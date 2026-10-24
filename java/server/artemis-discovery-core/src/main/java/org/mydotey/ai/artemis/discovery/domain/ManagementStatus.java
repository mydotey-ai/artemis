package org.mydotey.ai.artemis.discovery.domain;

/**
 * 规则源状态持有者（D-A5）：同步引擎单写、管理查询无锁读（volatile）。初始 DISABLED =
 * 未配置 management；配置后由同步器推进状态。
 */
public final class ManagementStatus {

    private volatile ManagementState state = ManagementState.DISABLED;

    public ManagementState state() {
        return state;
    }

    public void update(ManagementState state) {
        this.state = state;
    }
}
