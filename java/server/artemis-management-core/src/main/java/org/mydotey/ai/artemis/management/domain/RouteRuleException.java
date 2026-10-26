package org.mydotey.ai.artemis.management.domain;

/**
 * 路由规则访问失败与资源状态；code 为稳定字符串，供接入层映射 HTTP 状态
 * （ALREADY_EXISTS → 409、NOT_FOUND → 404、RELEASE_NOT_FOUND → 404、
 * STORE_UNAVAILABLE → 503）。
 */
public final class RouteRuleException extends RuntimeException {

    public static final String ALREADY_EXISTS = "ROUTE_RULE_ALREADY_EXISTS";
    public static final String NOT_FOUND = "ROUTE_RULE_NOT_FOUND";
    /** 回滚源发布记录缺失或属于其它规则（phase-2）。 */
    public static final String RELEASE_NOT_FOUND = "ROUTE_RULE_RELEASE_NOT_FOUND";

    public static final String STORE_UNAVAILABLE = "ROUTE_RULE_STORE_UNAVAILABLE";

    private final String code;

    public RouteRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public RouteRuleException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
