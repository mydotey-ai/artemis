package org.mydotey.ai.artemis.management.server;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** MySQL 方言 IT：真实容器起停，migration V1 applied + DB 态可观测（无 Docker 环境整类跳过）。 */
@Testcontainers(disabledWithoutDocker = true)
class MySQLPersistenceIT extends PersistenceDialectTestBase {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Override
    String expectedDatabaseProduct() {
        return "MySQL";
    }
}
