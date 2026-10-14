package org.mydotey.ai.artemis.registry.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.mydotey.ai.artemis.registry.domain.ResponseStatus;
import org.mydotey.ai.artemis.registry.domain.ServiceInstanceErrors;
import org.mydotey.ai.artemis.registry.domain.ServiceInstances;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;
import org.mydotey.ai.artemis.registry.domain.UpdateRequest;
import org.mydotey.ai.artemis.registry.domain.UpdateResponse;
import org.mydotey.ai.artemis.registry.domain.UpdateResponseData;

/**
 * 应用服务实现：编排 = 契约级校验/归一（一次完成，领域不重复防御）→ 聚合 → 组装领域响应。
 * 批量贯穿部分失败语义：任一明细失败 → ack=FAIL，成功项照常生效，明细仅携带失败项。
 */
public final class RegistryServiceImpl implements RegistryService {

    /** 契约明示：weight ≤ 0 等同未设置，入口归一为 100。 */
    private static final int DEFAULT_WEIGHT = 100;

    private final InstanceRegistry registry;
    private final Clock clock;

    public RegistryServiceImpl(
            LeaseTtlSource leaseTtl, Clock clock, MonotonicClock monotonicClock, ChangeListener listener) {
        this.registry = new InstanceRegistry(leaseTtl, monotonicClock, listener);
        this.clock = clock;
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
        validated.groups().forEach(group -> registry.heartbeatUpsert(group.key(), group.instances()));
        return new HeartbeatResponse(validated.status(), new HeartbeatResponseData(clock.millis(), request.seq()));
    }

    @Override
    public Optional<ServiceInstances> getInstances(ServiceKey key) {
        return registry.snapshot(key);
    }

    @Override
    public int evictExpired() {
        return registry.evictExpired();
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
