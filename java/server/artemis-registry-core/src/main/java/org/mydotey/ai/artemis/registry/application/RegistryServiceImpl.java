package org.mydotey.ai.artemis.registry.application;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.registry.domain.Ack;
import org.mydotey.ai.artemis.registry.domain.ChangeListener;
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
import org.mydotey.ai.artemis.registry.domain.LeaseTtlSource;
import org.mydotey.ai.artemis.registry.domain.MonotonicClock;
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

    /** 时间戳超前 now 的宽容上限（D10 墙钟回拨）：超出视为损坏/异常时钟写入，按超期处置。 */
    private static final long MAX_CLOCK_SKEW_MS = Duration.ofMinutes(1).toMillis();

    private final InstanceRegistry registry;
    private final Clock clock;
    private final SelfProtection protection;
    private final RegistrySnapshotStore snapshotStore;
    private final Duration snapshotMaxAge;
    private final RegistrySnapshotCodec snapshotCodec = new RegistrySnapshotCodec();

    public RegistryServiceImpl(
            LeaseTtlSource leaseTtl,
            Clock clock,
            MonotonicClock monotonicClock,
            ChangeListener listener,
            SelfProtection protection,
            RegistrySnapshotStore snapshotStore,
            Duration snapshotMaxAge) {
        this.registry = new InstanceRegistry(leaseTtl, monotonicClock, listener);
        this.clock = clock;
        this.protection = protection;
        this.snapshotStore = snapshotStore;
        this.snapshotMaxAge = snapshotMaxAge;
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
        return registry.evictExpired();
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
        long ageMs = clock.millis() - snapshot.timestampMs();
        // 双向时效：超龄或时间戳超前超过墙钟回拨宽容（损坏/快进时钟）均视为无效
        if (ageMs > snapshotMaxAge.toMillis() || ageMs < -MAX_CLOCK_SKEW_MS) {
            log.warn(
                    "registry snapshot timestamp outside validity (age {}ms, max {}ms, future tolerance {}ms),"
                            + " starting with empty registry",
                    ageMs,
                    snapshotMaxAge.toMillis(),
                    MAX_CLOCK_SKEW_MS);
            return 0;
        }
        // 回放走与 register 相同的校验/归一门：可解析但语义损坏的条目不得直入注册表
        ValidatedServices validated = validateServices(snapshot.services());
        if (validated.status().ack() != Ack.SUCCESS) {
            log.warn("registry snapshot contained invalid entries, dropped during restore");
        }
        List<ServiceInstances> services = validated.groups().stream()
                .map(group -> new ServiceInstances(group.key(), group.instances()))
                .toList();
        registry.restore(services);
        int restored = services.stream()
                .mapToInt(service -> service.instances().size())
                .sum();
        log.info("restored {} instances from registry snapshot (timestamp {})", restored, snapshot.timestampMs());
        return restored;
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
