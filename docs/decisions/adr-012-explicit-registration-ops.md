# ADR-012：注册模型显式四操作化

版本: 1.2    更新时间: 2026-10-12

## 状态

Accepted（2026-10-11，v0.1 phase-1 契约设计 review 定案；变更架构 v1.5 §4.1「免显式注册状态机」表述）

## 背景

架构 v1.5 将资产 #1（心跳即注册幂等对账）落地为「心跳唯一 rpc，注册/反注册为服务端 diff 派生事件，免显式注册状态机」。契约设计 review 中对照原产品取证重审：原产品实际为 register/heartbeat/unregister 三显式 API + WS 全量心跳 + miss 补注册环（legacy features.md:95/127/349、registry-lease-logic.md:148-174），显式操作的即时语义（显式下线 ~1s 生效 vs 被动等 TTL ~21s）与 API 面经过 10 万级验证；纯 diff 派生模型反而偏离原产品形态。

## 决策

注册模型 = **显式四操作 + 心跳全量上报**：SDK 本地实例集保持唯一事实源，register / deregister / update 先更新本地集再上报、服务端按语义处理——register 无条件覆盖发 NEW；deregister 意图幂等（目标缺席即达成）、不受自我保护拦截、发 DELETE；update 幂等 upsert（命中覆盖发 UPDATE，缺席按 NEW 事件建租约）。心跳保持进程级全量实例集幂等上报（gRPC bidi-stream，逐条即时 ack）与复制：逐实例续约 + 缺席自动注册（upsert）；不做集合 diff，上报中缺席的实例由租约 TTL 过期清理最终一致。

## 理由

- 保留资产 #1 的实质（本地集唯一事实源、全量幂等对账、断网重连自动收敛、稳态零变更事件），自愈性质不降级。
- 显式操作给出即时、可追踪的语义（显式下线不等 TTL；update 数据修正语义明确），贴近原产品已验证的 API 面。
- op 失败无需 SDK 重试状态机——register/update 丢失由心跳 upsert 即时自愈，deregister 丢失由 TTL 最终收敛；原产品「miss 报告 → 客户端补注册」跨网络环由服务端 upsert 替代，少一次网络往返。

## 否决方案

- 心跳唯一 rpc、注册/反注册全为 diff 派生事件（架构 v1.5 原案）：丢失显式语义与即时性，且偏离原产品形态。
- 照搬原产品三 API + miss 补注册环：对账要跨网络往返才收敛，复杂度更高。

## 后果

- registry.v1 契约四 rpc（Heartbeat/Register/Deregister/Update），响应统一 status + data 规范（proto 契约规范 §2）。
- deregister 不受自我保护拦截是显式重申的原产品边界（registry-lease-logic.md:205），自我保护实现须遵守。
- v0.1 phase-2 内涵为四操作语义 + 心跳续约/缺席补注册（无集合 diff）+ 租约过期清理。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.2 | 2026-10-12 | 心跳语义精化：不做集合 diff，缺席自动注册 + TTL 最终一致 |
| 1.1 | 2026-10-12 | 心跳 rpc 形态更正为 gRPC bidi-stream（逐条 ack） |
| 1.0 | 2026-10-11 | v0.1 phase-1 设计 review 定案成文 |
