package org.mydotey.ai.artemis.registry.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * registry 服务端入口：外部 gRPC（SDK 四操作 + 内部只读查询）、外部 HTTP、管理 HTTP，
 * 默认认证/健康检查/指标/结构化日志/trace 均已在装配层接入。
 *
 * <p>排除 {@code DataSourceAutoConfiguration}：registry 零外部依赖（架构 §2.1），不装配 DB；
 * 组合 classpath（IT fixture、dist 同 JVM 组合）可能混入 management 的 DB 依赖，
 * Boot 数据源自动配置在无 url 时会拒绝启动，显式排除声明装配边界。
 */
@SpringBootApplication(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
public class ArtemisRegistryServer {

    public static void main(String[] args) {
        SpringApplication.run(ArtemisRegistryServer.class, args);
    }
}
