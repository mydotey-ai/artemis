package org.mydotey.ai.artemis.registry.domain;

/** 心跳响应数据：ack 基准时间（注入时钟）+ seq 原样回填。 */
public record HeartbeatResponseData(long serverTimeMs, long seq) {}
