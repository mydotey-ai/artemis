package org.mydotey.ai.artemis.client.internal;

/** 节点列表不可用（前置寻址未完成/无可用节点）：瞬态，重试寻址自愈。 */
final class NoAddressException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    NoAddressException(String message) {
        super(message);
    }
}
