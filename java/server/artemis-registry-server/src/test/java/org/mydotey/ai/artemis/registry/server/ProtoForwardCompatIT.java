package org.mydotey.ai.artemis.registry.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.common.v1.ServiceInstances;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatRequest;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatResponse;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.mydotey.ai.artemis.proto.replication.v1.ChangeType;
import org.mydotey.ai.artemis.proto.replication.v1.InstanceChange;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicateRequest;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicateResponse;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicationBatch;
import org.mydotey.ai.artemis.proto.replication.v1.ReplicationServiceGrpc;
import org.mydotey.ai.artemis.proto.replication.v1.ServiceChange;
import org.mydotey.ai.artemis.registry.TestAwait;
import org.mydotey.ai.artemis.registry.application.RegistryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/**
 * 前向兼容运行时验证（v1.0 phase-5 滚动升级·行为层）：滚动升级中旧服务端收到「未来版本」
 * 消息（含本版本未知的字段），protobuf 默认忽略未知字段——本 IT 验证整条接入链路
 * （gRPC 解码 → application mapper → domain）无破坏该默认行为（如字段严格校验），
 * 数据面入口（心跳流、复制投递）正常处理并产生正确数据效果。
 * 反方向（旧消息 = 新字段缺省）由既有全部 IT 天然覆盖（消息类只有当前一个版本）。
 */
@SpringBootTest(properties = {"spring.grpc.server.port=0", "server.port=0", "management.server.port=0"})
class ProtoForwardCompatIT {

    /** 产品内部端口不接受 0：测试启动前预分配固定端口。 */
    private static final int PEER_PORT = TestGrpcPorts.freePort();

    /** 模拟未来版本新增字段：本版本契约未使用的 field number，覆盖 varint 与 length-delimited 两种 wire 类型。 */
    private static final UnknownFieldSet FUTURE_FIELDS = UnknownFieldSet.newBuilder()
            .addField(9001, UnknownFieldSet.Field.newBuilder().addVarint(42).build())
            .addField(
                    9002,
                    UnknownFieldSet.Field.newBuilder()
                            .addLengthDelimited(ByteString.copyFromUtf8("future"))
                            .build())
            .build();

    private static final String NAMESPACE = "compat";
    /** 两用例共享同一 Spring context（注册表为 context 级单例），service 隔离防互相污染。 */
    private static final String HEARTBEAT_SERVICE = "future-heartbeat-service";

    private static final String REPLICATION_SERVICE = "future-replication-service";

    @org.springframework.test.context.DynamicPropertySource
    static void peerPort(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("artemis.registry.internal.port", () -> PEER_PORT);
    }

    @Autowired
    private RegistryService registryService;

    @Autowired
    private java.util.Map<String, GrpcServerLifecycle> lifecycles;

    private ManagedChannel externalChannel;
    private ManagedChannel internalChannel;

    @BeforeEach
    void setUp() {
        externalChannel = ManagedChannelBuilder.forAddress("localhost", TestGrpcPorts.external(lifecycles))
                .usePlaintext()
                .build();
        internalChannel = ManagedChannelBuilder.forAddress("localhost", TestGrpcPorts.internal(lifecycles))
                .usePlaintext()
                .build();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        externalChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        internalChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void should_processFutureVersionPayload_when_heartbeatCarriesUnknownFields() throws Exception {
        List<HeartbeatResponse> acks = new CopyOnWriteArrayList<>();
        CountDownLatch acked = new CountDownLatch(1);
        StreamObserver<HeartbeatResponse> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(HeartbeatResponse value) {
                acks.add(value);
                acked.countDown();
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onCompleted() {}
        };
        StreamObserver<HeartbeatRequest> requestObserver = RegistryServiceGrpc.newStub(externalChannel)
                .withInterceptors(AuthClients.authenticated())
                .heartbeat(responseObserver);
        try {
            requestObserver.onNext(HeartbeatRequest.newBuilder()
                    .addServices(ServiceInstances.newBuilder()
                            .setKey(ServiceKey.newBuilder()
                                    .setNamespace(NAMESPACE)
                                    .setService(HEARTBEAT_SERVICE))
                            .addInstances(Instance.newBuilder()
                                    .setInstanceId("i-1")
                                    .setHost("10.0.0.1")
                                    .setPort(8080)
                                    .setWeight(100)))
                    .setSeq(7)
                    .setUnknownFields(FUTURE_FIELDS)
                    .build());

            assertThat(acked.await(10, TimeUnit.SECONDS)).as("心跳 ack 到达").isTrue();
            HeartbeatResponse ack = acks.get(0);
            assertThat(ack.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
            assertThat(ack.getData().getSeq()).isEqualTo(7);

            // 未知字段不影响数据语义：心跳 upsert（缺席自动注册）正常落地
            TestAwait.await(
                    "future payload instance registered via heartbeat",
                    () -> instances(HEARTBEAT_SERVICE).size() == 1);
        } finally {
            requestObserver.onCompleted();
        }
    }

    @Test
    void should_processFutureVersionPayload_when_replicationCarriesUnknownFields() {
        ReplicationServiceGrpc.ReplicationServiceBlockingStub replication =
                ReplicationServiceGrpc.newBlockingStub(internalChannel);

        ReplicateResponse response = replication.replicate(ReplicateRequest.newBuilder()
                .setBatch(ReplicationBatch.newBuilder()
                        .setSourceNodeId("future-node")
                        .addServices(ServiceChange.newBuilder()
                                .setKey(ServiceKey.newBuilder()
                                        .setNamespace(NAMESPACE)
                                        .setService(REPLICATION_SERVICE))
                                .addChanges(InstanceChange.newBuilder()
                                        .setInstance(Instance.newBuilder()
                                                .setInstanceId("i-replicated")
                                                .setHost("10.0.0.2")
                                                .setPort(8080)
                                                .setWeight(100))
                                        .setType(ChangeType.CHANGE_TYPE_NEW))))
                .setUnknownFields(FUTURE_FIELDS)
                .build());

        assertThat(response.getStatus().getAck()).isEqualTo(Ack.ACK_SUCCESS);
        TestAwait.await(
                "future payload instance replicated",
                () -> instances(REPLICATION_SERVICE).size() == 1);
    }

    private List<?> instances(String service) {
        Optional<org.mydotey.ai.artemis.registry.domain.ServiceInstances> snapshot =
                registryService.getInstances(new org.mydotey.ai.artemis.registry.domain.ServiceKey(NAMESPACE, service));
        return snapshot.map(s -> s.instances()).orElse(List.of());
    }
}
