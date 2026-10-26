package org.mydotey.ai.artemis.discovery.domain;

/**
 * 规则源状态持有者（D-A5）：同步引擎单写、管理查询无锁读（volatile）。初始 DISABLED =
 * 未配置 management；配置后由同步器推进状态。phase-5：appliedVersion 随规则应用推进
 * （-1 = 尚未应用；内存态 management 重启归零后重新对齐——快照全量替换语义）。
 */
public final class ManagementStatus {

    private volatile ManagementState state = ManagementState.DISABLED;
    private volatile long appliedVersion = -1;

    public ManagementState state() {
        return state;
    }

    /** 已应用规则集版本；-1 = 尚未应用任何版本。 */
    public long appliedVersion() {
        return appliedVersion;
    }

    public void update(ManagementState state) {
        this.state = state;
    }

    public void updateAppliedVersion(long appliedVersion) {
        this.appliedVersion = appliedVersion;
    }
}
