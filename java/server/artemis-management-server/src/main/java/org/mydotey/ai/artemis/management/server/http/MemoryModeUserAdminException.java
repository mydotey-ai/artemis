package org.mydotey.ai.artemis.management.server.http;

/** 内存态不支持用户管理（v1.0 phase-2：多账户仅 DB 态，凭证事实源是配置）；接入层映射 409。 */
final class MemoryModeUserAdminException extends RuntimeException {

    MemoryModeUserAdminException() {
        super("user administration is only available in database mode; "
                + "memory mode has a single configured administrator");
    }
}
