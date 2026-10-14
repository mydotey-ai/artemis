package org.mydotey.ai.artemis.registry.domain;

/** 操作结果状态：ack 整体判定 + FAIL 时代表性错误（SUCCESS 不设）。 */
public record ResponseStatus(Ack ack, ErrorInfo error) {}
