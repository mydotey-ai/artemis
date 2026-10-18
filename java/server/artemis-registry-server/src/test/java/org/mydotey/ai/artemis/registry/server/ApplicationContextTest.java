package org.mydotey.ai.artemis.registry.server;

import org.junit.jupiter.api.Test;
import org.mydotey.ai.artemis.registry.InMemorySnapshotStore;
import org.mydotey.ai.artemis.registry.domain.RegistrySnapshotStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@SpringBootTest(properties = "spring.grpc.server.port=0")
class ApplicationContextTest {

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
