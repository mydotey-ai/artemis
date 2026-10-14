package org.mydotey.ai.artemis.registry.domain;

/** 错误：code 为契约注册表稳定字符串；message 人类可读排障描述。 */
public record ErrorInfo(String code, String message) {}
