package org.mydotey.ai.artemis.discovery.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * context 加载冒烟：上游 endpoints 为必填项（fail-fast），测试以占位端点通过配置校验
 * （可达性不属于启动校验，同步器自行退避重试）。
 */
@SpringBootTest(
        properties = {
            "artemis.discovery.upstream.endpoints=127.0.0.1:1",
            "spring.grpc.server.port=0",
            "server.port=0",
            "management.server.port=0"
        })
class ApplicationContextTest {

    @Test
    void should_load_application_context_when_server_starts() {
        // 上下文由 @SpringBootTest 启动，进入测试即加载成功
    }
}
