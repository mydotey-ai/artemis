package org.mydotey.ai.artemis.registry.domain;

/**
 * 配置项排障视图：键、值（敏感项已脱敏）、来源、动静类型、默认值与 doc。
 * source 为空表示未命中任何配置源（使用默认值）。
 */
public record ConfigProperty(
        String key, String value, String source, boolean isStatic, String defaultValue, String doc) {}
