package org.mydotey.ai.artemis.management.application;

import org.mydotey.ai.artemis.management.domain.ServiceKey;

/**
 * 服务列表项：registry 事实计数 + 当前摘除记录数（记录可能含未注册实例）。
 */
public record ServiceView(ServiceKey key, int instanceCount, int ejectedCount) {}
