package org.mydotey.ai.artemis.management.domain;

import java.util.Map;

/**
 * 路由目标：selector 命中的实例集合分得权重。selector 为实例 metadata 等值匹配
 * （多键 AND，ADR-013 分组以 metadata 表达）；空 map = 兜底目标（承接原产品默认组语义）。
 * weight 生效权重（release 拷贝后）、unreleasedWeight 编辑中权重——两段式发布
 * （继承资产 §5-5，release 动作 v0.5 phase-2）。权重为非负相对值、比例语义，无总和约束。
 */
public record RouteRuleTarget(Map<String, String> selector, int weight, int unreleasedWeight) {}
