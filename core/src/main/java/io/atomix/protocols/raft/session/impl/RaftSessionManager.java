/*
 * Copyright 2017-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.protocols.raft.session.impl;

import io.atomix.cluster.ClusterCommunicationService;
import io.atomix.cluster.NodeId;
import io.atomix.protocols.raft.client.CommunicationStrategies;
import io.atomix.protocols.raft.client.CommunicationStrategy;
import io.atomix.protocols.raft.session.RaftSession;
import io.atomix.protocols.raft.error.UnknownSessionException;
import io.atomix.protocols.raft.protocol.CloseSessionRequest;
import io.atomix.protocols.raft.protocol.CloseSessionResponse;
import io.atomix.protocols.raft.protocol.KeepAliveRequest;
import io.atomix.protocols.raft.protocol.KeepAliveResponse;
import io.atomix.protocols.raft.protocol.OpenSessionRequest;
import io.atomix.protocols.raft.protocol.OpenSessionResponse;
import io.atomix.protocols.raft.protocol.RaftResponse;
import io.atomix.util.concurrent.Futures;
import io.atomix.util.concurrent.OrderedExecutor;
import io.atomix.util.serializer.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Client session manager.
 *
 * @author <a href="http://github.com/kuujo>Jordan Halterman</a>
 */
public class RaftSessionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(RaftSessionManager.class);
    private final String clientId;
    private final String clusterName;
    private final ClusterCommunicationService communicationService;
    private final RaftConnection connection;
    private final ScheduledExecutorService threadPoolExecutor;
    private final NodeSelectorManager selectorManager;
    private final Map<Long, RaftSessionState> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean open = new AtomicBoolean();
    private ScheduledFuture<?> keepAliveFuture;

    public RaftSessionManager(String clientId, String clusterName, ClusterCommunicationService communicationService, NodeSelectorManager selectorManager, ScheduledExecutorService threadPoolExecutor) {
        this.clientId = checkNotNull(clientId, "clientId cannot be null");
        this.clusterName = checkNotNull(clusterName, "clusterName cannot be null");
        this.communicationService = checkNotNull(communicationService, "communicationService cannot be null");
        this.selectorManager = checkNotNull(selectorManager, "selectorManager cannot be null");
        this.connection = new RaftClientConnection(clientId, clusterName, communicationService, selectorManager.createSelector(CommunicationStrategies.ANY));
        this.threadPoolExecutor = checkNotNull(threadPoolExecutor, "threadPoolExecutor cannot be null");
    }

    /**
     * Resets the session manager's cluster information.
     */
    public void resetConnections() {
        selectorManager.resetAll();
    }

    /**
     * Resets the session manager's cluster information.
     *
     * @param leader  The leader address.
     * @param servers The collection of servers.
     */
    public void resetConnections(NodeId leader, Collection<NodeId> servers) {
        selectorManager.resetAll(leader, servers);
    }

    /**
     * Opens the session manager.
     *
     * @return A completable future to be called once the session manager is opened.
     */
    public CompletableFuture<Void> open() {
        open.set(true);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Opens a new session.
     *
     * @param name                  The session name.
     * @param stateMachine                  The session type.
     * @param communicationStrategy The strategy with which to communicate with servers.
     * @param timeout               The session timeout.
     * @return A completable future to be completed once the session has been opened.
     */
    public CompletableFuture<RaftSession> openSession(
            String name,
            String stateMachine,
            CommunicationStrategy communicationStrategy,
            Serializer serializer,
            Duration timeout) {
        LOGGER.trace("{} - Opening session; name: {}, type: {}", clientId, name, stateMachine);
        OpenSessionRequest request = OpenSessionRequest.builder()
                .withClient(clientId)
                .withStateMachine(stateMachine)
                .withName(name)
                .withTimeout(timeout.toMillis())
                .build();

        LOGGER.trace("{} - Sending {}", clientId, request);
        CompletableFuture<RaftSession> future = new CompletableFuture<>();
        Executor sessionExecutor = new OrderedExecutor(threadPoolExecutor);
        connection.<OpenSessionRequest, OpenSessionResponse>sendAndReceive(request).whenCompleteAsync((response, error) -> {
            if (error == null) {
                if (response.status() == RaftResponse.Status.OK) {
                    RaftSessionState state = new RaftSessionState(
                            response.session(), name, stateMachine, response.timeout());
                    sessions.put(state.getSessionId(), state);
                    keepAliveSessions();
                    future.complete(new DefaultRaftSession(
                            clientId,
                            clusterName,
                            state,
                            communicationService,
                            selectorManager,
                            this,
                            communicationStrategy,
                            serializer,
                            sessionExecutor,
                            threadPoolExecutor));
                } else {
                    future.completeExceptionally(response.error().createException());
                }
            } else {
                future.completeExceptionally(error);
            }
        }, sessionExecutor);
        return future;
    }

    /**
     * Closes a session.
     *
     * @param sessionId The session identifier.
     * @return A completable future to be completed once the session is closed.
     */
    public CompletableFuture<Void> closeSession(long sessionId) {
        RaftSessionState state = sessions.get(sessionId);
        if (state == null) {
            return Futures.exceptionalFuture(new UnknownSessionException("Unknown session: " + sessionId));
        }

        LOGGER.trace("Closing session {}", sessionId);
        CloseSessionRequest request = CloseSessionRequest.builder()
                .withSession(sessionId)
                .build();

        LOGGER.trace("Sending {}", request);
        CompletableFuture<Void> future = new CompletableFuture<>();
        connection.<CloseSessionRequest, CloseSessionResponse>sendAndReceive(request).whenComplete((response, error) -> {
            if (error == null) {
                if (response.status() == RaftResponse.Status.OK) {
                    sessions.remove(sessionId);
                    future.complete(null);
                } else {
                    future.completeExceptionally(response.error().createException());
                }
            } else {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    /**
     * Resets indexes for the given session.
     *
     * @param sessionId The session for which to reset indexes.
     * @return A completable future to be completed once the session's indexes have been reset.
     */
    CompletableFuture<Void> resetIndexes(long sessionId) {
        RaftSessionState sessionState = sessions.get(sessionId);
        if (sessionState == null) {
            return Futures.exceptionalFuture(new IllegalArgumentException("Unknown session: " + sessionId));
        }

        CompletableFuture<Void> future = new CompletableFuture<>();

        KeepAliveRequest request = KeepAliveRequest.builder()
                .withSessionIds(new long[]{sessionId})
                .withCommandSequences(new long[]{sessionState.getCommandResponse()})
                .withEventIndexes(new long[]{sessionState.getEventIndex()})
                .withConnections(new long[]{sessionState.getConnection()})
                .build();

        LOGGER.trace("{} - Sending {}", clientId, request);
        connection.<KeepAliveRequest, KeepAliveResponse>sendAndReceive(request).whenComplete((response, error) -> {
            if (error == null) {
                LOGGER.trace("{} - Received {}", clientId, response);
                if (response.status() == RaftResponse.Status.OK) {
                    future.complete(null);
                } else {
                    future.completeExceptionally(response.error().createException());
                }
            } else {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    /**
     * Sends a keep-alive request to the cluster.
     */
    private void keepAliveSessions() {
        keepAliveSessions(true);
    }

    /**
     * Sends a keep-alive request to the cluster.
     */
    private synchronized void keepAliveSessions(boolean retryOnFailure) {
        Map<Long, RaftSessionState> sessions = new HashMap<>(this.sessions);
        long[] sessionIds = new long[sessions.size()];
        long[] commandResponses = new long[sessions.size()];
        long[] eventIndexes = new long[sessions.size()];
        long[] connections = new long[sessions.size()];

        int i = 0;
        for (RaftSessionState sessionState : sessions.values()) {
            sessionIds[i] = sessionState.getSessionId();
            commandResponses[i] = sessionState.getCommandResponse();
            eventIndexes[i] = sessionState.getEventIndex();
            connections[i] = sessionState.getConnection();
            i++;
        }

        KeepAliveRequest request = KeepAliveRequest.builder()
                .withSessionIds(sessionIds)
                .withCommandSequences(commandResponses)
                .withEventIndexes(eventIndexes)
                .withConnections(connections)
                .build();

        LOGGER.trace("{} - Sending {}", clientId, request);
        connection.<KeepAliveRequest, KeepAliveResponse>sendAndReceive(request).whenComplete((response, error) -> {
            if (open.get()) {
                if (error == null) {
                    LOGGER.trace("{} - Received {}", clientId, response);
                    // If the request was successful, update the address selector and schedule the next keep-alive.
                    if (response.status() == RaftResponse.Status.OK) {
                        selectorManager.resetAll(response.leader(), response.members());
                        sessions.values().forEach(s -> s.setState(RaftSession.State.CONNECTED));
                        scheduleKeepAlive();
                    }
                    // If a leader is still set in the address selector, unset the leader and attempt to send another keep-alive.
                    // This will ensure that the address selector selects all servers without filtering on the leader.
                    else if (retryOnFailure && connection.leader() != null) {
                        selectorManager.resetAll(null, connection.servers());
                        keepAliveSessions(false);
                    }
                    // If no leader was set, set the session state to unstable and schedule another keep-alive.
                    else {
                        sessions.values().forEach(s -> s.setState(RaftSession.State.SUSPENDED));
                        selectorManager.resetAll();
                        scheduleKeepAlive();
                    }
                }
                // If a leader is still set in the address selector, unset the leader and attempt to send another keep-alive.
                // This will ensure that the address selector selects all servers without filtering on the leader.
                else if (retryOnFailure && connection.leader() != null) {
                    selectorManager.resetAll(null, connection.servers());
                    keepAliveSessions(false);
                }
                // If no leader was set, set the session state to unstable and schedule another keep-alive.
                else {
                    sessions.values().forEach(s -> s.setState(RaftSession.State.SUSPENDED));
                    selectorManager.resetAll();
                    scheduleKeepAlive();
                }
            }
        });
    }

    /**
     * Schedules a keep-alive request.
     */
    private void scheduleKeepAlive() {
        OptionalLong minTimeout = sessions.values().stream().mapToLong(RaftSessionState::getSessionTimeout).min();
        if (minTimeout.isPresent()) {
            synchronized (this) {
                if (keepAliveFuture != null) {
                    keepAliveFuture.cancel(false);
                }

                keepAliveFuture = threadPoolExecutor.schedule(() -> {
                    if (open.get()) {
                        keepAliveSessions();
                    }
                }, minTimeout.getAsLong() / 2, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * Closes the session manager.
     *
     * @return A completable future to be completed once the session manager is closed.
     */
    public CompletableFuture<Void> close() {
        if (open.compareAndSet(true, false)) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            threadPoolExecutor.execute(() -> {
                synchronized (this) {
                    if (keepAliveFuture != null) {
                        keepAliveFuture.cancel(false);
                        keepAliveFuture = null;
                    }
                }
                future.complete(null);
            });
            return future;
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Kills the client session manager.
     *
     * @return A completable future to be completed once the session manager is killed.
     */
    public CompletableFuture<Void> kill() {
        return CompletableFuture.runAsync(() -> {
            synchronized (this) {
                if (keepAliveFuture != null) {
                    keepAliveFuture.cancel(false);
                }
            }
        }, threadPoolExecutor);
    }

    @Override
    public String toString() {
        return String.format("%s[client=%s]", getClass().getSimpleName(), clientId);
    }

}