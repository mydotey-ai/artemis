package org.mydotey.ai.artemis.management.domain;

/**
 * registry 配置项状态（敏感值已由 registry 脱敏）。
 */
public record ConfigProperty(
        String key, String value, String source, boolean isStatic, String defaultValue, String doc) {}
