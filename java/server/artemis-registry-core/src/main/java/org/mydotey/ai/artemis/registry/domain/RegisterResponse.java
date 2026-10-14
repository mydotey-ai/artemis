package org.mydotey.ai.artemis.registry.domain;

/** register 响应（status + data 规范形式）。 */
public record RegisterResponse(ResponseStatus status, RegisterResponseData data) {}
