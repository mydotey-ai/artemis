package org.mydotey.ai.artemis.registry.application;

/** 错误码：契约注册表内稳定字符串（只增不改）。 */
public final class ErrorCodes {

    /** 参数非法（契约注册表唯一已分配项：空 namespace/service/instanceId）。 */
    public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";

    private ErrorCodes() {}
}
