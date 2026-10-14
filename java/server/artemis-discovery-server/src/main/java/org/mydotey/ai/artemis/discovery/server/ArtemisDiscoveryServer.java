package org.mydotey.ai.artemis.discovery.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * discovery 服务端入口。装配内容随版本递补：订阅推送（v0.2）、多集群投影（v0.3）。
 */
@SpringBootApplication
public class ArtemisDiscoveryServer {

    public static void main(String[] args) {
        SpringApplication.run(ArtemisDiscoveryServer.class, args);
    }
}
