package org.mydotey.ai.artemis.registry.domain;

import java.time.Duration;

/**
 * 自我保护参数（语义继承原产品证据，legacy §2.3）：window 续约滑动窗口、factor 阈值因子、
 * minCount 启用下限（窗口历史峰值未达 minCount 不判定——小规模集群/冷启动不误保护）。
 * 默认值由装配层提供（10s / 0.85 / 50）。
 */
public record SelfProtectionConfig(Duration window, double factor, int minCount) {}
