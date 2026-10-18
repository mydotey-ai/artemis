package org.mydotey.ai.artemis.registry.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * registry 服务端入口：外部 gRPC（SDK 四操作 + 内部只读查询）、外部 HTTP、管理 HTTP，
 * 默认认证/健康检查/指标/结构化日志/trace 均已在装配层接入。
 */
@SpringBootApplication
public class ArtemisRegistryServer {

    public static void main(String[] args) {
        SpringApplication.run(ArtemisRegistryServer.class, args);
    }
}
