package org.mydotey.ai.artemis.bench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.mydotey.ai.artemis.client.InstanceId;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;

/**
 * 压测配置：参数解析、档位展开、分片与确定性实例分配、资源预算（phase 设计文档「规模参数体系与资源模型」）。
 *
 * <p>两层规模参数：逻辑规模（S×I×P×Q，服务端协议面）+ 物理布局（topology/driver JVM 数，机器资源来源）。
 * 档位预置全维度组合，显式参数覆盖档位值（覆盖优先级：显式 &gt; 档位）；custom 档全显式给。
 * 实例分配确定性：进程 p 持服务索引区间 [p*S/P, (p+1)*S/P)，两次生成分配一致（复现可对齐）。
 */
public final class BenchConfig {

    /** 档位表（phase 设计文档）：profile → S/I/P/k/Q/拓扑/时长与各进程 heap（MB）。 */
    private static final Map<String, int[]> PROFILES = new LinkedHashMap<>();

    static {
        // {S, I, P, k, Q, topologyNodes, rampSec, durationSec, regHeap, discHeap, drvHeap, subHeap}
        PROFILES.put("dev", new int[] {100, 10, 20, 1, 5, 1, 30, 120, 512, 512, 512, 512});
        PROFILES.put("small", new int[] {500, 20, 50, 2, 10, 1, 30, 600, 1536, 768, 1024, 512});
        PROFILES.put("medium", new int[] {1000, 50, 100, 3, 20, 1, 60, 1800, 3072, 1024, 1536, 768});
        PROFILES.put("large", new int[] {1000, 100, 200, 4, 20, 1, 60, 1800, 4096, 1536, 1536, 1024});
    }

    public final String role;
    public final String scenario;
    public final String profile;
    public final int services;
    public final int instancesPerService;
    public final int processes;
    public final int driverJvms;
    public final int subscribers;
    public final int topologyNodes;
    public final int shardIndex;
    public final int shardCount;
    public final int rampSec;
    public final int durationSec;
    public final int churnIntervalMs;
    public final String target;
    public final String discoveryTarget;
    public final String reportDir;
    public final int registryHeapMb;
    public final int discoveryHeapMb;
    public final int driverHeapMb;
    public final int subscriberHeapMb;

    private BenchConfig(Builder builder) {
        this.role = builder.role;
        this.scenario = builder.scenario;
        this.profile = builder.profile;
        this.services = builder.services;
        this.instancesPerService = builder.instancesPerService;
        this.processes = builder.processes;
        this.driverJvms = builder.driverJvms;
        this.subscribers = builder.subscribers;
        this.topologyNodes = builder.topologyNodes;
        this.shardIndex = builder.shardIndex;
        this.shardCount = builder.shardCount;
        this.rampSec = builder.rampSec;
        this.durationSec = builder.durationSec;
        this.churnIntervalMs = builder.churnIntervalMs;
        this.target = builder.target;
        this.discoveryTarget = builder.discoveryTarget;
        this.reportDir = builder.reportDir;
        this.registryHeapMb = builder.registryHeapMb;
        this.discoveryHeapMb = builder.discoveryHeapMb;
        this.driverHeapMb = builder.driverHeapMb;
        this.subscriberHeapMb = builder.subscriberHeapMb;
    }

    /** 总实例数。 */
    public int totalInstances() {
        return services * instancesPerService;
    }

    /** 本 driver JVM 承载的模拟进程号列表（shard 均摊：进程 i 归 shard i*k/P）。 */
    public List<Integer> shardProcessIds() {
        List<Integer> ids = new ArrayList<>();
        for (int p = 0; p < processes; p++) {
            if (p * shardCount / processes == shardIndex) {
                ids.add(p);
            }
        }
        return ids;
    }

    /** 进程 p 持有的服务索引区间下界（含）。 */
    private int serviceLowerBound(int processId) {
        return processId * services / processes;
    }

    /** 进程 p 持有的服务索引区间上界（不含）。 */
    private int serviceUpperBound(int processId) {
        return (processId + 1) * services / processes;
    }

    /** 进程 p 的服务集合（确定性派生：bench-svc-%04d）。 */
    public List<ServiceId> servicesOfProcess(int processId) {
        List<ServiceId> result = new ArrayList<>();
        for (int s = serviceLowerBound(processId); s < serviceUpperBound(processId); s++) {
            result.add(serviceIdOf(s));
        }
        return result;
    }

    /** 服务索引 → ServiceId（确定性派生：bench-svc-%04d）。 */
    public ServiceId serviceIdOf(int serviceIndex) {
        return new ServiceId.Builder()
                .namespace("bench")
                .service(String.format("bench-svc-%04d", serviceIndex))
                .build();
    }

    /** 进程 p 的全部实例（每服务 I 个，确定性派生：host 10.p.i / port 1024+i——port 无唯一性语义，恒界内）。 */
    public List<ServiceInstance> instancesOfProcess(int processId) {
        List<ServiceInstance> result = new ArrayList<>();
        for (int s = serviceLowerBound(processId); s < serviceUpperBound(processId); s++) {
            ServiceId service = serviceIdOf(s);
            for (int i = 0; i < instancesPerService; i++) {
                result.add(new ServiceInstance.Builder()
                        .service(service)
                        .instanceId(String.format("i%03d", i))
                        .host("10." + processId + "." + i)
                        .port(1024 + i)
                        .build());
            }
        }
        return result;
    }

    /** 持有服务 s 的进程号（与 instancesOfProcess 分配互逆；churn 路由用）。 */
    public int processOfServiceIndex(int serviceIndex) {
        return serviceIndex * processes / services;
    }

    /** 本 driver JVM 承载的服务索引全集（进程归属过滤；churn 均匀轮转用）。 */
    public List<Integer> shardServiceIndices() {
        List<Integer> indices = new ArrayList<>();
        for (int s = 0; s < services; s++) {
            if (shardProcessIds().contains(Integer.valueOf(processOfServiceIndex(s)))) {
                indices.add(s);
            }
        }
        return indices;
    }

    /** 实例 id 定位（churn update 目标）。 */
    public InstanceId instanceIdOf(int serviceIndex, int instanceIndex) {
        return new InstanceId.Builder()
                .service(serviceIdOf(serviceIndex))
                .instanceId(String.format("i%03d", instanceIndex))
                .build();
    }

    /** 内存预算（MB，资源模型公式输入）：registry ×R + discovery + driver ×k + subscriber。 */
    public int memoryBudgetMb() {
        int budget = topologyNodes * registryHeapMb + discoveryHeapMb + driverJvms * driverHeapMb;
        if (hasSubscriber()) {
            budget += subscriberHeapMb;
        }
        return budget;
    }

    /** 重 JVM 进程数（CPU 判据：建议 ≤ 核数）。 */
    public int heavyProcessCount() {
        return topologyNodes + 1 + driverJvms + (hasSubscriber() ? 1 : 0);
    }

    /** 场景是否拉起 subscriber driver（S1–S3 无、S4/combo 有）。 */
    public boolean hasSubscriber() {
        return "s4".equals(scenario) || "combo".equals(scenario);
    }

    /** 场景是否含变更注入（S3/S4/combo：churn）。 */
    public boolean hasChurn() {
        return "s3".equals(scenario) || "s4".equals(scenario) || "combo".equals(scenario);
    }

    /** 场景是否为注册风暴（S1：分批爬坡注册，其余场景 ramp 即常规负载建立）。 */
    public boolean isStorm() {
        return "s1".equals(scenario);
    }

    /** 场景时间预算（秒）。 */
    public int scenarioBudgetSec() {
        return rampSec + durationSec;
    }

    /** 档位/参数展开结果（--role plan 输出，bench.sh 预检与编排单一来源）。 */
    public Map<String, String> planEntries() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("profile", profile);
        entries.put("scenario", scenario);
        entries.put("services", String.valueOf(services));
        entries.put("instancesPerService", String.valueOf(instancesPerService));
        entries.put("totalInstances", String.valueOf(totalInstances()));
        entries.put("processes", String.valueOf(processes));
        entries.put("driverJvms", String.valueOf(driverJvms));
        entries.put("subscribers", String.valueOf(subscribers));
        entries.put("topologyNodes", String.valueOf(topologyNodes));
        entries.put("rampSec", String.valueOf(rampSec));
        entries.put("durationSec", String.valueOf(durationSec));
        entries.put("churnIntervalMs", String.valueOf(churnIntervalMs));
        entries.put("registryHeapMb", String.valueOf(registryHeapMb));
        entries.put("discoveryHeapMb", String.valueOf(discoveryHeapMb));
        entries.put("driverHeapMb", String.valueOf(driverHeapMb));
        entries.put("subscriberHeapMb", String.valueOf(subscriberHeapMb));
        entries.put("memoryBudgetMb", String.valueOf(memoryBudgetMb()));
        entries.put("memoryBudgetWithMarginMb", String.valueOf(memoryBudgetMb() * 6 / 5));
        entries.put("heavyProcessCount", String.valueOf(heavyProcessCount()));
        entries.put("scenarioBudgetSec", String.valueOf(scenarioBudgetSec()));
        entries.put("scenarioHardTimeoutSec", String.valueOf(scenarioBudgetSec() * 2));
        return entries;
    }

    /** 解析 CLI 参数：--key value 序列；profile 先展开、显式参数覆盖。 */
    public static BenchConfig parse(String[] args) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (!key.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + key);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("missing value for " + key);
            }
            raw.put(key.substring(2), args[++i]);
        }

        String role = require(raw, "role", "plan|registry|subscriber|join");
        if (!"plan".equals(role) && !"registry".equals(role) && !"subscriber".equals(role) && !"join".equals(role)) {
            throw new IllegalArgumentException("invalid role: " + role);
        }

        String profileName = raw.getOrDefault("profile", "small");
        int[] preset;
        int services;
        int instancesPerService;
        int processes;
        int driverJvms;
        int subscribers;
        int topologyNodes;
        int rampSec;
        int durationSec;
        int registryHeapMb;
        int discoveryHeapMb;
        int driverHeapMb;
        int subscriberHeapMb;
        if ("custom".equals(profileName)) {
            preset = null;
            // custom 只强制矩阵核心（S/I/P/k 缺失即拒），其余取静态默认（拓扑 1 节点、时长 30s/600s、heap 512m）
            services = 0;
            instancesPerService = 0;
            processes = 0;
            driverJvms = 0;
            subscribers = 0;
            topologyNodes = 1;
            rampSec = 30;
            durationSec = 600;
            registryHeapMb = 512;
            discoveryHeapMb = 512;
            driverHeapMb = 512;
            subscriberHeapMb = 512;
        } else {
            preset = PROFILES.get(profileName);
            if (preset == null) {
                throw new IllegalArgumentException(
                        "invalid profile: " + profileName + " (expected dev|small|medium|large|custom)");
            }
            services = preset[0];
            instancesPerService = preset[1];
            processes = preset[2];
            driverJvms = preset[3];
            subscribers = preset[4];
            topologyNodes = preset[5];
            rampSec = preset[6];
            durationSec = preset[7];
            registryHeapMb = preset[8];
            discoveryHeapMb = preset[9];
            driverHeapMb = preset[10];
            subscriberHeapMb = preset[11];
        }

        services = intArg(raw, "services", services);
        instancesPerService = intArg(raw, "instancesPerService", instancesPerService);
        processes = intArg(raw, "processes", processes);
        driverJvms = intArg(raw, "driverJvms", driverJvms);
        subscribers = intArg(raw, "subscribers", subscribers);
        topologyNodes = intArg(raw, "topologyNodes", topologyNodes);
        rampSec = intArg(raw, "rampSec", rampSec);
        durationSec = intArg(raw, "durationSec", durationSec);
        registryHeapMb = intArg(raw, "registryHeapMb", registryHeapMb);
        discoveryHeapMb = intArg(raw, "discoveryHeapMb", discoveryHeapMb);
        driverHeapMb = intArg(raw, "driverHeapMb", driverHeapMb);
        subscriberHeapMb = intArg(raw, "subscriberHeapMb", subscriberHeapMb);

        String scenario = raw.getOrDefault("scenario", "s1");
        if (!"s1".equals(scenario)
                && !"s2".equals(scenario)
                && !"s3".equals(scenario)
                && !"s4".equals(scenario)
                && !"combo".equals(scenario)) {
            throw new IllegalArgumentException("invalid scenario: " + scenario + " (expected s1|s2|s3|s4|combo)");
        }
        int shardIndex = intArg(raw, "shardIndex", 0);
        int shardCount = intArg(raw, "shardCount", 1);
        int churnIntervalMs = intArg(raw, "churnIntervalMs", 1000);
        String target = raw.getOrDefault("target", "");
        String discoveryTarget = raw.getOrDefault("discoveryTarget", "");
        String reportDir = raw.getOrDefault("reportDir", "bench-report");

        Builder builder = new Builder();
        builder.role = role;
        builder.scenario = scenario;
        builder.profile = profileName;
        builder.services = services;
        builder.instancesPerService = instancesPerService;
        builder.processes = processes;
        builder.driverJvms = driverJvms;
        builder.subscribers = subscribers;
        builder.topologyNodes = topologyNodes;
        builder.shardIndex = shardIndex;
        builder.shardCount = shardCount;
        builder.rampSec = rampSec;
        builder.durationSec = durationSec;
        builder.churnIntervalMs = churnIntervalMs;
        builder.target = target;
        builder.discoveryTarget = discoveryTarget;
        builder.reportDir = reportDir;
        builder.registryHeapMb = registryHeapMb;
        builder.discoveryHeapMb = discoveryHeapMb;
        builder.driverHeapMb = driverHeapMb;
        builder.subscriberHeapMb = subscriberHeapMb;
        BenchConfig config = builder.build();
        config.validate();
        return config;
    }

    private void validate() {
        requirePositive("services", services);
        requirePositive("instancesPerService", instancesPerService);
        requirePositive("processes", processes);
        requirePositive("driverJvms", driverJvms);
        if (subscribers < 0) {
            throw new IllegalArgumentException("subscribers must be >= 0");
        }
        if (topologyNodes != 1 && topologyNodes != 3) {
            throw new IllegalArgumentException("topologyNodes must be 1 or 3");
        }
        if (shardIndex < 0 || shardIndex >= shardCount) {
            throw new IllegalArgumentException("shardIndex out of range: " + shardIndex + "/" + shardCount);
        }
        if (shardCount > processes) {
            throw new IllegalArgumentException("shardCount " + shardCount + " > processes " + processes);
        }
        requirePositive("rampSec", rampSec);
        requirePositive("durationSec", durationSec);
        requirePositive("churnIntervalMs", churnIntervalMs);
        requirePositive("registryHeapMb", registryHeapMb);
        requirePositive("discoveryHeapMb", discoveryHeapMb);
        requirePositive("driverHeapMb", driverHeapMb);
        requirePositive("subscriberHeapMb", subscriberHeapMb);
        if ("registry".equals(role) && target.isEmpty()) {
            throw new IllegalArgumentException("registry role requires --target (registry HTTP addresses)");
        }
        if ("subscriber".equals(role) && discoveryTarget.isEmpty()) {
            throw new IllegalArgumentException("subscriber role requires --discoveryTarget (discovery HTTP url)");
        }
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
    }

    private static String require(Map<String, String> raw, String key, String expected) {
        String value = raw.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing required --" + key + " (" + expected + ")");
        }
        return value;
    }

    private static int intArg(Map<String, String> raw, String key, int defaultValue) {
        String value = raw.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid int for --" + key + ": " + value);
        }
    }

    /** 测试与 plan 展开用构造器。 */
    static final class Builder {

        String role = "plan";
        String scenario = "s1";
        String profile = "small";
        int services;
        int instancesPerService;
        int processes;
        int driverJvms;
        int subscribers;
        int topologyNodes = 1;
        int shardIndex;
        int shardCount = 1;
        int rampSec = 30;
        int durationSec = 600;
        int churnIntervalMs = 1000;
        String target = "";
        String discoveryTarget = "";
        String reportDir = "bench-report";
        int registryHeapMb = 512;
        int discoveryHeapMb = 512;
        int driverHeapMb = 512;
        int subscriberHeapMb = 512;

        BenchConfig build() {
            return new BenchConfig(this);
        }
    }
}
