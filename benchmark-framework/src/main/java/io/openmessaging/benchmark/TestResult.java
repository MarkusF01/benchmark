/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.openmessaging.benchmark;


import java.util.ArrayList;
import java.util.List;

public class TestResult {
    public String workload;
    public String driver;
    public long messageSize;
    public int topics;
    public int partitions;
    public int producersPerTopic;
    public int consumersPerTopic;

    public List<Double> publishRate = new ArrayList<>();
    public List<Double> publishErrorRate = new ArrayList<>();
    public List<Double> consumeRate = new ArrayList<>();
    public List<Long> backlog = new ArrayList<>();

    // End to end latencies (from producer to consumer)
    // Latencies are expressed in milliseconds (without decimals)
    public List<Double> endToEndLatencyAvg = new ArrayList<>();
    public List<Double> endToEndLatencyMax = new ArrayList<>();

    public List<Double> cpuUsage = new ArrayList<>();
    public List<Double> memoryUsage = new ArrayList<>();

    public int getTopics() {
        return topics;
    }

    public int getPartitions() {
        return partitions;
    }

    public long getMessageSize() {
        return messageSize;
    }
}
