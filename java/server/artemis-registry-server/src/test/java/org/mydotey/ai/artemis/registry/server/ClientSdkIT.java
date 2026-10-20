package org.mydotey.ai.artemis.registry.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.client.ArtemisClient;
import org.mydotey.ai.artemis.client.ArtemisClients;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;
import org.mydotey.ai.artemis.registry.InMemorySnapshotStore;
import org.mydotey.ai.artemis.registry.MutableClock;
import org.mydotey.ai.artemis.registry.TestAwait;
import org.mydotey.ai.artemis.registry.TestProperties;
import org.mydotey.ai.artemis.registry.application.RegistryService;
import org.mydotey.ai.artemis.registry.domain.DeregisterRequest;
import org.mydotey.ai.artemis.registry.domain.InstanceKey;
import org.mydotey.ai.artemis.registry.domain.RegistrySnapshotStore;
import org.mydotey.ai.artemis.registry.domain.ServiceKey;
import org.mydotey.scf.facade.StringProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;

/**
 * SDK 端到端集成测试：artemis-client（release=8，JDK 25 JVM 正常加载；Java 8 兼容由 client
 * 单测覆盖）驱动进程内真实 registry 服务——注册可见性、心跳续约、update 覆盖、close 优雅下线、
 * 认证拒绝。心跳间隔经系统属性压到 500ms（SDK builder 缺省配置源含系统属性）；
 * 服务端 TTL 压到 1s（测试装配），续约用例以虚拟时钟推进区分「有无续约」。
 */
@SpringBootTest(properties = {"spring.grpc.server.port=0", "server.port=0", "management.server.port=0"})
class ClientSdkIT {

    /** 产品内部端口不接受 0：测试启动前预分配固定端口。 */
    private static final int PEER_PORT = TestGrpcPorts.freePort();

    private static final ServiceId ORDER =
            ServiceId.newBuilder().namespace("default").service("order-service").build();

    private static final ServiceKey DOMAIN_KEY = new ServiceKey("default", "order-service");

    @Autowired
    private Map<String, GrpcServerLifecycle> lifecycles;

    private int port;

    @Autowired
    private RegistryService registryService;

    @Autowired
    private MutableClock clock;

    private ArtemisClient client;

    @BeforeEach
    void setUp() {
        port = TestGrpcPorts.external(lifecycles);
        System.setProperty("artemis.client.heartbeat.intervalMs", "500");
        // context 跨用例缓存：时钟归零、清残留实例，保证用例独立
        clock.reset();
        registryService.deregister(
                new DeregisterRequest(List.of(new InstanceKey(DOMAIN_KEY, "i-1"), new InstanceKey(DOMAIN_KEY, "i-2"))));
        System.setProperty("artemis.client.serverAddresses", "localhost:" + port);
        System.setProperty("artemis.client.auth.username", "artemis");
        System.setProperty("artemis.client.auth.password", "artemis");
        client = ArtemisClients.newClient();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        System.clearProperty("artemis.client.heartbeat.intervalMs");
        System.clearProperty("artemis.client.serverAddresses");
        System.clearProperty("artemis.client.auth.username");
        System.clearProperty("artemis.client.auth.password");
    }

    private static ServiceInstance instance(String instanceId, String host) {
        return ServiceInstance.newBuilder()
                .service(ORDER)
                .instanceId(instanceId)
                .host(host)
                .port(8080)
                .weight(100)
                .build();
    }

    private int instanceCount() {
        return registryService
                .getInstances(DOMAIN_KEY)
                .map(s -> s.instances().size())
                .orElse(0);
    }

    @Test
    void should_registerAndBecomeVisible_when_sdkStarted() {
        client.start();

        client.register(instance("i-1", "10.0.0.1"));

        TestAwait.await("instance visible in server registry", () -> instanceCount() == 1);
        // 心跳通道全量携带注册实例（幂等 upsert，不重复、不丢失）
        TestAwait.await("instance stays visible across heartbeats", () -> {
            registryService.evictExpired();
            return instanceCount() == 1;
        });
    }

    @Test
    void should_renewLease_when_heartbeatPeriodic() throws Exception {
        client.start();
        client.register(instance("i-1", "10.0.0.1"));
        TestAwait.await("registered", () -> instanceCount() == 1);

        // 虚拟时钟推进 0.9s（< TTL 1s）；等真实心跳（500ms 周期，1.2s 保 2 跳）把续约点拉到 0.9s；
        // 再推进 0.9s：有续约 → now-续约点 = 0.9s < 1s 仍在；无续约 → 1.8s > 1s 已过期被摘
        clock.advance(java.time.Duration.ofMillis(900));
        Thread.sleep(1200);
        clock.advance(java.time.Duration.ofMillis(900));
        registryService.evictExpired();
        assertThat(instanceCount()).isEqualTo(1);
    }

    @Test
    void should_coverInstance_when_updateCalled() {
        client.start();
        client.register(instance("i-1", "10.0.0.1"));

        client.update(instance("i-1", "10.0.0.9"));

        TestAwait.await(
                "instance host updated",
                () -> registryService
                        .getInstances(DOMAIN_KEY)
                        .map(services -> services.instances().stream()
                                .anyMatch(i -> "i-1".equals(i.instanceId()) && "10.0.0.9".equals(i.host())))
                        .orElse(false));
    }

    @Test
    void should_deregisterImmediately_when_close() {
        client.start();
        client.register(instance("i-1", "10.0.0.1"));
        TestAwait.await("registered", () -> instanceCount() == 1);

        client.close();

        // close 优雅下线：显式注销即时生效（不等 TTL）
        TestAwait.await("instance gone after close", () -> instanceCount() == 0);
    }

    @Test
    void should_reject_when_wrongCredentials() throws Exception {
        System.setProperty("artemis.client.auth.password", "wrong-password"); // username 沿用正确值
        ArtemisClient unauthorized = ArtemisClients.newClient();
        try {
            unauthorized.start();

            // fire-and-forget：错误凭证不抛不返回，服务端拒绝 → 实例不可见（WARN + metrics 内化）
            unauthorized.register(instance("i-1", "10.0.0.1"));
            Thread.sleep(1000); // 上报异步执行（deadline 5s 内完成，1s 足够到达且被拒）
            assertThat(instanceCount()).isEqualTo(0);
        } finally {
            unauthorized.close();
            System.clearProperty("artemis.client.auth.password");
        }
    }

    // ---- 测试装配（同 RegistryGrpcIT：可控时钟 + 短 TTL + 快照隔离文件系统）----

    @TestConfiguration(proxyBeanMethods = false)
    static class ClientSdkTestConfig {

        @Bean
        @Primary
        MutableClock testClock() {
            // MutableClock 同源实现壁挂/单调两端口（D14）
            return new MutableClock();
        }

        @Bean
        @Primary
        StringProperties testProperties() {
            return TestProperties.of(Map.of(
                            "artemis.registry.lease.ttlMs",
                            "1000",
                            "artemis.registry.internal.port",
                            String.valueOf(PEER_PORT)))
                    .properties();
        }

        @Bean
        @Primary
        RegistrySnapshotStore inMemorySnapshotStore() {
            return new InMemorySnapshotStore();
        }
    }
}
