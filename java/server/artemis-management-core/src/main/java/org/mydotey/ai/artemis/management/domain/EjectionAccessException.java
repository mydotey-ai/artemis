package org.mydotey.ai.artemis.management.domain;

/**
 * 摘除/审计存储访问失败；code 为稳定字符串，供接入层映射 HTTP 状态。
 */
public final class EjectionAccessException extends RuntimeException {

    public static final String EJECTION_STORE_UNAVAILABLE = "EJECTION_STORE_UNAVAILABLE";

    private final String code;

    public EjectionAccessException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
