package org.mydotey.ai.artemis.management.server.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.mydotey.ai.artemis.common.auth.BasicAuth;
import org.mydotey.ai.artemis.management.application.ManagementProtoMapper;
import org.mydotey.ai.artemis.management.domain.ConfigProperty;
import org.mydotey.ai.artemis.management.domain.Instance;
import org.mydotey.ai.artemis.management.domain.LeaseStatus;
import org.mydotey.ai.artemis.management.domain.RegistryQueryException;
import org.mydotey.ai.artemis.management.domain.RegistryQueryPort;
import org.mydotey.ai.artemis.management.domain.ServiceKey;
import org.mydotey.ai.artemis.management.domain.ServiceSummary;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.ResponseStatus;
import org.mydotey.ai.artemis.proto.management.v1.GetConfigStatusRequest;
import org.mydotey.ai.artemis.proto.management.v1.GetLeaseStatusRequest;
import org.mydotey.ai.artemis.proto.management.v1.GetServiceInstancesRequest;
import org.mydotey.ai.artemis.proto.management.v1.ListServicesRequest;
import org.mydotey.ai.artemis.proto.management.v1.RegistryQueryServiceGrpc;

/**
 * registry 只读查询 gRPC 客户端（ADR-016：v0.1 复用 registry 外部 gRPC 端口，Basic 认证）。
 * 传输层只做调用与失败翻译；proto→domain 映射在 application 的 mapper。
 */
public final class RegistryQueryGrpcClient implements RegistryQueryPort, AutoCloseable {

    /** 管理查询响应无分页（phase-5 契约），放宽客户端入站上限以覆盖 10 万实例级全量响应。 */
    private static final int MAX_INBOUND_MESSAGE_BYTES = 16 * 1024 * 1024;

    private final ManagedChannel channel;
    private final RegistryQueryServiceGrpc.RegistryQueryServiceBlockingStub stub;
    private final long timeoutMs;
    private final ManagementProtoMapper mapper = ManagementProtoMapper.INSTANCE;

    public RegistryQueryGrpcClient(RegistryClientConfig config) {
        this.channel = ManagedChannelBuilder.forTarget(config.endpoint())
                .usePlaintext()
                .maxInboundMessageSize(MAX_INBOUND_MESSAGE_BYTES)
                .build();
        Metadata headers = new Metadata();
        headers.put(
                Metadata.Key.of(BasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER),
                BasicAuth.headerValue(config.username(), config.password()));
        this.stub = RegistryQueryServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
        this.timeoutMs = config.timeoutMs();
    }

    /** deadline 必须每次调用现算（`withDeadlineAfter` 在调用时固化绝对时刻，不可复用）。 */
    private RegistryQueryServiceGrpc.RegistryQueryServiceBlockingStub stub() {
        return stub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public List<ServiceSummary> listServices(String namespace) {
        var response = call(() -> stub().listServices(ListServicesRequest.newBuilder()
                .setNamespace(nullToEmpty(namespace))
                .build()));
        ensureSuccess(response.getStatus(), "ListServices");
        return response.getData().getServicesList().stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public List<Instance> getInstances(ServiceKey key) {
        var response = call(() -> stub().getServiceInstances(GetServiceInstancesRequest.newBuilder()
                .setKey(mapper.toProto(key))
                .build()));
        ensureSuccess(response.getStatus(), "GetServiceInstances");
        return response.getData().getInstancesList().stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public LeaseStatus leaseStatus(String namespace, String service) {
        var response = call(() -> stub().getLeaseStatus(GetLeaseStatusRequest.newBuilder()
                .setNamespace(nullToEmpty(namespace))
                .setService(nullToEmpty(service))
                .build()));
        ensureSuccess(response.getStatus(), "GetLeaseStatus");
        return mapper.toDomain(response.getData());
    }

    @Override
    public List<ConfigProperty> configStatus() {
        var response = call(() -> stub().getConfigStatus(GetConfigStatusRequest.getDefaultInstance()));
        ensureSuccess(response.getStatus(), "GetConfigStatus");
        return response.getData().getPropertiesList().stream()
                .map(mapper::toDomain)
                .toList();
    }

    /** Spring 上下文关闭时释放 channel。 */
    @Override
    public void close() {
        channel.shutdownNow();
    }

    private static <T> T call(Supplier<T> call) {
        try {
            return call.get();
        } catch (StatusRuntimeException e) {
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED) {
                throw new RegistryQueryException(
                        RegistryQueryException.REGISTRY_UNAVAILABLE, "registry unavailable: " + e.getStatus(), e);
            }
            throw new RegistryQueryException(
                    RegistryQueryException.REGISTRY_ERROR, "registry query failed: " + e.getStatus(), e);
        }
    }

    private static void ensureSuccess(ResponseStatus status, String operation) {
        if (status.getAck() != Ack.ACK_SUCCESS) {
            String detail = status.hasError()
                    ? status.getError().getCode() + ": " + status.getError().getMessage()
                    : "";
            throw new RegistryQueryException(
                    RegistryQueryException.REGISTRY_ERROR, operation + " failed on registry, " + detail);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
