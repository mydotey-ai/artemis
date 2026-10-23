package org.mydotey.ai.artemis.discovery.domain;

/** 上游新鲜度条目（拉取响应的状态元信息，D-F4/D-F5）；不可变。 */
public record UpstreamStatus(String cluster, UpstreamState state) {}
