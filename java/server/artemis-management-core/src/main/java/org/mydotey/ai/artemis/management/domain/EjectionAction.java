package org.mydotey.ai.artemis.management.domain;

/**
 * 摘除操作动作：下线（EJECT，可叠加）与恢复（RECOVER，反向记录对消）。
 */
public enum EjectionAction {
    EJECT,
    RECOVER
}
