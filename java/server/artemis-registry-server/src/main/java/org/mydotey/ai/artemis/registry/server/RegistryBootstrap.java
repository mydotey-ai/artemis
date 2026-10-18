package org.mydotey.ai.artemis.registry.server;

import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.registry.application.ReadinessGate;
import org.mydotey.ai.artemis.registry.application.RegistryService;
import org.springframework.beans.factory.InitializingBean;

/**
 * 启动编排（架构 §4.1 三分支，v0.1 落地 ②/③）：context 刷新早期（普通 bean 初始化阶段，
 * 先于 gRPC server 绑定）回放快照 → 就绪——正常路径 readiness interceptor 永远放行。
 * 回放无效（缺失/损坏/超期）不阻断：restoreFromSnapshot 内部已按空表启动处置。
 * v0.2 集群复制在位后在 markReady 前插入分支 ①（有已就绪 peer → 全量同步，失败定期重试）。
 */
@Slf4j
public final class RegistryBootstrap implements InitializingBean {

    private final RegistryService registryService;
    private final ReadinessGate readinessGate;

    public RegistryBootstrap(RegistryService registryService, ReadinessGate readinessGate) {
        this.registryService = registryService;
        this.readinessGate = readinessGate;
    }

    @Override
    public void afterPropertiesSet() {
        int restored = registryService.restoreFromSnapshot();
        readinessGate.markReady();
        log.info("registry bootstrap complete, restored {} instances, server ready", restored);
    }
}
