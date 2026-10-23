package org.mydotey.ai.artemis.discovery.domain;

/** per-service 视图校验和（对账表条目，D-F1；checksum 规则见 ProjectionChecksum）；不可变。 */
public record ServiceChecksum(ServiceKey key, int checksum) {}
