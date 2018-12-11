/*
 * Copyright 2018-present Open Networking Foundation
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
package io.atomix.bench;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.UUID;

/**
 * Benchmark configuration.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    property = "type"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = MapBenchmarkConfig.class, name = "map")
})
public abstract class BenchmarkConfig {
  private static final int DEFAULT_OPERATIONS = 10000;

  private String benchId = UUID.randomUUID().toString();
  private String type;
  private int operations = DEFAULT_OPERATIONS;

  public BenchmarkConfig() {
  }

  public BenchmarkConfig(BenchmarkConfig config) {
    this.benchId = config.benchId;
    this.operations = config.operations;
  }

  public String getBenchId() {
    return benchId;
  }

  public BenchmarkConfig setBenchId(String benchId) {
    this.benchId = benchId;
    return this;
  }

  public abstract String getType();

  public int getOperations() {
    return operations;
  }

  public BenchmarkConfig setOperations(int operations) {
    this.operations = operations;
    return this;
  }

  abstract BenchmarkConfig copy();
}