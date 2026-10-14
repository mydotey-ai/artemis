package org.mydotey.ai.artemis.registry.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.grpc.server.port=0")
class ApplicationContextTest {

    @Test
    void should_load_application_context_when_server_starts() {
        // 上下文由 @SpringBootTest 启动，进入测试即加载成功
    }
}
