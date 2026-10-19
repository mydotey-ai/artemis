package org.mydotey.ai.artemis.management.domain;

/**
 * 下游 registry 查询失败；code 为稳定字符串，供接入层映射 HTTP 状态。
 */
public final class RegistryQueryException extends RuntimeException {

    public static final String REGISTRY_UNAVAILABLE = "REGISTRY_UNAVAILABLE";
    public static final String REGISTRY_ERROR = "REGISTRY_ERROR";

    private final String code;

    public RegistryQueryException(String code, String message) {
        this(code, message, null);
    }

    public RegistryQueryException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
