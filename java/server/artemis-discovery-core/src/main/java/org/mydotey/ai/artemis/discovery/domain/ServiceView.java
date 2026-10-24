package org.mydotey.ai.artemis.discovery.domain;

/** 管理面服务视图行（D-A6）：逐来源 cluster 的可见实例计数；不可变。 */
public record ServiceView(String cluster, String namespace, String service, int instanceCount) {}
