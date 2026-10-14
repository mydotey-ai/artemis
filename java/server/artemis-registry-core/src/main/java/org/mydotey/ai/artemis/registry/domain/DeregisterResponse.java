package org.mydotey.ai.artemis.registry.domain;

/** deregister 响应（status + data 规范形式）。 */
public record DeregisterResponse(ResponseStatus status, DeregisterResponseData data) {}
