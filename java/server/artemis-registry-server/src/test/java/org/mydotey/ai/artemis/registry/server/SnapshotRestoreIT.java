package org.mydotey.ai.artemis.registry.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mydotey.ai.artemis.proto.common.v1.Ack;
import org.mydotey.ai.artemis.proto.common.v1.Instance;
import org.mydotey.ai.artemis.proto.common.v1.InstanceStatus;
import org.mydotey.ai.artemis.proto.common.v1.ServiceInstances;
import org.mydotey.ai.artemis.proto.common.v1.ServiceKey;
import org.mydotey.ai.artemis.proto.registry.v1.HeartbeatRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegisterRequest;
import org.mydotey.ai.artemis.proto.registry.v1.RegistryServiceGrpc;
import org.mydotey.ai.artemis.registry.MutableClock;
import org.mydotey.ai.artemis.registry.TestAwait;
import org.mydotey.ai.artemis.registry.TestProperties;
import org.mydotey.ai.artemis.registry.application.RegistryService;
import org.mydotey.ai.artemis.registry.domain.ChangeListener;
import org.mydotey.ai.artemis.registry.domain.ChangeType;
import org.mydotey.ai.artemis.registry.domain.InstanceChange;
import org.mydotey.scf.facade.StringProperties;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 重启回放端到端：进程内手动双 context 生命周期、同快照文件——gRPC 注册 → 手动落盘 →
 * 关 context（模拟停机）→ 同文件重起 → 断言实例恢复、回放零事件、TTL 从回放起算、
 * 就绪后 gRPC 服务可用（启动三分支 ② 的落地 + readiness 正常路径）。
 */
class SnapshotRestoreIT {

    private static final String NAMESPACE = "default";
    private static final String SERVICE = "order-service";
    private static final org.mydotey.ai.artemis.registry.domain.ServiceKey DOMAIN_KEY =
            new org.mydotey.ai.artemis.registry.domain.ServiceKey(NAMESPACE, SERVICE);

    @TempDir
    Path snapshotDir;

    @Test
    void should_restore_instances_after_server_restart() throws Exception {
        Path snapshotFile = snapshotDir.resolve("registry-snapshot.bin");

        // ---- context A：gRPC 注册 → 手动落盘 → 关闭（模拟停机）----
        int portA = freePort();
        ConfigurableApplicationContext contextA = startContext(portA, snapshotFile);
        try {
            ManagedChannel channelA = channel(portA);
            RegistryService serviceA = contextA.getBean(RegistryService.class);
            assertThat(RegistryServiceGrpc.newBlockingStub(channelA)
                            .withInterceptors(AuthClients.authenticated())
                            .register(registerRequest("i1"))
                            .getStatus()
                            .getAck())
                    .isEqualTo(Ack.ACK_SUCCESS);
            TestAwait.await("registered", () -> instanceCount(serviceA) == 1);
            serviceA.writeSnapshot(); // 手动触发落盘（生产由调度器周期触发；时间戳取注入时钟 = 0）
            channelA.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } finally {
            contextA.close();
        }

        // ---- context B：同快照文件重起 → 回放恢复 ----
        int portB = freePort();
        ConfigurableApplicationContext contextB = startContext(portB, snapshotFile);
        try {
            RegistryService serviceB = contextB.getBean(RegistryService.class);
            MutableClock clockB = contextB.getBean(MutableClock.class);
            List<InstanceChange> eventsB = changeSink(contextB);

            // 回放零事件（D4）：启动完成后 listener 零回调；实例已恢复
            assertThat(eventsB).isEmpty();
            assertThat(instanceCount(serviceB)).isEqualTo(1);

            // 就绪后服务可用（readiness 正常路径）：gRPC 心跳内容一致 → 纯续约零事件（实例在且内容等价）；
            // update 经完整 wire 链路生效（回放实例照常可变更）
            ManagedChannel channelB = channel(portB);
            try {
                assertThat(Heartbeats.collect(channelB, heartbeatRequest(1L, "i1")))
                        .hasSize(1);
                assertThat(RegistryServiceGrpc.newBlockingStub(channelB)
                                .withInterceptors(AuthClients.authenticated())
                                .update(updateRequest("i1"))
                                .getStatus()
                                .getAck())
                        .isEqualTo(Ack.ACK_SUCCESS);
            } finally {
                channelB.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            }
            assertThat(eventsB).noneMatch(change -> change.type() == ChangeType.NEW);
            assertThat(serviceB.getInstances(DOMAIN_KEY))
                    .hasValueSatisfying(
                            s -> assertThat(s.instances().get(0).weight()).isEqualTo(200));

            // TTL 从回放起算（TTL 1000ms）：距回放 500ms 在、再 +510ms 后被清理调度摘除
            clockB.advance(Duration.ofMillis(500));
            assertThat(instanceCount(serviceB)).isEqualTo(1);
            clockB.advance(Duration.ofMillis(510));
            TestAwait.await("evicted after ttl counted from restore", () -> instanceCount(serviceB) == 0);
        } finally {
            contextB.close();
        }
    }

    // ---- 双 context 测试装配（可控时钟 + 短 TTL + 事件收集；快照 store 走真实文件）----

    @Configuration(proxyBeanMethods = false)
    static class RestoreTestConfig {

        /** 每个 context 启动前由测试设置，供测试 StringProperties 指向 @TempDir 隔离路径。 */
        static Path snapshotFilePath;

        /** 产品内部端口不接受 0：每个 context 启动前预分配固定端口。 */
        static int peerPort;

        @Bean
        @Primary
        MutableClock testClock() {
            // 同源实现壁挂/单调两端口（D14）；两 context 独立实例、同起点 0 → 快照 age = 0 时效内
            return new MutableClock();
        }

        @Bean
        @Primary
        StringProperties testProperties() {
            return TestProperties.of(Map.of(
                            "artemis.registry.lease.ttlMs",
                            "1000",
                            "artemis.registry.snapshot.path",
                            snapshotFilePath.toString(),
                            "artemis.registry.internal.port",
                            String.valueOf(peerPort)))
                    .properties();
        }

        @Bean
        @Primary
        ChangeListener changeCollector(List<InstanceChange> sink) {
            return sink::add;
        }

        @Bean
        List<InstanceChange> changeSink() {
            return new CopyOnWriteArrayList<>();
        }
    }

    // ---- 设施 ----

    private ConfigurableApplicationContext startContext(int grpcPort, Path snapshotFile) {
        RestoreTestConfig.snapshotFilePath = snapshotFile;
        RestoreTestConfig.peerPort = TestGrpcPorts.freePort();
        // 命令行参数优先级高于 application.properties（本用例需覆盖默认端口/关闭 web）
        return new SpringApplicationBuilder(ArtemisRegistryServer.class)
                .sources(RestoreTestConfig.class)
                .run("--spring.grpc.server.port=" + grpcPort, "--spring.main.web-application-type=none");
    }

    @SuppressWarnings("unchecked")
    private static List<InstanceChange> changeSink(ConfigurableApplicationContext context) {
        return context.getBean("changeSink", List.class);
    }

    private static ManagedChannel channel(int port) {
        return ManagedChannelBuilder.forAddress("localhost", port)
                .usePlaintext()
                .build();
    }

    private static HeartbeatRequest heartbeatRequest(long seq, String instanceId) {
        return HeartbeatRequest.newBuilder()
                .setSeq(seq)
                .addServices(serviceInstances(instanceId))
                .build();
    }

    private static int instanceCount(RegistryService service) {
        return service.getInstances(DOMAIN_KEY)
                .map(services -> services.instances().size())
                .orElse(0);
    }

    private static RegisterRequest registerRequest(String instanceId) {
        return RegisterRequest.newBuilder()
                .addServices(serviceInstances(instanceId))
                .build();
    }

    private static ServiceInstances.Builder serviceInstances(String instanceId) {
        return serviceInstances(instanceId, 100);
    }

    private static org.mydotey.ai.artemis.proto.registry.v1.UpdateRequest updateRequest(String instanceId) {
        return org.mydotey.ai.artemis.proto.registry.v1.UpdateRequest.newBuilder()
                .addServices(serviceInstances(instanceId, 200))
                .build();
    }

    private static ServiceInstances.Builder serviceInstances(String instanceId, int weight) {
        return ServiceInstances.newBuilder()
                .setKey(ServiceKey.newBuilder().setNamespace(NAMESPACE).setService(SERVICE))
                .addInstances(Instance.newBuilder()
                        .setInstanceId(instanceId)
                        .setHost("10.0.0.1")
                        .setPort(8080)
                        .setStatus(InstanceStatus.INSTANCE_STATUS_UP)
                        .setWeight(weight));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
