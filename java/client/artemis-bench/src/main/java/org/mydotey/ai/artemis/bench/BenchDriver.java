package org.mydotey.ai.artemis.bench;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.mydotey.ai.artemis.client.ArtemisClients;
import org.mydotey.ai.artemis.client.DiscoveryClient;
import org.mydotey.ai.artemis.client.DiscoverySelector;
import org.mydotey.ai.artemis.client.RegistryClient;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;
import org.mydotey.scf.ConfigurationManager;
import org.mydotey.scf.facade.ConfigurationManagers;
import org.mydotey.scf.facade.SimpleConfigurationSources;
import org.mydotey.scf.facade.StringProperties;
import org.mydotey.scf.source.stringproperty.memorymap.MemoryMapConfigurationSource;

/**
 * 压测 driver 入口（编排与预检在 java/scripts/bench.sh）。
 *
 * <p>四角色：plan（输出档位展开与资源预算，bash 预检单一来源）/ registry（分片负载：S1 风暴、
 * S2 稳态、S3/S4 churn 注入）/ subscriber（S4 订阅与 listener 时间戳）/ join（t0×t1 配对出延迟分布）。
 * 每 client 独立 SCF memory-map 源（单 JVM 多 client 各自配置），共享 SimpleMeterRegistry
 * （counter 累加 = JVM 总视角）。CSV 输出到 reportDir，事后由 bench.sh 汇总。
 */
public final class BenchDriver {

    private static final long SAMPLE_INTERVAL_MS = 5_000;

    private BenchDriver() {}

    public static void main(String[] args) throws Exception {
        BenchConfig config = BenchConfig.parse(args);
        switch (config.role) {
            case "plan":
                for (Map.Entry<String, String> entry : config.planEntries().entrySet()) {
                    System.out.println(entry.getKey() + "=" + entry.getValue());
                }
                break;
            case "registry":
                runRegistryShard(config);
                break;
            case "subscriber":
                runSubscribers(config);
                break;
            case "join":
                runJoin(config);
                break;
            default:
                throw new IllegalStateException("unexpected role: " + config.role);
        }
    }

    // ---- registry 侧 ----

    private static void runRegistryShard(BenchConfig config) throws Exception {
        Path reportDir = ensureReportDir(config);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        List<Integer> processIds = config.shardProcessIds();
        System.out.println("shard " + config.shardIndex + "/" + config.shardCount + " processes=" + processIds);

        Map<Integer, RegistryClient> clients = new LinkedHashMap<>();
        for (int processId : processIds) {
            clients.put(processId, ArtemisClients.newRegistryClient(propertiesFor(config, processId), meters));
        }

        AtomicLong churnSeq = new AtomicLong();
        Path eventsCsv = reportDir.resolve("events-registry-" + config.scenario + "-" + config.shardIndex + ".csv");
        CsvAppender events = new CsvAppender(eventsCsv, "timestamp,service,seq");

        ScheduledExecutorService sampler = startSampler(config, meters, reportDir, "registry-" + config.shardIndex);
        long deadline = System.currentTimeMillis() + (config.rampSec + config.durationSec) * 1000L;

        try {
            // ramp：每 client 独立线程摊开（风暴爬坡按总速率并行，rampSec 内全部就位；非风暴直接快速注册）
            java.util.concurrent.ExecutorService rampPool =
                    Executors.newFixedThreadPool(Math.max(1, processIds.size()), r -> {
                        Thread thread = new Thread(r, "bench-ramp-" + config.shardIndex);
                        thread.setDaemon(true);
                        return thread;
                    });
            for (Map.Entry<Integer, RegistryClient> entry : clients.entrySet()) {
                final RegistryClient client = entry.getValue();
                final List<ServiceInstance> instances = config.instancesOfProcess(entry.getKey());
                rampPool.submit(() -> {
                    client.start();
                    if (config.isStorm() && config.rampSec > 0 && !instances.isEmpty()) {
                        long batchIntervalMs = config.rampSec * 1000L / instances.size();
                        for (ServiceInstance instance : instances) {
                            client.register(instance);
                            sleepQuietly(batchIntervalMs);
                        }
                    } else {
                        for (ServiceInstance instance : instances) {
                            client.register(instance);
                        }
                    }
                });
            }
            rampPool.shutdown();
            rampPool.awaitTermination(config.rampSec * 2 + 60, TimeUnit.SECONDS);
            System.out.println("ramp done at " + System.currentTimeMillis());

            // 稳态窗：S2 纯心跳（SDK 自持），churn 场景持续 update 注入（记 t0）
            if (config.hasChurn()) {
                ScheduledExecutorService churner = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread thread = new Thread(r, "bench-churn-" + config.shardIndex);
                    thread.setDaemon(true);
                    return thread;
                });
                // 本 shard 服务索引展平轮转（独立游标：每服务被均匀 churn，无进程/服务步长偏斜）
                List<Integer> shardServiceIndices = config.shardServiceIndices();
                final AtomicLong cursor = new AtomicLong();
                final long rotationEvery = 10; // 每 10 tick 一对 deregister/register（S3 10% 实例集轮换语义）
                churner.scheduleAtFixedRate(
                        () -> {
                            try {
                                int serviceIndex = shardServiceIndices.get(
                                        (int) (cursor.getAndIncrement() % shardServiceIndices.size()));
                                ServiceId service = config.serviceIdOf(serviceIndex);
                                int processId = config.processOfServiceIndex(serviceIndex);
                                RegistryClient client = clients.get(processId);
                                long seq = churnSeq.incrementAndGet();
                                long at = System.currentTimeMillis();
                                if (seq % rotationEvery == 0) {
                                    // 轮换 tick：DELETE→NEW 全链路（净集合规模不变）
                                    client.deregister(config.instanceIdOf(serviceIndex, 0));
                                    client.register(withSeq(config, processId, service, seq));
                                } else {
                                    client.update(withSeq(config, processId, service, seq));
                                }
                                events.append(at + "," + service.getService() + "," + seq);
                            } catch (RuntimeException e) {
                                System.err.println("churn tick failed: " + e.getMessage());
                            }
                        },
                        0,
                        config.churnIntervalMs,
                        TimeUnit.MILLISECONDS);
                waitUntil(deadline);
                churner.shutdownNow();
            } else {
                waitUntil(deadline);
            }
        } finally {
            sampler.shutdownNow();
            events.close();
            for (RegistryClient client : clients.values()) {
                closeQuietly(client);
            }
            meters.close();
        }
        System.out.println("registry shard finished, churnSeq=" + churnSeq.get());
    }

    /** churn update 目标实例：服务首实例 + bench-seq metadata（内容变化触发 UPDATE）。 */
    private static ServiceInstance withSeq(BenchConfig config, int processId, ServiceId service, long seq) {
        List<ServiceInstance> instances = config.instancesOfProcess(processId);
        for (ServiceInstance instance : instances) {
            if (instance.getService().equals(service)) {
                Map<String, String> metadata = new HashMap<>(instance.getMetadata());
                metadata.put("bench-seq", String.valueOf(seq));
                return new ServiceInstance.Builder()
                        .service(service)
                        .instanceId(instance.getInstanceId())
                        .host(instance.getHost())
                        .port(instance.getPort())
                        .metadata(metadata)
                        .build();
            }
        }
        throw new IllegalStateException("no instance of " + service);
    }

    // ---- subscriber 侧 ----

    private static void runSubscribers(BenchConfig config) throws Exception {
        Path reportDir = ensureReportDir(config);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Path eventsCsv = reportDir.resolve("events-subscriber.csv");
        CsvAppender events = new CsvAppender(eventsCsv, "timestamp,service,subscriber");
        List<DiscoveryClient> clients = new ArrayList<>();
        AtomicLong observations = new AtomicLong();

        ScheduledExecutorService sampler = startSampler(config, meters, reportDir, "subscriber");
        long deadline = System.currentTimeMillis() + (config.rampSec + config.durationSec) * 1000L;
        try {
            for (int q = 0; q < config.subscribers; q++) {
                Map<String, String> props = new HashMap<>();
                props.put("artemis.client.clientId", "bench-sub-" + q);
                props.put("artemis.client.discovery.addresses", config.discoveryTarget);
                props.put(
                        "artemis.client.discovery.snapshot.dir",
                        reportDir.resolve("snap-" + q).toString());
                props.put("artemis.client.discovery.reconcile.intervalMs", "600000");
                props.put("artemis.client.discovery.refresh.intervalMs", "600000");
                DiscoveryClient client = ArtemisClients.newDiscoveryClient(properties("bench-sub-" + q, props), meters);
                client.start();
                // 空服务集 = 全部服务（订阅全量，S4 语义）
                final String subscriberId = "sub-" + q;
                client.subscribe(new DiscoverySelector.Builder().build(), (ServiceId service) -> {
                    events.append(System.currentTimeMillis() + "," + service.getService() + "," + subscriberId);
                    observations.incrementAndGet();
                });
                clients.add(client);
            }
            waitUntil(deadline);
        } finally {
            sampler.shutdownNow();
            events.close();
            for (DiscoveryClient client : clients) {
                closeQuietly(client);
            }
            meters.close();
        }
        System.out.println("subscriber finished, observations=" + observations.get());
    }

    // ---- join ----

    private static void runJoin(BenchConfig config) throws Exception {
        Path reportDir = Paths.get(config.reportDir);
        List<LatencyPairer.Injection> injections = new ArrayList<>();
        for (int shard = 0; shard < config.shardCount; shard++) {
            Path eventsCsv = reportDir.resolve("events-registry-" + config.scenario + "-" + shard + ".csv");
            if (!Files.exists(eventsCsv)) {
                continue;
            }
            for (String line : Files.readAllLines(eventsCsv, StandardCharsets.UTF_8)) {
                if (line.startsWith("timestamp")) {
                    continue;
                }
                String[] parts = line.split(",");
                injections.add(
                        new LatencyPairer.Injection(parts[1], Long.parseLong(parts[2]), Long.parseLong(parts[0])));
            }
        }
        List<LatencyPairer.Observation> observations = new ArrayList<>();
        Path subscriberCsv = reportDir.resolve("events-subscriber.csv");
        if (Files.exists(subscriberCsv)) {
            for (String line : Files.readAllLines(subscriberCsv, StandardCharsets.UTF_8)) {
                if (line.startsWith("timestamp")) {
                    continue;
                }
                String[] parts = line.split(",");
                observations.add(new LatencyPairer.Observation(parts[1], parts[2], Long.parseLong(parts[0])));
            }
        }
        List<LatencyPairer.Sample> samples = LatencyPairer.pair(injections, observations);
        try (CsvAppender writer = new CsvAppender(
                reportDir.resolve("latency-" + config.scenario + ".csv"),
                "service,subscriber,seq,injectedAt,observedAt,latencyMs")) {
            for (LatencyPairer.Sample sample : samples) {
                writer.append(sample.service + "," + sample.subscriber + "," + sample.seq + "," + sample.injectedAt
                        + "," + sample.observedAt + "," + sample.latencyMs);
            }
        }
        System.out.println("push-latency " + LatencyPairer.summarize(samples));
    }

    // ---- 共用设施 ----

    private static StringProperties propertiesFor(BenchConfig config, int processId) {
        Map<String, String> props = new HashMap<>();
        props.put("artemis.client.clientId", "bench-p" + processId);
        props.put("artemis.client.registry.addresses", config.target);
        // 寻址节奏压测化：默认 300s 周期/60s 重试上限对分钟级场景太钝（首寻址迟迟不发生）
        props.put("artemis.client.registry.address.refreshIntervalMs", "1000");
        props.put("artemis.client.registry.address.retryBaseMs", "200");
        props.put("artemis.client.registry.address.retryMaxMs", "2000");
        return properties("bench-p" + processId, props);
    }

    private static StringProperties properties(String managerName, Map<String, String> props) {
        MemoryMapConfigurationSource source = SimpleConfigurationSources.newMemoryMapSource(managerName);
        source.setProperties(props);
        ConfigurationManager manager = ConfigurationManagers.newManager(managerName, source);
        return new StringProperties(manager);
    }

    private static ScheduledExecutorService startSampler(
            BenchConfig config, SimpleMeterRegistry meters, Path reportDir, String label) throws IOException {
        CsvAppender writer = new CsvAppender(reportDir.resolve("samples-" + label + ".csv"), "timestamp,metric,value");
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "bench-sampler-" + label);
            thread.setDaemon(true);
            return thread;
        });
        sampler.scheduleAtFixedRate(
                () -> {
                    StringBuilder row = new StringBuilder();
                    long now = System.currentTimeMillis();
                    for (Meter meter : meters.getMeters()) {
                        String value = null;
                        if (meter instanceof Timer) {
                            value = String.valueOf(((Timer) meter).count());
                        } else if (meter instanceof io.micrometer.core.instrument.Counter) {
                            value = String.valueOf(((io.micrometer.core.instrument.Counter) meter).count());
                        } else if (meter instanceof io.micrometer.core.instrument.Gauge) {
                            value = String.valueOf(((io.micrometer.core.instrument.Gauge) meter).value());
                        }
                        if (value != null) {
                            row.setLength(0);
                            row.append(now)
                                    .append(",")
                                    .append(meter.getId().getName())
                                    .append("[")
                                    .append(meter.getId().getTags().toString().replace(',', ';'))
                                    .append("]")
                                    .append(",")
                                    .append(value);
                            writer.append(row.toString());
                        }
                    }
                },
                SAMPLE_INTERVAL_MS,
                SAMPLE_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
        return sampler;
    }

    private static Path ensureReportDir(BenchConfig config) throws IOException {
        Path dir = Paths.get(config.reportDir);
        Files.createDirectories(dir);
        return dir;
    }

    private static void waitUntil(long deadlineMillis) {
        long remaining;
        while ((remaining = deadlineMillis - System.currentTimeMillis()) > 0) {
            sleepQuietly(Math.min(remaining, 10_000));
        }
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            System.err.println("close failed: " + e.getMessage());
        }
    }
}
