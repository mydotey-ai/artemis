package org.mydotey.ai.artemis.management.domain;

/**
 * 逻辑实例存储访问失败；code 为稳定字符串，供接入层映射 HTTP 状态。
 */
public final class LogicalInstanceAccessException extends RuntimeException {

    public static final String LOGICAL_INSTANCE_STORE_UNAVAILABLE = "LOGICAL_INSTANCE_STORE_UNAVAILABLE";

    private final String code;

    public LogicalInstanceAccessException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
