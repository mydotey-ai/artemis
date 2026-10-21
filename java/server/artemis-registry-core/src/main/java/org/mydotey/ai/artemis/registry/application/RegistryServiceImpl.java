package org.mydotey.ai.artemis.registry.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.common.config.ScfProperties;
import org.mydotey.ai.artemis.registry.domain.Ack;
import org.mydotey.ai.artemis.registry.domain.DeregisterRequest;
import org.mydotey.ai.artemis.registry.domain.DeregisterResponse;
import org.mydotey.ai.artemis.registry.domain.DeregisterResponseData;
import org.mydotey.ai.artemis.registry.domain.ErrorInfo;
import org.mydotey.ai.artemis.registry.domain.HeartbeatRequest;
import org.mydotey.ai.artemis.registry.domain.HeartbeatResponse;
import org.mydotey.ai.artemis.registry.domain.HeartbeatResponseData;
import org.mydotey.ai.artemis.registry.domain.Instance;
import org.mydotey.ai.artemis.registry.domain.InstanceError;
import org.mydotey.ai.artemis.registry.domain.InstanceKey;
import org.mydotey.ai.artemis.registry.domain.InstanceRegistry;
import org.mydotey.ai.artemis.registry.domain.RegisterRequest;
import org.mydotey.ai.artemis.registry.domain.RegisterResponse;
import org.mydotey.ai.artemis.registry.domain.RegisterResponseData;
import org.mydotey.ai.artemis.registry.domain.RegistrySnapshotStore;
import org.mydotey.ai.artemis.registry.domain.ResponseStatus;
import org.mydotey.ai.artemis.registry.domain.SelfProtection;
import org.mydotey.ai.artemis.registry.domain.ServiceInstanceErrors;
import org.mydotey.ai.artemis.registry.domain.ServiceInstances;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;
import org.mydotey.ai.artemis.registry.domain.UpdateRequest;
import org.mydotey.ai.artemis.registry.domain.UpdateResponse;
import org.mydotey.ai.artemis.registry.domain.UpdateResponseData;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;

/**
 * 应用服务实现：编排 = 契约级校验/归一（一次完成，领域不重复防御）→ 聚合 → 组装领域响应。
 * 批量贯穿部分失败语义：任一明细失败 → ack=FAIL，成功项照常生效，明细仅携带失败项。
 *
 * <p>phase-3 增量编排：心跳续约计数（D1，请求数聚合后单次累加，显式操作不计——非续约信号）、
 * 保护态跳过过期清理、快照落盘/回放（D3/D6/D10，快照只是减少重注册风暴而非零丢失）。
 */
@Slf4j
public final class RegistryServiceImpl implements RegistryService {

    /** 契约明示：weight ≤ 0 等同未设置，入口归一为 100。 */
    private static final int DEFAULT_WEIGHT = 100;

    static final String KEY_SNAPSHOT_MAX_AGE_MS = "artemis.registry.snapshot.maxAgeMs";
    static final String KEY_SNAPSHOT_CLOCK_SKEW_TOLERANCE_MS = "artemis.registry.snapshot.clockSkewToleranceMs";

    private static final long DEFAULT_SNAPSHOT_MAX_AGE_MS = 3_600_000;
    private static final long DEFAULT_CLOCK_SKEW_TOLERANCE_MS = 60_000;

    private final InstanceRegistry registry;
    private final Clock clock;
    private final SelfProtection protection;
    private final RegistrySnapshotStore snapshotStore;
    private final Property<String, Long> snapshotMaxAgeMs;
    private final Property<String, Long> clockSkewToleranceMs;
    private final RegistrySnapshotCodec snapshotCodec = new RegistrySnapshotCodec();
    private final Counter evictions;
    private final Counter heartbeatInstances;

    /**
     * 注册表聚合与 TTL 配置由装配层构造注入（InstanceRegistry 类内定义动态 TTL property）；
     * 回放时效为静态项（启动取值一次）。
     */
    public RegistryServiceImpl(
            InstanceRegistry registry,
            StringProperties properties,
            Clock clock,
            SelfProtection protection,
            RegistrySnapshotStore snapshotStore,
            MeterRegistry meterRegistry) {
        this.registry = registry;
        this.clock = clock;
        this.protection = protection;
        this.snapshotStore = snapshotStore;
        this.snapshotMaxAgeMs = ScfProperties.staticLong(
                properties,
                KEY_SNAPSHOT_MAX_AGE_MS,
                DEFAULT_SNAPSHOT_MAX_AGE_MS,
                60_000L,
                86_400_000L,
                "maximum snapshot age accepted at restore in milliseconds (static)");
        this.clockSkewToleranceMs = ScfProperties.staticLong(
                properties,
                KEY_SNAPSHOT_CLOCK_SKEW_TOLERANCE_MS,
                DEFAULT_CLOCK_SKEW_TOLERANCE_MS,
                0L,
                300_000L,
                "allowed wall-clock forward skew for snapshot timestamps in milliseconds (static)");
        // 指标就地定义：本类负责的心跳/清理计数器 + 注册表实例数 gauge
        this.evictions = Counter.builder("artemis.registry.evictions")
                .description("total instances evicted by lease expiry")
                .register(meterRegistry);
        this.heartbeatInstances = Counter.builder("artemis.registry.heartbeat.instances")
                .description("total instance renewals reported by heartbeats")
                .register(meterRegistry);
        Gauge.builder("artemis.registry.instances", registry, InstanceRegistry::instanceCount)
                .description("current registered instance count")
                .register(meterRegistry);
    }

    @Override
    public RegisterResponse register(RegisterRequest request) {
        ValidatedServices validated = validateServices(request.services());
        validated.groups().forEach(group -> registry.register(group.key(), group.instances()));
        return new RegisterResponse(validated.status(), new RegisterResponseData(validated.serviceErrors()));
    }

    @Override
    public DeregisterResponse deregister(DeregisterRequest request) {
        List<InstanceError> errors = new ArrayList<>();
        Map<ServiceKey, List<String>> targets = new LinkedHashMap<>();
        ErrorInfo keyError = null;
        for (InstanceKey instanceKey : request.instances()) {
            ServiceKey service = instanceKey == null ? null : instanceKey.service();
            if (isInvalidKey(service)) {
                keyError = invalidArgument("blank namespace/service");
            } else if (isBlank(instanceKey.instanceId())) {
                errors.add(new InstanceError(
                        new InstanceKey(service, instanceKey.instanceId()), invalidArgument("blank instanceId")));
            } else {
                targets.computeIfAbsent(service, key -> new ArrayList<>()).add(instanceKey.instanceId());
            }
        }
        targets.forEach(registry::deregister);
        return new DeregisterResponse(statusOf(keyError, errors.isEmpty()), new DeregisterResponseData(errors));
    }

    @Override
    public UpdateResponse update(UpdateRequest request) {
        ValidatedServices validated = validateServices(request.services());
        validated.groups().forEach(group -> registry.update(group.key(), group.instances()));
        return new UpdateResponse(validated.status(), new UpdateResponseData(validated.serviceErrors()));
    }

    @Override
    public HeartbeatResponse heartbeat(HeartbeatRequest request) {
        ValidatedServices validated = validateServices(request.services());
        int renewals = validated.groups().stream()
                .mapToInt(group -> group.instances().size())
                .sum();
        protection.recordRenewals(renewals); // 有效心跳实例数聚合后单次累加（D1：仅心跳计入续约统计）
        heartbeatInstances.increment(renewals);
        validated.groups().forEach(group -> registry.heartbeatUpsert(group.key(), group.instances()));
        return new HeartbeatResponse(validated.status(), new HeartbeatResponseData(clock.millis(), request.seq()));
    }

    @Override
    public Optional<ServiceInstances> getInstances(ServiceKey key) {
        return registry.snapshot(key);
    }

    @Override
    public int evictExpired() {
        if (protection.isProtected()) {
            return 0; // 保护态整轮跳过（与原产品一致）；显式 deregister 不经此路径、天然不受保护
        }
        int evicted = registry.evictExpired();
        evictions.increment(evicted);
        return evicted;
    }

    @Override
    public void writeSnapshot() {
        snapshotStore.write(snapshotCodec.encode(registry.snapshots(), clock.millis()));
    }

    @Override
    public int restoreFromSnapshot() {
        Optional<byte[]> bytes = snapshotStore.read();
        if (bytes.isEmpty()) {
            log.warn("no registry snapshot found, starting with empty registry");
            return 0;
        }
        RegistrySnapshotCodec.DecodedSnapshot snapshot;
        try {
            snapshot = snapshotCodec.decode(bytes.get());
        } catch (IllegalArgumentException e) {
            log.warn("corrupt registry snapshot discarded, starting with empty registry: {}", e.getMessage());
            return 0;
        }
        long maxAgeMs = snapshotMaxAgeMs.getValue(); // 静态 Property：SCF 保证启动后不变
        long maxClockSkewMs = clockSkewToleranceMs.getValue();
        long ageMs = clock.millis() - snapshot.timestampMs();
        // 双向时效：超龄或时间戳超前超过墙钟回拨宽容（损坏/快进时钟）均视为无效
        if (ageMs > maxAgeMs || ageMs < -maxClockSkewMs) {
            log.warn(
                    "registry snapshot timestamp outside validity (age {}ms, max {}ms, future tolerance {}ms),"
                            + " starting with empty registry",
                    ageMs,
                    maxAgeMs,
                    maxClockSkewMs);
            return 0;
        }
        // 回放走与 register 相同的校验/归一门：可解析但语义损坏的条目不得直入注册表
        List<ServiceInstances> services = validatedServices(snapshot.services(), "registry snapshot");
        registry.restore(services);
        int restored = instanceCount(services);
        log.info("restored {} instances from registry snapshot (timestamp {})", restored, snapshot.timestampMs());
        return restored;
    }

    @Override
    public int applyBootstrap(List<ServiceInstances> services) {
        List<ServiceInstances> valid = validatedServices(services, "peer bootstrap");
        registry.replaceAll(valid);
        int applied = instanceCount(valid);
        log.info("applied {} instances from peer bootstrap", applied);
        return applied;
    }

    /** 校验/归一 + 丢弃非法条目（回放与 peer bootstrap 共用单一口径）。 */
    private List<ServiceInstances> validatedServices(List<ServiceInstances> services, String source) {
        ValidatedServices validated = validateServices(services);
        if (validated.status().ack() != Ack.SUCCESS) {
            log.warn("{} contained invalid entries, dropped during apply", source);
        }
        return validated.groups().stream()
                .map(group -> new ServiceInstances(group.key(), group.instances()))
                .toList();
    }

    private static int instanceCount(List<ServiceInstances> services) {
        return services.stream().mapToInt(service -> service.instances().size()).sum();
    }

    // ---- 校验与归一（§8：应用层一次完成）----

    private ValidatedServices validateServices(List<ServiceInstances> services) {
        List<ServiceGroup> groups = new ArrayList<>();
        List<ServiceInstanceErrors> serviceErrors = new ArrayList<>();
        ErrorInfo keyError = null;
        for (ServiceInstances service : services) {
            ServiceKey key = service.key();
            if (isInvalidKey(key)) {
                // 分组整体拒绝不写入；结构上无法表达实例级明细，代表性错误进 status
                keyError = invalidArgument("blank namespace/service");
                continue;
            }
            List<Instance> valid = new ArrayList<>(service.instances().size());
            List<InstanceError> failed = new ArrayList<>();
            for (Instance instance : service.instances()) {
                if (isBlank(instance.instanceId())) {
                    failed.add(new InstanceError(
                            new InstanceKey(key, instance.instanceId()), invalidArgument("blank instanceId")));
                } else {
                    valid.add(normalize(instance));
                }
            }
            if (!failed.isEmpty()) {
                serviceErrors.add(new ServiceInstanceErrors(key, failed));
            }
            if (!valid.isEmpty()) {
                groups.add(new ServiceGroup(key, valid));
            }
        }
        return new ValidatedServices(groups, serviceErrors, statusOf(keyError, serviceErrors.isEmpty()));
    }

    /** 归一（不算失败）：weight ≤ 0 → 100；status 保留原值（UNSPECIFIED 同 UP，消费侧解释）。 */
    private static Instance normalize(Instance instance) {
        return instance.weight() <= 0
                ? new Instance(
                        instance.instanceId(),
                        instance.host(),
                        instance.port(),
                        instance.status(),
                        DEFAULT_WEIGHT,
                        instance.metadata())
                : instance;
    }

    private static boolean isInvalidKey(ServiceKey key) {
        return key == null || isBlank(key.namespace()) || isBlank(key.service());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ErrorInfo invalidArgument(String message) {
        return new ErrorInfo(ErrorCodes.INVALID_ARGUMENT, message);
    }

    private static ResponseStatus statusOf(ErrorInfo keyError, boolean noDetailErrors) {
        if (keyError == null && noDetailErrors) {
            return new ResponseStatus(Ack.SUCCESS, null);
        }
        return new ResponseStatus(Ack.FAIL, keyError != null ? keyError : invalidArgument("invalid instances"));
    }

    private record ServiceGroup(ServiceKey key, List<Instance> instances) {}

    private record ValidatedServices(
            List<ServiceGroup> groups, List<ServiceInstanceErrors> serviceErrors, ResponseStatus status) {}
}
