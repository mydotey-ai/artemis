package org.mydotey.ai.artemis.client.internal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Basic 认证头构造（与 registry 服务端 GrpcAuthInterceptor 对齐）。 */
final class ClientBasicAuth {

    static final String HEADER = "authorization";

    private ClientBasicAuth() {}

    static String headerValue(String username, String password) {
        String raw = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
