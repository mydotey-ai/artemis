/**
 * 应用层：用例编排（契约级校验/归一）+ dto（proto 生成消息）与领域模型互相转换（mapper/proto 门面）。
 * 依赖 domain（单向）；不依赖 Spring；proto 类型只出现在本包与 server 接入层的转换边界。
 */
package org.mydotey.ai.artemis.registry.application;
