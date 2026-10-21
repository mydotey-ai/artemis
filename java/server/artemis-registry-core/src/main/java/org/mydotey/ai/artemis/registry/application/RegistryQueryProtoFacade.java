package org.mydotey.ai.artemis.registry.application;

import org.mydotey.ai.artemis.proto.management.v1.GetConfigStatusRequest;
import org.mydotey.ai.artemis.proto.management.v1.GetConfigStatusResponse;
import org.mydotey.ai.artemis.proto.management.v1.GetLeaseStatusRequest;
import org.mydotey.ai.artemis.proto.management.v1.GetLeaseStatusResponse;
import org.mydotey.ai.artemis.proto.management.v1.GetSelfProtectionStatusRequest;
import org.mydotey.ai.artemis.proto.management.v1.GetSelfProtectionStatusResponse;
import org.mydotey.ai.artemis.proto.management.v1.GetServiceInstancesRequest;
import org.mydotey.ai.artemis.proto.management.v1.GetServiceInstancesResponse;
import org.mydotey.ai.artemis.proto.management.v1.GetServiceInstancesResponseData;
import org.mydotey.ai.artemis.proto.management.v1.ListServicesRequest;
import org.mydotey.ai.artemis.proto.management.v1.ListServicesResponse;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;

/**
 * 内部只读查询的 proto 门面（dto 进出）：契约级校验 + 委托应用服务 + 响应映射。
 * 契约校验在信任边界一次完成；查询不存在资源返回空集合 + SUCCESS。
 */
public final class RegistryQueryProtoFacade {

    private final RegistryQueryService service;
    private final RegistryProtoMapper mapper = RegistryProtoMapper.INSTANCE;

    public RegistryQueryProtoFacade(RegistryQueryService service) {
        this.service = service;
    }

    public ListServicesResponse listServices(ListServicesRequest request) {
        return mapper.toListServicesResponse(service.listServices(request.getNamespace()));
    }

    public GetServiceInstancesResponse getServiceInstances(GetServiceInstancesRequest request) {
        ServiceKey key = mapper.toDomain(request.getKey());
        if (key == null || isBlank(key.namespace()) || isBlank(key.service())) {
            return GetServiceInstancesResponse.newBuilder()
                    .setStatus(mapper.invalidArgumentStatus("blank namespace/service"))
                    .setData(GetServiceInstancesResponseData.getDefaultInstance())
                    .build();
        }
        return mapper.toGetServiceInstancesResponse(service.getInstances(key).orElse(null));
    }

    public GetLeaseStatusResponse getLeaseStatus(GetLeaseStatusRequest request) {
        return mapper.toGetLeaseStatusResponse(service.leaseStatus(request.getNamespace(), request.getService()));
    }

    public GetConfigStatusResponse getConfigStatus(GetConfigStatusRequest request) {
        return mapper.toGetConfigStatusResponse(service.configStatus());
    }

    public GetSelfProtectionStatusResponse getSelfProtectionStatus(GetSelfProtectionStatusRequest request) {
        return mapper.toGetSelfProtectionStatusResponse(
                service.selfProtectionStatus(request.getNamespace(), request.getService(), request.getIncludeNormal()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
