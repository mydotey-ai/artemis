package org.mydotey.ai.artemis.discovery.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * discovery 服务端入口。装配内容随版本递补：订阅推送（v0.2）、多集群投影（v0.3）。
 *
 * <p>排除 {@code DataSourceAutoConfiguration}：discovery 自身无持久化（只读投影）；
 * 组合 classpath（IT fixture、dist 同 JVM 组合）可能混入 management 的 DB 依赖，
 * Boot 数据源自动配置在无 url 时会拒绝启动，显式排除声明装配边界。
 */
@SpringBootApplication(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
public class ArtemisDiscoveryServer {

    public static void main(String[] args) {
        SpringApplication.run(ArtemisDiscoveryServer.class, args);
    }
}
