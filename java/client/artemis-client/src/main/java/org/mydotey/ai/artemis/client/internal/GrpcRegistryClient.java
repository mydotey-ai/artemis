package org.mydotey.ai.artemis.client.internal;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.mydotey.ai.artemis.client.InstanceId;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.ErrorInfo;
import org.mydotey.ai.artemis.proto.common.v1.InstanceError;
import org.mydotey.ai.artemis.proto.common.v1.ResponseStatus;
import org.mydotey.ai.artemis.proto.registry.v1.DeregisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.DeregisterResponse;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatRequest;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatResponse;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterResponse;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.mydotey.ai.artemis.proto.registry.v1.UpdateRequest;
import org.mydotey.ai.artemis.proto.registry.v1.UpdateResponse;
import org.mydotey.scf.Property;
import org.mydotey.scf.facade.StringProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC 传输：channel 管理（选址/换址）+ Basic 认证 + unary 显式操作与心跳流 stub。
 * 地址与凭证为 SCF 配置且**使用时重读**（重连换址、凭证变更运行时生效）；创建期校验必配项 fail-fast。
 * unary 带 5s deadline、失败不抛异常：返回 {@link ReportOutcome}——瞬态传输失败（UNAVAILABLE/
 * DEADLINE_EXCEEDED）为 RETRIABLE，由调用方按配置有界重试；业务拒绝（ACK_FAIL）与非瞬态失败
 * 为 REJECTED（重试无意义）。每次尝试的失败细节 DEBUG（重试期间不刷 WARN），结论性 WARN 归编排层。
 */
final class GrpcRegistryClient {

    /** 上报结果三态：成功 / 瞬态可重试 / 终态拒绝。 */
    enum ReportOutcome {
        REPORTED,
        RETRIABLE,
        REJECTED
    }

    private static final Logger LOG = LoggerFactory.getLogger(GrpcRegistryClient.class);

    static final String KEY_AUTH_USERNAME = "artemis.client.auth.username";
    static final String KEY_AUTH_PASSWORD = "artemis.client.auth.password";

    private static final long DEADLINE_MS = 5_000;

    private final ServerAddressProvider addressProvider;
    private final ClientInterceptor authInterceptor;

    private volatile ManagedChannel channel;
    private volatile boolean closed;

    GrpcRegistryClient(StringProperties properties) {
        // 创建期校验（构造即触发）：必配项缺失/非法 fail-fast；select 重读配置（无静态固化）
        this.addressProvider = new ConfigAddressProvider(ConfigAddressProvider.property(properties));
        addressProvider.select();
        Property<String, String> username = ClientProperties.stringProperty(
                properties, KEY_AUTH_USERNAME, null, "auth username (both set = Basic auth, absent = no header)");
        Property<String, String> password = ClientProperties.stringProperty(
                properties, KEY_AUTH_PASSWORD, null, "auth password (both set = Basic auth, absent = no header)");
        // 凭证默认空：未配置不发认证头，认证裁决归服务端（显式配置方可认证，与服务端对称）；
        // 每次请求重读（变更运行时生效）；半配置（只配一项）几乎必是失误，WARN 提示
        if (isNonBlank(username.getValue()) != isNonBlank(password.getValue())) {
            LOG.warn("artemis.client.auth.username/password must be set together; authentication header not sent");
        }
        this.authInterceptor = authInterceptor(username, password);
    }

    private static ClientInterceptor authInterceptor(
            final Property<String, String> username, final Property<String, String> password) {
        return new ClientInterceptor() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
                return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, options)) {
                    @Override
                    public void start(Listener<RespT> listener, Metadata headers) {
                        String usernameValue = username.getValue();
                        String passwordValue = password.getValue();
                        if (isNonBlank(usernameValue) && isNonBlank(passwordValue)) {
                            headers.put(
                                    Metadata.Key.of(ClientBasicAuth.HEADER, Metadata.ASCII_STRING_MARSHALLER),
                                    ClientBasicAuth.headerValue(usernameValue, passwordValue));
                        }
                        super.start(listener, headers);
                    }
                };
            }
        };
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** 当前 channel（无则按选址建立；认证 interceptor 挂 channel 级，全部 RPC 覆盖）。close 后不再建立。 */
    ManagedChannel channel() {
        ManagedChannel current = channel;
        if (current == null || current.isShutdown()) {
            synchronized (this) {
                if (closed) {
                    throw new IllegalStateException("registry client closed");
                }
                current = channel;
                if (current == null || current.isShutdown()) {
                    // keepAlive：半开连接（NAT 静默丢包）由 TCP keepalive 探测兜底；
                    // ack 看门狗（HeartbeatChannel）是应用层的第一道检测
                    current = ManagedChannelBuilder.forTarget(addressProvider.select())
                            .usePlaintext()
                            .keepAliveTime(30, TimeUnit.SECONDS)
                            .keepAliveTimeout(10, TimeUnit.SECONDS)
                            .keepAliveWithoutCalls(true)
                            .intercept(authInterceptor)
                            .build();
                    channel = current;
                }
            }
        }
        return current;
    }

    /** 换址重建 channel（断流重连路径，调度线程调用）：异步 shutdown 不等待，不阻塞心跳调度。 */
    void resetChannel() {
        ManagedChannel old;
        synchronized (this) {
            old = channel;
            channel = null;
        }
        if (old != null) {
            old.shutdown();
        }
    }

    ReportOutcome reportRegister(ServiceId service, ServiceInstance instance) {
        RegisterRequest request = ProtoMapper.registerRequest(service, instance);
        try {
            RegisterResponse response = RegistryServiceGrpc.newBlockingStub(channel())
                    .withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
                    .register(request);
            return checkAck("register", instance, response.getStatus());
        } catch (StatusRuntimeException e) {
            return transportOutcome("register", instance, e);
        } catch (RuntimeException e) {
            LOG.debug("register report failed for {}", instance, e);
            return ReportOutcome.REJECTED;
        }
    }

    ReportOutcome reportUpdate(ServiceId service, ServiceInstance instance) {
        UpdateRequest request = ProtoMapper.updateRequest(service, instance);
        try {
            UpdateResponse response = RegistryServiceGrpc.newBlockingStub(channel())
                    .withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
                    .update(request);
            return checkAck("update", instance, response.getStatus());
        } catch (StatusRuntimeException e) {
            return transportOutcome("update", instance, e);
        } catch (RuntimeException e) {
            LOG.debug("update report failed for {}", instance, e);
            return ReportOutcome.REJECTED;
        }
    }

    ReportOutcome reportDeregister(List<InstanceId> instances) {
        DeregisterRequest request = ProtoMapper.deregisterRequest(instances);
        try {
            DeregisterResponse response = RegistryServiceGrpc.newBlockingStub(channel())
                    .withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
                    .deregister(request);
            if (response.getStatus().getAck() == Ack.ACK_FAIL) {
                List<String> messages = collectDeregisterMessages(response);
                LOG.debug(
                        "deregister report rejected for {}: {}",
                        instances,
                        messages.isEmpty() ? "no details" : messages);
                return ReportOutcome.REJECTED;
            }
            return ReportOutcome.REPORTED;
        } catch (StatusRuntimeException e) {
            return transportOutcome("deregister", instances, e);
        } catch (RuntimeException e) {
            LOG.debug("deregister report failed for {}", instances, e);
            return ReportOutcome.REJECTED;
        }
    }

    /** 瞬态传输失败（UNAVAILABLE/DEADLINE_EXCEEDED）可重试；其余（UNAUTHENTICATED 等配置性失败）终态。 */
    private static ReportOutcome transportOutcome(String operation, Object target, StatusRuntimeException e) {
        io.grpc.Status.Code code = e.getStatus().getCode();
        LOG.debug(
                "{} report failed for {} ({}: {})",
                operation,
                target,
                code,
                e.getStatus().getDescription());
        return code == io.grpc.Status.Code.UNAVAILABLE || code == io.grpc.Status.Code.DEADLINE_EXCEEDED
                ? ReportOutcome.RETRIABLE
                : ReportOutcome.REJECTED;
    }

    /** 开心跳 bidi 流；返回的 request observer 供调度线程发送。 */
    StreamObserver<HeartbeatRequest> openHeartbeatStream(StreamObserver<HeartbeatResponse> responseObserver) {
        return RegistryServiceGrpc.newStub(channel()).heartbeat(responseObserver);
    }

    void close() {
        ManagedChannel current;
        synchronized (this) {
            closed = true;
            current = channel;
            channel = null;
        }
        if (current != null) {
            shutdown(current);
        }
    }

    private static ReportOutcome checkAck(String operation, ServiceInstance instance, ResponseStatus status) {
        if (status.getAck() == Ack.ACK_FAIL) {
            ErrorInfo error = status.getError();
            LOG.debug(
                    "{} report rejected for {} ({}: {})",
                    operation,
                    instance,
                    error == null ? "UNKNOWN" : error.getCode(),
                    error == null ? "no details" : error.getMessage());
            return ReportOutcome.REJECTED;
        }
        return ReportOutcome.REPORTED;
    }

    private static List<String> collectDeregisterMessages(DeregisterResponse response) {
        if (!response.hasData()) {
            return java.util.Collections.emptyList();
        }
        List<String> messages = new java.util.ArrayList<>();
        for (InstanceError failed : response.getData().getInstancesList()) {
            messages.add(failed.getInstance().getInstanceId() + ": "
                    + failed.getError().getMessage());
        }
        return messages;
    }

    private static void shutdown(ManagedChannel channel) {
        try {
            channel.shutdown();
            if (!channel.awaitTermination(2, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }
    }
}
