package org.mydotey.ai.artemis.registry.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * registry 服务端入口。装配内容随 phase 递补：gRPC 传输（phase-4）、健康检查与指标。
 */
@SpringBootApplication
public class ArtemisRegistryServer {

    public static void main(String[] args) {
        SpringApplication.run(ArtemisRegistryServer.class, args);
    }
}
