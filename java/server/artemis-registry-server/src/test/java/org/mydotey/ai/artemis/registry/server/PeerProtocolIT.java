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
import org.mydotey.ai.artemis.proto.management.v1.ListServicesRequest;
import org.mydotey.ai.artemis.proto.management.v1.RegistryQueryServiceGrpc;
import org.mydotey.ai.artemis.proto.replication.v1.GetNodeStatusRequest;
import org.mydotey.ai.artemis.proto.replication.v1.NodeState;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicationServiceGrpc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/**
 * 双监听基础语义（内部认证默认关闭）：内部端口起停与两个服务可用，
 * 外部端口不暴露内部服务（UNIMPLEMENTED）。
 */
@SpringBootTest(properties = {"spring.grpc.server.port=0", "server.port=0", "management.server.port=0"})
class PeerProtocolIT {

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
    private ReplicationServiceGrpc.ReplicationServiceBlockingStub externalReplication;
    private ReplicationServiceGrpc.ReplicationServiceBlockingStub internalReplication;
    private RegistryQueryServiceGrpc.RegistryQueryServiceBlockingStub externalQuery;
    private RegistryQueryServiceGrpc.RegistryQueryServiceBlockingStub internalQuery;

    @BeforeEach
    void setUp() {
        externalPort = TestGrpcPorts.external(lifecycles);
        externalChannel = channel(externalPort);
        internalChannel = channel(TestGrpcPorts.internal(lifecycles));
        // 外部端口带合法外部凭证：用于断言「内部服务不在外部端口」（否则先被认证拦截）
        externalReplication =
                ReplicationServiceGrpc.newBlockingStub(externalChannel).withInterceptors(AuthClients.authenticated());
        internalReplication = ReplicationServiceGrpc.newBlockingStub(internalChannel);
        externalQuery =
                RegistryQueryServiceGrpc.newBlockingStub(externalChannel).withInterceptors(AuthClients.authenticated());
        internalQuery = RegistryQueryServiceGrpc.newBlockingStub(internalChannel);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        externalChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        internalChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void should_start_peer_listener_when_context_starts() {
        var response = internalReplication.getNodeStatus(GetNodeStatusRequest.getDefaultInstance());

        assertThat(response.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
        assertThat(response.getData().getSelf().getState()).isEqualTo(NodeState.NODE_STATE_UP);
    }

    @Test
    void should_not_serve_internal_services_on_external_port() {
        assertThatThrownBy(() -> externalReplication.getNodeStatus(GetNodeStatusRequest.getDefaultInstance()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNIMPLEMENTED));
        assertThatThrownBy(() -> externalQuery.listServices(ListServicesRequest.getDefaultInstance()))
                .isInstanceOfSatisfying(
                        StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNIMPLEMENTED));
    }

    @Test
    void should_allow_internal_call_without_credentials_when_auth_disabled() {
        assertThat(internalReplication
                        .getNodeStatus(GetNodeStatusRequest.getDefaultInstance())
                        .getStatus()
                        .getAck())
                .isEqualTo(Ack.ACK_SUCCESS);
        assertThat(internalQuery
                        .listServices(ListServicesRequest.newBuilder()
                                .setNamespace("any")
                                .build())
                        .getStatus()
                        .getAck())
                .isEqualTo(Ack.ACK_SUCCESS);
    }

    private static ManagedChannel channel(int port) {
        return ManagedChannelBuilder.forAddress("localhost", port)
                .usePlaintext()
                .build();
    }
}
