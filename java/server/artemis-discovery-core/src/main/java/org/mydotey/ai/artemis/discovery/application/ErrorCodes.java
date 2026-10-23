package org.mydotey.ai.artemis.discovery.application;

/** 错误码：契约注册表内稳定字符串（单一来源 proto-contract §2，只增不改）。 */
public final class ErrorCodes {

    /** 参数非法：查询指定 services 含空白 namespace/service。 */
    public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";

    private ErrorCodes() {}
}
