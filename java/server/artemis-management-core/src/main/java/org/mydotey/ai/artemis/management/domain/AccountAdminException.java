package org.mydotey.ai.artemis.management.domain;

/** 账户管理冲突语义（v1.0 phase-2）：接入层按 code 映射 404/409；存储不可用仍走 {@link AccountAccessException}。 */
public class AccountAdminException extends RuntimeException {

    public static final String NOT_FOUND = "ACCOUNT_NOT_FOUND";
    public static final String ALREADY_EXISTS = "ACCOUNT_ALREADY_EXISTS";
    public static final String LAST_ADMIN_REQUIRED = "LAST_ADMIN_REQUIRED";
    public static final String SELF_DELETE = "SELF_DELETE";

    private final String code;

    public AccountAdminException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
