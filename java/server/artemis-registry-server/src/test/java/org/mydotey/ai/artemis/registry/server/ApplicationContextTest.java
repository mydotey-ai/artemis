package org.mydotey.ai.artemis.registry.server;

import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.InMemorySnapshotStore;
import org.mydotey.ai.artemis.registry.domain.RegistrySnapshotStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@SpringBootTest(properties = {"spring.grpc.server.port=0", "server.port=0", "management.server.port=0"})
class ApplicationContextTest {

    /** 产品内部端口不接受 0：测试启动前预分配固定端口。 */
    private static final int PEER_PORT = TestGrpcPorts.freePort();

    @org.springframework.test.context.DynamicPropertySource
    static void peerPort(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("artemis.registry.internal.port", () -> PEER_PORT);
    }

    @Test
    void should_load_application_context_when_server_starts() {
        // 上下文由 @SpringBootTest 启动，进入测试即加载成功
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @Primary
        RegistrySnapshotStore inMemorySnapshotStore() {
            // 隔离文件系统：全上下文测试不读写工作目录快照，结果不依赖本地残留
            return new InMemorySnapshotStore();
        }
    }
}
