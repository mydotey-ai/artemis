package org.mydotey.ai.artemis.client.internal;

import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
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
import org.mydotey.scf.facade.StringProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gRPC 传输：channel 管理（经 {@link ServerAddressProvider} 选址/换址）+ Basic 认证 + unary 显式操作
 * 与心跳流 stub。凭证为 SCF 配置且**使用时重读**（凭证变更运行时生效）。
 * 地址由 provider 供给（节点列表选址、熔断降权）；channel 断开时记住地址，重建选址排除之
 * （重连/TTL 轮换换址），冷启动不排除。传输成败上报 provider（熔断计数）**仅由心跳引擎
 * {@link #onTransportFailed()}/{@link #onTransportSucceeded()} 驱动（单线程，归因无竞态）；
 * unary 不参与熔断计数——ops 线程与轮换并发时地址归因存在竞态窗，且 unary 失败已驱动重试与
 * 心跳补投，节点故障由心跳断流路径上报.
 * unary 带 5s deadline、失败不抛异常：返回 {@link ReportOutcome}——瞬态传输失败（UNAVAILABLE/
 * DEADLINE_EXCEEDED、节点列表未就绪）为 RETRIABLE，由调用方按配置有界重试；业务拒绝（ACK_FAIL）
 * 与非瞬态失败为 REJECTED（重试无意义）。每次尝试的失败细节 DEBUG（重试期间不刷 WARN），结论性
 * WARN 归编排层。
 */
final class GrpcRegistryClient {

    /** 上报结果三态：成功 / 瞬态可重试 / 终态拒绝。 */
    enum ReportOutcome {
        REPORTED,
        RETRIABLE,
        REJECTED
    }

    private static final Logger LOG = LoggerFactory.getLogger(GrpcRegistryClient.class);

    private static final long DEADLINE_MS = 5_000;

    private final ServerAddressProvider addressProvider;
    private final ClientInterceptor authInterceptor;

    private volatile ManagedChannel channel;
    private volatile String currentAddress;
    /** 刚断开的地址：重建 channel 时选址排除（换址）；建立后清空。 */
    private volatile String lastDisconnectedAddress;

    private volatile boolean closed;

    GrpcRegistryClient(ServerAddressProvider addressProvider, StringProperties properties) {
        this.addressProvider = addressProvider;
        // 凭证默认空：未配置不发认证头，认证裁决归服务端（显式配置方可认证，与服务端对称）；
        // 每次请求重读（变更运行时生效）；半配置 WARN 由共用 AuthInterceptor 统一发出
        this.authInterceptor = new AuthInterceptor(
                ClientProperties.authUsername(properties), ClientProperties.authPassword(properties));
    }

    /** 当前 channel（无则按选址建立；认证 interceptor 挂 channel 级，全部 RPC 覆盖）。close 后不再建立。 */
    ManagedChannel channel() {
        return channel(null);
    }

    /**
     * channel 获取：无/已关闭则重建（选址排除 {@code lastDisconnectedAddress}）；forcedExclude 非空
     * （TTL 轮换）时**强制重新选址排除之**——不复用活 channel，保证轮换必换址（同址为唯一候选时除外，
     * 直接复用重开流即完成对当前节点对账）。被替换的活 channel 异步 shutdown（不泄漏：其上的旧流由
     * generation 机制作废，shutdown 触发的 onError 被判为陈旧回调）。
     */
    private ManagedChannel channel(String forcedExclude) {
        ManagedChannel current = channel;
        if (forcedExclude != null || current == null || current.isShutdown()) {
            ManagedChannel replaced = null;
            synchronized (this) {
                if (closed) {
                    throw new IllegalStateException("registry client closed");
                }
                current = channel;
                if (forcedExclude != null || current == null || current.isShutdown()) {
                    String exclude = forcedExclude != null ? forcedExclude : lastDisconnectedAddress;
                    String address = addressProvider.select(exclude);
                    if (current != null && !current.isShutdown() && address.equals(currentAddress)) {
                        return current; // 唯一候选回到当前地址：复用活 channel 重开流（对账语义保留）
                    }
                    // keepAlive：半开连接（NAT 静默丢包）由 TCP keepalive 探测兜底；
                    // ack 看门狗（HeartbeatChannel）是应用层的第一道检测
                    replaced = current;
                    current = ManagedChannelBuilder.forTarget(address)
                            .usePlaintext()
                            .keepAliveTime(30, TimeUnit.SECONDS)
                            .keepAliveTimeout(10, TimeUnit.SECONDS)
                            .keepAliveWithoutCalls(true)
                            .intercept(authInterceptor)
                            .build();
                    channel = current;
                    currentAddress = address;
                    lastDisconnectedAddress = null;
                }
            }
            if (replaced != null) {
                replaced.shutdown(); // 被替换的活 channel 关闭（锁外，异步不阻塞）
            }
        }
        return current;
    }

    /** 换址重建 channel（断流重连/TTL 轮换路径，调度线程调用）：异步 shutdown 不等待，不阻塞心跳调度。 */
    void resetChannel() {
        ManagedChannel old;
        synchronized (this) {
            old = channel;
            channel = null;
            if (currentAddress != null) {
                lastDisconnectedAddress = currentAddress; // 重建选址排除刚断开的地址（换址）
            }
        }
        if (old != null) {
            old.shutdown();
        }
    }

    /** 传输失败上报（心跳断流/看门狗超时）：计入节点连续失败（达阈值熔断 + 触发重寻址）。 */
    void onTransportFailed() {
        addressProvider.markFailed(currentAddress);
    }

    /** 传输成功上报（心跳 ack）：清零节点失败计数并解除熔断。 */
    void onTransportSucceeded() {
        addressProvider.markSucceeded(currentAddress);
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
            return unexpectedOutcome("register", instance, e);
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
            return unexpectedOutcome("update", instance, e);
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
            return unexpectedOutcome("deregister", instances, e);
        }
    }

    /** 瞬态传输失败（UNAVAILABLE/DEADLINE_EXCEEDED）可重试（不计节点失败——熔断仅由心跳引擎上报，见类 javadoc）；其余（UNAUTHENTICATED 等配置性失败）终态。 */
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

    /** 非 gRPC 异常：节点列表未就绪（前置寻址中）为瞬态可重试；其余终态（重试无意义）。 */
    private ReportOutcome unexpectedOutcome(String operation, Object target, RuntimeException e) {
        LOG.debug("{} report failed for {}", operation, target, e);
        return e instanceof NoAddressException ? ReportOutcome.RETRIABLE : ReportOutcome.REJECTED;
    }

    /** 当前连接地址（TTL 轮换取「上一个地址」作强制排除用）。 */
    String currentAddress() {
        return currentAddress;
    }

    /** 开心跳 bidi 流；返回的 request observer 供调度线程发送。 */
    StreamObserver<HeartbeatRequest> openHeartbeatStream(StreamObserver<HeartbeatResponse> responseObserver) {
        return RegistryServiceGrpc.newStub(channel()).heartbeat(responseObserver);
    }

    /** 开心跳 bidi 流（TTL 轮换）：强制排除 forcedExclude 重新选址，不复用活 channel。 */
    StreamObserver<HeartbeatRequest> openHeartbeatStream(
            StreamObserver<HeartbeatResponse> responseObserver, String forcedExclude) {
        return RegistryServiceGrpc.newStub(channel(forcedExclude)).heartbeat(responseObserver);
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
