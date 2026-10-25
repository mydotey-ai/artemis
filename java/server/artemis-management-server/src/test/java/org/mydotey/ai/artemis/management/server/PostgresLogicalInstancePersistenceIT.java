package org.mydotey.ai.artemis.management.server;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** PostgreSQL 方言逻辑实例持久化 IT（无 Docker 环境整类跳过）。 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresLogicalInstancePersistenceIT extends LogicalInstancePersistenceIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Override
    String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    @Override
    String jdbcUsername() {
        return POSTGRES.getUsername();
    }

    @Override
    String jdbcPassword() {
        return POSTGRES.getPassword();
    }
}
