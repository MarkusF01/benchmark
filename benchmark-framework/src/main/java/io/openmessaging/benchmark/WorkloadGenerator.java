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

import static java.util.concurrent.TimeUnit.MINUTES;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.openmessaging.benchmark.utils.PaddingDecimalFormat;
import io.openmessaging.benchmark.utils.RandomGenerator;
import io.openmessaging.benchmark.utils.Timer;
import io.openmessaging.benchmark.utils.payload.FilePayloadReader;
import io.openmessaging.benchmark.utils.payload.PayloadReader;
import io.openmessaging.benchmark.worker.Worker;
import io.openmessaging.benchmark.worker.commands.ConsumerAssignment;
import io.openmessaging.benchmark.worker.commands.CountersStats;
import io.openmessaging.benchmark.worker.commands.CumulativeLatencies;
import io.openmessaging.benchmark.worker.commands.PeriodStats;
import io.openmessaging.benchmark.worker.commands.ProducerWorkAssignment;
import io.openmessaging.benchmark.worker.commands.TopicSubscription;
import io.openmessaging.benchmark.worker.commands.TopicsInfo;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang.ArrayUtils;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.Dsl;
import org.asynchttpclient.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkloadGenerator implements AutoCloseable {

    private final String driverName;
    private final Workload workload;
    private final Worker worker;
    private final String brokerBasePath;

    private final ExecutorService executor =
            Executors.newCachedThreadPool(new DefaultThreadFactory("messaging-benchmark"));

    private volatile boolean runCompleted = false;
    private volatile boolean needToWaitForBacklogDraining = false;

    private volatile double targetPublishRate;

    public WorkloadGenerator(
            String driverName, Workload workload, Worker worker, String brokerBasePath) {
        this.driverName = driverName;
        this.workload = workload;
        this.worker = worker;
        this.brokerBasePath = brokerBasePath;

        if (workload.consumerBacklogSizeGB > 0 && workload.producerRate == 0) {
            throw new IllegalArgumentException(
                    "Cannot probe producer sustainable rate when building backlog");
        }
    }

    public TestResult run() throws Exception {
        Timer timer = new Timer();
        List<String> topics =
                worker.createTopics(new TopicsInfo(workload.topics, workload.partitionsPerTopic));
        log.info("Created {} topics in {} ms", topics.size(), timer.elapsedMillis());

        createConsumers(topics);
        createProducers(topics);

        ensureTopicsAreReady();

        if (workload.producerRate > 0) {
            targetPublishRate = workload.producerRate;
        } else {
            // Producer rate is 0 and we need to discover the sustainable rate
            targetPublishRate = 10000;

            executor.execute(
                    () -> {
                        // Run background controller to adjust rate
                        try {
                            findMaximumSustainableRate(targetPublishRate);
                        } catch (IOException e) {
                            log.warn("Failure in finding max sustainable rate", e);
                        }
                    });
        }

        final PayloadReader payloadReader = new FilePayloadReader(workload.messageSize);

        ProducerWorkAssignment producerWorkAssignment = new ProducerWorkAssignment();
        producerWorkAssignment.keyDistributorType = workload.keyDistributor;
        producerWorkAssignment.publishRate = targetPublishRate;
        producerWorkAssignment.payloadData = new ArrayList<>();

        if (workload.useRandomizedPayloads) {
            // create messages that are part random and part zeros
            // better for testing effects of compression
            Random r = new Random();
            int randomBytes = (int) (workload.messageSize * workload.randomBytesRatio);
            int zerodBytes = workload.messageSize - randomBytes;
            for (int i = 0; i < workload.randomizedPayloadPoolSize; i++) {
                byte[] randArray = new byte[randomBytes];
                r.nextBytes(randArray);
                byte[] zerodArray = new byte[zerodBytes];
                byte[] combined = ArrayUtils.addAll(randArray, zerodArray);
                producerWorkAssignment.payloadData.add(combined);
            }
        } else {
            producerWorkAssignment.payloadData.add(payloadReader.load(workload.payloadFile));
        }

        worker.startLoad(producerWorkAssignment);

        if (workload.warmupDurationMinutes > 0) {
            log.info("----- Starting warm-up traffic ({}m) ------", workload.warmupDurationMinutes);
            printAndCollectStats(workload.warmupDurationMinutes, TimeUnit.MINUTES);
        }

        if (workload.consumerBacklogSizeGB > 0) {
            executor.execute(
                    () -> {
                        try {
                            buildAndDrainBacklog(workload.testDurationMinutes);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
        }

        worker.resetStats();
        log.info("----- Starting benchmark traffic ({}m)------", workload.testDurationMinutes);

        // start tracking system resources
        sendHttpRequest(Dsl.asyncHttpClient(), "http://" + brokerBasePath + ":5000/start");

        TestResult result = printAndCollectStats(workload.testDurationMinutes, TimeUnit.MINUTES);
        runCompleted = true;

        // stop tracking system resources
        String jsonResponse =
                sendHttpRequest(Dsl.asyncHttpClient(), "http://" + brokerBasePath + "/stop").get();
        parseTrackingData(jsonResponse, result);

        worker.stopAll();
        return result;
    }

    private void ensureTopicsAreReady() throws IOException {
        log.info("Waiting for consumers to be ready");
        // This is work around the fact that there's no way to have a consumer ready in Kafka without
        // first publishing
        // some message on the topic, which will then trigger the partitions assignment to the consumers

        int expectedMessages = workload.topics * workload.subscriptionsPerTopic;

        // In this case we just publish 1 message and then wait for consumers to receive the data
        worker.probeProducers();

        long start = System.currentTimeMillis();
        long end = start + 60 * 1000;
        while (System.currentTimeMillis() < end) {
            CountersStats stats = worker.getCountersStats();

            log.info(
                    "Waiting for topics to be ready -- Sent: {}, Received: {}",
                    stats.messagesSent,
                    stats.messagesReceived);
            if (stats.messagesReceived < expectedMessages) {
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            } else {
                break;
            }
        }

        if (System.currentTimeMillis() >= end) {
            throw new RuntimeException("Timed out waiting for consumers to be ready");
        } else {
            log.info("All consumers are ready");
        }
    }

    /**
     * Adjust the publish rate to a level that is sustainable, meaning that we can consume all the
     * messages that are being produced.
     *
     * @param currentRate
     */
    private void findMaximumSustainableRate(double currentRate) throws IOException {
        CountersStats stats = worker.getCountersStats();

        int controlPeriodMillis = 3000;
        long lastControlTimestamp = System.nanoTime();

        RateController rateController = new RateController();

        while (!runCompleted) {
            // Check every few seconds and adjust the rate
            try {
                Thread.sleep(controlPeriodMillis);
            } catch (InterruptedException e) {
                return;
            }

            // Consider multiple copies when using multiple subscriptions
            stats = worker.getCountersStats();
            long currentTime = System.nanoTime();
            long periodNanos = currentTime - lastControlTimestamp;

            lastControlTimestamp = currentTime;

            currentRate =
                    rateController.nextRate(
                            currentRate, periodNanos, stats.messagesSent, stats.messagesReceived);
            worker.adjustPublishRate(currentRate);
        }
    }

    @Override
    public void close() throws Exception {
        worker.stopAll();
        executor.shutdownNow();
    }

    private void createConsumers(List<String> topics) throws IOException {
        ConsumerAssignment consumerAssignment = new ConsumerAssignment();

        for (String topic : topics) {
            for (int i = 0; i < workload.subscriptionsPerTopic; i++) {
                String subscriptionName =
                        String.format("sub-%03d-%s", i, RandomGenerator.getRandomString());
                for (int j = 0; j < workload.consumerPerSubscription; j++) {
                    consumerAssignment.topicsSubscriptions.add(
                            new TopicSubscription(topic, subscriptionName));
                }
            }
        }

        Collections.shuffle(consumerAssignment.topicsSubscriptions);

        Timer timer = new Timer();

        worker.createConsumers(consumerAssignment);
        log.info(
                "Created {} consumers in {} ms",
                consumerAssignment.topicsSubscriptions.size(),
                timer.elapsedMillis());
    }

    private void createProducers(List<String> topics) throws IOException {
        List<String> fullListOfTopics = new ArrayList<>();

        // Add the topic multiple times, one for each producer
        for (int i = 0; i < workload.producersPerTopic; i++) {
            fullListOfTopics.addAll(topics);
        }

        Collections.shuffle(fullListOfTopics);

        Timer timer = new Timer();

        worker.createProducers(fullListOfTopics);
        log.info("Created {} producers in {} ms", fullListOfTopics.size(), timer.elapsedMillis());
    }

    private void buildAndDrainBacklog(int testDurationMinutes) throws IOException {
        Timer timer = new Timer();
        log.info("Stopping all consumers to build backlog");
        worker.pauseConsumers();

        this.needToWaitForBacklogDraining = true;

        long requestedBacklogSize = workload.consumerBacklogSizeGB * 1024 * 1024 * 1024;

        while (true) {
            CountersStats stats = worker.getCountersStats();
            long currentBacklogSize =
                    (workload.subscriptionsPerTopic * stats.messagesSent - stats.messagesReceived)
                            * workload.messageSize;

            if (currentBacklogSize >= requestedBacklogSize) {
                break;
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        log.info("--- Completed backlog build in {} s ---", timer.elapsedSeconds());
        timer = new Timer();
        log.info("--- Start draining backlog ---");

        worker.resumeConsumers();

        long backlogMessageCapacity = requestedBacklogSize / workload.messageSize;
        long backlogEmptyLevel = (long) ((1.0 - workload.backlogDrainRatio) * backlogMessageCapacity);
        final long minBacklog = Math.max(1000L, backlogEmptyLevel);

        while (true) {
            CountersStats stats = worker.getCountersStats();
            long currentBacklog =
                    workload.subscriptionsPerTopic * stats.messagesSent - stats.messagesReceived;
            if (currentBacklog <= minBacklog) {
                log.info("--- Completed backlog draining in {} s ---", timer.elapsedSeconds());

                try {
                    Thread.sleep(MINUTES.toMillis(testDurationMinutes));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                needToWaitForBacklogDraining = false;
                return;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @SuppressWarnings({"checkstyle:LineLength", "checkstyle:MethodLength"})
    private TestResult printAndCollectStats(long testDurations, TimeUnit unit)
            throws IOException, ExecutionException, InterruptedException {
        long startTime = System.nanoTime();

        // Print report stats
        long oldTime = System.nanoTime();

        long testEndTime = testDurations > 0 ? startTime + unit.toNanos(testDurations) : Long.MAX_VALUE;

        TestResult result = new TestResult();
        result.workload = workload.name;
        result.driver = driverName;
        result.topics = workload.topics;
        result.partitions = workload.partitionsPerTopic;
        result.messageSize = workload.messageSize;
        result.producersPerTopic = workload.producersPerTopic;
        result.consumersPerTopic = workload.consumerPerSubscription;

        while (true) {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                break;
            }

            PeriodStats stats = worker.getPeriodStats();

            long now = System.nanoTime();
            double elapsed = (now - oldTime) / 1e9;

            double publishRate = stats.messagesSent / elapsed;
            double publishThroughput = stats.bytesSent / elapsed / 1024 / 1024;
            double errorRate = stats.messageSendErrors / elapsed;

            double consumeRate = stats.messagesReceived / elapsed;
            double consumeThroughput = stats.bytesReceived / elapsed / 1024 / 1024;

            long currentBacklog =
                    Math.max(
                            0L,
                            workload.subscriptionsPerTopic * stats.totalMessagesSent
                                    - stats.totalMessagesReceived);

            log.info(
                    "Pub rate {} msg/s / {} MB/s | Pub err {} err/s | Cons rate {} msg/s / {} MB/s | Backlog: {} K | Pub Latency (ms) avg: {} - 50%: {} - 99%: {} - 99.9%: {} - Max: {} | Pub Delay Latency (us) avg: {} - 50%: {} - 99%: {} - 99.9%: {} - Max: {}",
                    rateFormat.format(publishRate),
                    throughputFormat.format(publishThroughput),
                    rateFormat.format(errorRate),
                    rateFormat.format(consumeRate),
                    throughputFormat.format(consumeThroughput),
                    dec.format(currentBacklog / 1000.0), //
                    dec.format(microsToMillis(stats.publishLatency.getMean())),
                    dec.format(microsToMillis(stats.publishLatency.getValueAtPercentile(50))),
                    dec.format(microsToMillis(stats.publishLatency.getValueAtPercentile(99))),
                    dec.format(microsToMillis(stats.publishLatency.getValueAtPercentile(99.9))),
                    throughputFormat.format(microsToMillis(stats.publishLatency.getMaxValue())),
                    dec.format(stats.publishDelayLatency.getMean()),
                    dec.format(stats.publishDelayLatency.getValueAtPercentile(50)),
                    dec.format(stats.publishDelayLatency.getValueAtPercentile(99)),
                    dec.format(stats.publishDelayLatency.getValueAtPercentile(99.9)),
                    throughputFormat.format(stats.publishDelayLatency.getMaxValue()));

            result.publishRate.add(publishRate);
            result.publishErrorRate.add(errorRate);
            result.consumeRate.add(consumeRate);
            result.backlog.add(currentBacklog);

            result.endToEndLatencyAvg.add(microsToMillis(stats.endToEndLatency.getMean()));
            result.endToEndLatencyMax.add(microsToMillis(stats.endToEndLatency.getMaxValue()));

            if (now >= testEndTime && !needToWaitForBacklogDraining) {
                CumulativeLatencies agg = worker.getCumulativeLatencies();
                log.info(
                        "----- Aggregated Pub Latency (ms) avg: {} - 50%: {} - 95%: {} - 99%: {} - 99.9%: {} - 99.99%: {} - Max: {} | Pub Delay (us)  avg: {} - 50%: {} - 95%: {} - 99%: {} - 99.9%: {} - 99.99%: {} - Max: {}",
                        dec.format(agg.publishLatency.getMean() / 1000.0),
                        dec.format(agg.publishLatency.getValueAtPercentile(50) / 1000.0),
                        dec.format(agg.publishLatency.getValueAtPercentile(95) / 1000.0),
                        dec.format(agg.publishLatency.getValueAtPercentile(99) / 1000.0),
                        dec.format(agg.publishLatency.getValueAtPercentile(99.9) / 1000.0),
                        dec.format(agg.publishLatency.getValueAtPercentile(99.99) / 1000.0),
                        throughputFormat.format(agg.publishLatency.getMaxValue() / 1000.0),
                        dec.format(agg.publishDelayLatency.getMean()),
                        dec.format(agg.publishDelayLatency.getValueAtPercentile(50)),
                        dec.format(agg.publishDelayLatency.getValueAtPercentile(95)),
                        dec.format(agg.publishDelayLatency.getValueAtPercentile(99)),
                        dec.format(agg.publishDelayLatency.getValueAtPercentile(99.9)),
                        dec.format(agg.publishDelayLatency.getValueAtPercentile(99.99)),
                        throughputFormat.format(agg.publishDelayLatency.getMaxValue()));
                break;
            }

            oldTime = now;
        }

        return result;
    }

    private CompletableFuture<String> sendHttpRequest(AsyncHttpClient client, String url) {
        return client
                .prepareGet(url)
                .execute()
                .toCompletableFuture()
                .thenApply(Response::getResponseBody);
    }

    private void parseTrackingData(String jsonResponse, TestResult result)
            throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode rootNode = objectMapper.readTree(jsonResponse);
        JsonNode dataArray = rootNode.get("data");

        if (dataArray.isArray()) {
            for (JsonNode entry : dataArray) {
                result.cpuUsage.add(entry.get("cpu").asDouble());
                result.memoryUsage.add(entry.get("memory").asDouble());
            }
        }
    }

    private static final DecimalFormat rateFormat = new PaddingDecimalFormat("0.0", 7);
    private static final DecimalFormat throughputFormat = new PaddingDecimalFormat("0.0", 4);
    private static final DecimalFormat dec = new PaddingDecimalFormat("0.0", 4);

    private static double microsToMillis(double timeInMicros) {
        return timeInMicros / 1000.0;
    }

    private static double microsToMillis(long timeInMicros) {
        return timeInMicros / 1000.0;
    }

    private static final Logger log = LoggerFactory.getLogger(WorkloadGenerator.class);
}
