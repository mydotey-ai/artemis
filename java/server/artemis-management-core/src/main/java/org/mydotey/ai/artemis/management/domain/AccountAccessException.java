package org.mydotey.ai.artemis.management.domain;

/**
 * 账户存储访问失败（认证后端不可用）；code 为稳定字符串，供接入层映射 HTTP 状态。
 */
public final class AccountAccessException extends RuntimeException {

    public static final String ACCOUNT_STORE_UNAVAILABLE = "ACCOUNT_STORE_UNAVAILABLE";

    private final String code;

    public AccountAccessException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
