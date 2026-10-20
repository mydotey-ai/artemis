package org.mydotey.ai.artemis.registry.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusRequest;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicationServiceGrpc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/**
 * 认证隔离（内部认证显式启用）：内部/外部账户互不通用，无凭证拒绝；
 * 外部端口仍不暴露内部服务。
 */
@SpringBootTest(
        properties = {
            "spring.grpc.server.port=0",
            "server.port=0",
            "management.server.port=0",
            "artemis.registry.internal.auth.enabled=true",
            "artemis.registry.internal.auth.username=peer",
            "artemis.registry.internal.auth.password=secret"
        })
class PeerAuthIsolationIT {

    private static final String INTERNAL_USER = "peer";
    private static final String INTERNAL_PASSWORD = "secret";

    /** 产品内部端口不接受 0：测试启动前预分配固定端口。 */
    private static final int PEER_PORT = TestGrpcPorts.freePort();

    @org.springframework.test.context.DynamicPropertySource
    static void peerPort(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("artemis.registry.internal.port", () -> PEER_PORT);
    }

    @Autowired
    private Map<String, GrpcServerLifecycle> lifecycles;

    private int externalPort;

    private ManagedChannel externalChannel;
    private ManagedChannel internalChannel;

    @BeforeEach
    void setUp() {
        externalPort = TestGrpcPorts.external(lifecycles);
        externalChannel = channel(externalPort);
        internalChannel = channel(TestGrpcPorts.internal(lifecycles));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        externalChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        internalChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void should_isolate_credentials_between_external_and_internal_ports() {
        // 内部凭证 + 内部端口：放行
        assertThat(stub(internalChannel, INTERNAL_USER, INTERNAL_PASSWORD)
                        .getNodeStatus(GetNodeStatusRequest.getDefaultInstance())
                        .getStatus()
                        .getAck())
                .isEqualTo(Ack.ACK_SUCCESS);

        // 无凭证 + 内部端口：拒绝
        assertUnauthenticated(stub(internalChannel, null, null));

        // 外部凭证 + 内部端口：拒绝（账户不通用）
        assertUnauthenticated(stub(internalChannel, AuthClients.USERNAME, AuthClients.PASSWORD));

        // 内部凭证 + 外部端口：拒绝（用外部 RegistryService 验证；未知服务会先于拦截器返回 UNIMPLEMENTED）
        assertThatThrownBy(() -> org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc.newBlockingStub(
                                externalChannel)
                        .withInterceptors(AuthClients.authenticated(INTERNAL_USER, INTERNAL_PASSWORD))
                        .register(org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest.getDefaultInstance()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));

        // 外部凭证 + 外部端口：认证通过，但内部服务不在外部端口（UNIMPLEMENTED）
        assertThatThrownBy(() -> stub(externalChannel, AuthClients.USERNAME, AuthClients.PASSWORD)
                        .getNodeStatus(GetNodeStatusRequest.getDefaultInstance()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNIMPLEMENTED));
    }

    @Test
    void should_require_internal_credentials_for_query_when_auth_enabled() {
        // 内部凭证 + 内部端口：放行
        assertThat(org.mydotey.ai.artemis.proto.management.v1.RegistryQueryServiceGrpc.newBlockingStub(internalChannel)
                        .withInterceptors(AuthClients.authenticated(INTERNAL_USER, INTERNAL_PASSWORD))
                        .listServices(
                                org.mydotey.ai.artemis.proto.management.v1.ListServicesRequest.getDefaultInstance())
                        .getStatus()
                        .getAck())
                .isEqualTo(Ack.ACK_SUCCESS);

        // 无凭证 / 外部凭证：拒绝（查询与其他内部服务共用内部认证）
        for (var stub : java.util.List.of(
                org.mydotey.ai.artemis.proto.management.v1.RegistryQueryServiceGrpc.newBlockingStub(internalChannel),
                org.mydotey.ai.artemis.proto.management.v1.RegistryQueryServiceGrpc.newBlockingStub(internalChannel)
                        .withInterceptors(AuthClients.authenticated(AuthClients.USERNAME, AuthClients.PASSWORD)))) {
            assertThatThrownBy(() -> stub.listServices(
                            org.mydotey.ai.artemis.proto.management.v1.ListServicesRequest.getDefaultInstance()))
                    .isInstanceOfSatisfying(
                            StatusRuntimeException.class,
                            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        }
    }

    private static void assertUnauthenticated(ReplicationServiceGrpc.ReplicationServiceBlockingStub stub) {
        assertThatThrownBy(() -> stub.getNodeStatus(GetNodeStatusRequest.getDefaultInstance()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    private static ReplicationServiceGrpc.ReplicationServiceBlockingStub stub(
            ManagedChannel channel, String username, String password) {
        ReplicationServiceGrpc.ReplicationServiceBlockingStub stub = ReplicationServiceGrpc.newBlockingStub(channel);
        return username == null ? stub : stub.withInterceptors(AuthClients.authenticated(username, password));
    }

    private static ManagedChannel channel(int port) {
        return ManagedChannelBuilder.forAddress("localhost", port)
                .usePlaintext()
                .build();
    }
}
