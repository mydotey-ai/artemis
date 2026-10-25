package org.mydotey.ai.artemis.management.server.http;

/**
 * 内存态不支持改密（ADR-021 双轨：凭证事实源是配置）；接入层映射 409。
 */
final class MemoryModePasswordChangeException extends RuntimeException {

    MemoryModePasswordChangeException() {
        super("password change is only available in database mode; "
                + "credentials come from configuration in memory mode");
    }
}
