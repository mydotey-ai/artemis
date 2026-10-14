package org.mydotey.ai.artemis.registry.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * registry 服务端入口。gRPC 传输已随 phase-2 装配；HTTP 辅助通道/认证/健康检查/指标随 phase-4 递补。
 */
@SpringBootApplication
public class ArtemisRegistryServer {

    public static void main(String[] args) {
        SpringApplication.run(ArtemisRegistryServer.class, args);
    }
}
