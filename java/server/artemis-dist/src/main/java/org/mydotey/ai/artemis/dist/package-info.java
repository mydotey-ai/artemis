/**
 * all-in-one 单进程组合（v0.3 phase-9）：同 JVM 起 registry + discovery 两个独立 Spring 上下文，
 * 各自端口、网络互访不进程内直连（ADR-001）；单服务入口与打包不受影响。
 * 组合期端口经 {@code artemis.dist.*}（SCF，静态）配置，入口 {@code ArtemisAllInOneServer}。
 */
package org.mydotey.ai.artemis.dist;
