package org.mydotey.ai.artemis.registry.application;

/**
 * proto 门面（dto 进出）：mapper 转换 + 委派应用服务；server 接入层的 @GrpcService 一行委派于此。
 */
public final class RegistryProtoFacade {

    private final RegistryService service;
    private final RegistryProtoMapper mapper = RegistryProtoMapper.INSTANCE;

    public RegistryProtoFacade(RegistryService service) {
        this.service = service;
    }

    public org.mydotey.ai.artemis.proto.registry.v1.RegisterResponse register(
            org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest request) {
        return mapper.toProto(service.register(mapper.toDomain(request)));
    }

    public org.mydotey.ai.artemis.proto.registry.v1.DeregisterResponse deregister(
            org.mydotey.ai.artemis.proto.registry.v1.DeregisterRequest request) {
        return mapper.toProto(service.deregister(mapper.toDomain(request)));
    }

    public org.mydotey.ai.artemis.proto.registry.v1.UpdateResponse update(
            org.mydotey.ai.artemis.proto.registry.v1.UpdateRequest request) {
        return mapper.toProto(service.update(mapper.toDomain(request)));
    }

    public org.mydotey.ai.artemis.proto.registry.v1.HeartbeatResponse heartbeat(
            org.mydotey.ai.artemis.proto.registry.v1.HeartbeatRequest request) {
        return mapper.toProto(service.heartbeat(mapper.toDomain(request)));
    }
}
