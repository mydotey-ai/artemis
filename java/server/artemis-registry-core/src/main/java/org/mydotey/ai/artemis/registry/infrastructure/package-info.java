/**
 * 基础设施层：领域端口的具体实现与技术关注（调度等）。依赖 domain（单向）；
 * 不承载领域语义、不做协议接入；未来：TTL 来源的 SCF 实现、集群复制的协议无关编排。
 */
package org.mydotey.ai.artemis.registry.infrastructure;
