package org.mydotey.ai.artemis.common.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * HTTP Basic 凭证编解码与常量时间比较（gRPC metadata 与 HTTP header 共用）。
 */
public final class BasicAuth {

    public static final String HEADER = "authorization";
    private static final String PREFIX = "Basic ";

    private BasicAuth() {}

    public static String headerValue(String username, String password) {
        String raw = username + ":" + password;
        return PREFIX + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 启用认证但凭证未完整配置时返回 null（调用方应拒绝请求，避免空凭证形成可猜测口令）。 */
    public static String headerValueOrNull(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return null;
        }
        return headerValue(username, password);
    }

    public static boolean matches(String actual, String expected) {
        if (actual == null) {
            return false;
        }
        // RFC 7617：scheme 大小写不敏感，scheme 与凭证间允许一个或多个空格
        String trimmed = actual.trim();
        if (!trimmed.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return false;
        }
        String actualToken = trimmed.substring(PREFIX.length()).trim();
        String expectedToken = expected.substring(PREFIX.length()).trim();
        return MessageDigest.isEqual(
                actualToken.getBytes(StandardCharsets.US_ASCII), expectedToken.getBytes(StandardCharsets.US_ASCII));
    }
}
