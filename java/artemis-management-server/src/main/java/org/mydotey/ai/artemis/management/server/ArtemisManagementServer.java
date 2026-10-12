package org.mydotey.ai.artemis.management.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 治理面服务端入口。装配内容随版本递补：治理 REST API 与 DB 持久化（期 2/v0.4）。
 */
@SpringBootApplication
public class ArtemisManagementServer {

    public static void main(String[] args) {
        SpringApplication.run(ArtemisManagementServer.class, args);
    }
}
