package org.mydotey.ai.artemis.discovery.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ApplicationContextTest {

    @Test
    void should_load_application_context_when_server_starts() {
        // 上下文由 @SpringBootTest 启动，进入测试即加载成功
    }
}
