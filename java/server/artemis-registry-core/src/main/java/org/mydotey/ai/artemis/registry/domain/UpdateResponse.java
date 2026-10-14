package org.mydotey.ai.artemis.registry.domain;

/** update 响应（status + data 规范形式）。 */
public record UpdateResponse(ResponseStatus status, UpdateResponseData data) {}
