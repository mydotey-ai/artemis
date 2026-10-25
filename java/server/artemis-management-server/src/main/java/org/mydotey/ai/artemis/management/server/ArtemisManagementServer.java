package org.mydotey.ai.artemis.management.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 治理面服务端入口。装配内容随版本递补：治理 REST API 与 DB 持久化（期 2/v0.4）。
 *
 * <p>排除 {@code DataSourceAutoConfiguration}（excludeName 字符串形式：组合 classpath 上
 * 无 spring-boot-jdbc 时 Class 引用会使注解属性解析失败）：DB 是可选依赖，未配置
 * {@code spring.datasource.url} 时零 DB 装配、内存态运行——Boot 自动配置在无 url 时会因
 * 驱动推断失败拒绝启动；配置 url 后由 {@code PersistenceConfiguration} 条件装配 DataSource，
 * Boot 的 Flyway/事务/JdbcTemplate 自动配置随之接管。
 */
@SpringBootApplication(excludeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
public class ArtemisManagementServer {

    public static void main(String[] args) {
        SpringApplication.run(ArtemisManagementServer.class, args);
    }
}
