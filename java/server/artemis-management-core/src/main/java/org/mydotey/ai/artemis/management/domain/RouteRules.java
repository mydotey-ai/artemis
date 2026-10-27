package org.mydotey.ai.artemis.management.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 路由规则域约束校验（纯函数，内存/DB 两态共享语义，单测一处覆盖）。
 * 信任边界调用（Controller 组装 domain 后一次校验），违规抛 IllegalArgumentException。
 */
public final class RouteRules {

    public static final int MAX_NAMESPACE_LENGTH = 255;
    public static final int MAX_SERVICE_LENGTH = 255;
    public static final int MAX_NAME_LENGTH = 255;
    /** name 进 URL 路径段，限安全字符集避免编码歧义。 */
    public static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

    public static final int MAX_TARGETS = 10;
    public static final int MAX_SELECTOR_KEYS = 8;
    public static final int MAX_SELECTOR_KEY_LENGTH = 128;
    public static final int MAX_SELECTOR_VALUE_LENGTH = 512;
    public static final int MAX_WEIGHT = 10_000;

    /** 保留名：canary 专属规则组（phase-4 预置派生独占，CRUD 通道创建拒绝）。 */
    public static final String RESERVED_NAME_CANARY = "canary";

    private RouteRules() {}

    public static void validate(RouteRule rule) {
        validate(rule, false);
    }

    /** allowReservedName = canary 派生通道独占保留名（保留名约束是 CRUD 输入约束，非 domain 不变量）。 */
    public static void validate(RouteRule rule, boolean allowReservedName) {
        if (rule == null) {
            throw new IllegalArgumentException("route rule required");
        }
        ServiceKey service = rule.service();
        if (service == null) {
            throw new IllegalArgumentException("service required");
        }
        requireName(service.namespace(), "namespace", MAX_NAMESPACE_LENGTH);
        requireName(service.service(), "service", MAX_SERVICE_LENGTH);
        requireName(rule.name(), "name", MAX_NAME_LENGTH);
        if (!NAME_PATTERN.matcher(rule.name()).matches()) {
            throw new IllegalArgumentException("name must match " + NAME_PATTERN.pattern());
        }
        if (!allowReservedName && RESERVED_NAME_CANARY.equals(rule.name())) {
            throw new IllegalArgumentException("name is reserved: " + RESERVED_NAME_CANARY);
        }
        if (rule.status() == null) {
            throw new IllegalArgumentException("status required");
        }
        List<RouteRuleTarget> targets = rule.targets();
        if (targets == null || targets.isEmpty() || targets.size() > MAX_TARGETS) {
            throw new IllegalArgumentException("targets must contain 1.." + MAX_TARGETS + " entries");
        }
        Set<Map<String, String>> selectors = new HashSet<>();
        for (RouteRuleTarget target : targets) {
            validateTarget(target);
            // 等值重复拒绝：selector 是目标身份（update 的生效权重继承按等值匹配，
            // 重复目标会破坏继承唯一性；「不要求互斥」指命中集合可重叠，不涉等值）
            if (!selectors.add(target.selector())) {
                throw new IllegalArgumentException("duplicate target selector: " + target.selector());
            }
        }
    }

    /** ACTIVE 规则集（规则流内容，phase-2；流上 INACTIVE 化由 deleted 事件表达）。 */
    public static List<RouteRule> activeOf(List<RouteRule> rules) {
        return rules.stream()
                .filter(rule -> rule.status() == RouteRuleStatus.ACTIVE)
                .toList();
    }

    private static void validateTarget(RouteRuleTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("target required");
        }
        Map<String, String> selector = target.selector();
        if (selector == null) {
            throw new IllegalArgumentException("selector required (empty map = fallback target)");
        }
        if (selector.size() > MAX_SELECTOR_KEYS) {
            throw new IllegalArgumentException("selector must contain at most " + MAX_SELECTOR_KEYS + " keys");
        }
        for (Map.Entry<String, String> entry : selector.entrySet()) {
            if (isBlank(entry.getKey()) || length(entry.getKey()) > MAX_SELECTOR_KEY_LENGTH) {
                throw new IllegalArgumentException(
                        "selector key must be 1.." + MAX_SELECTOR_KEY_LENGTH + " characters");
            }
            if (isBlank(entry.getValue()) || length(entry.getValue()) > MAX_SELECTOR_VALUE_LENGTH) {
                throw new IllegalArgumentException(
                        "selector value must be 1.." + MAX_SELECTOR_VALUE_LENGTH + " characters");
            }
        }
        if (target.weight() < 0 || target.weight() > MAX_WEIGHT) {
            throw new IllegalArgumentException("weight must be within 0.." + MAX_WEIGHT);
        }
        if (target.unreleasedWeight() < 0 || target.unreleasedWeight() > MAX_WEIGHT) {
            throw new IllegalArgumentException("unreleasedWeight must be within 0.." + MAX_WEIGHT);
        }
    }

    private static void requireName(String value, String field, int maxLength) {
        if (isBlank(value) || length(value) > maxLength) {
            throw new IllegalArgumentException(field + " must be 1.." + maxLength + " characters");
        }
    }

    /** 码点计量（Controller requireLength 同口径）：星体平面字符不受 UTF-16 代理对双计惩罚。 */
    private static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
