package org.mydotey.ai.artemis.registry.domain;

/** 心跳响应（status + data 规范形式）。 */
public record HeartbeatResponse(ResponseStatus status, HeartbeatResponseData data) {}
