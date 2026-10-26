# API 文档索引

| 文档 | 状态 | 说明 |
|---|---|---|
| [registry-query-api.md](registry-query-api.md) | v0.1 | registry 内部只读查询 gRPC 契约（management-service 消费，非 SDK 公开 API） |
| [discovery-query-api.md](discovery-query-api.md) | v0.3 | discovery 查询 gRPC API（投影选择器查询） |
| [discovery-subscription-api.md](discovery-subscription-api.md) | v0.3 | discovery 订阅推送 gRPC API（推拉结合增量流，ADR-019：确认/基线/视图语义翻译/diff 续推/慢消费者断流） |
| [discovery-reconciliation-api.md](discovery-reconciliation-api.md) | v0.3 | discovery 对账与新鲜度 gRPC API（Checksum 表拉取、canonical 规则单一来源 + golden vector、UpstreamStatus 语义） |
| [ejection-rule-stream-api.md](ejection-rule-stream-api.md) | v0.3 | management → discovery 摘除规则订阅流 gRPC 契约（非 SDK 公开 API：快照替换 + 增量、at-most-once、fail-open） |
| [logical-instance-stream-api.md](logical-instance-stream-api.md) | v0.4 | management → discovery 逻辑实例订阅流 gRPC 契约（非 SDK 公开 API：快照 + 增量、at-most-once、保留 cluster `logical` 投影合并、fail-open） |
| [node-status-api.md](node-status-api.md) | v0.2 | registry 对外节点状态 gRPC API（节点就绪态、成员端点与后台探测观测态；SDK 节点列表来源） |
| [admin-api.md](admin-api.md) | v0.4 | management-service Admin 与状态 HTTP API（实例列表/详情/四级摘除/恢复、逻辑实例 CRUD、账户、租约/配置排障面、有效视图语义） |
| [discovery-admin-api.md](discovery-admin-api.md) | v0.3 | discovery 只读发现管理面 HTTP API（订阅关系/服务视图/上游与规则源状态） |
| [discovery-nodes-api.md](discovery-nodes-api.md) | v0.3 | discovery 静态节点目录 HTTP API（SDK 前置寻址，ADR-020：`GET /cluster/nodes`、Basic、readiness 503） |
| [sdk-java.md](sdk-java.md) | v0.1 | Java 8 **注册侧** SDK：`RegistryClient` 公开 API、SCF 配置项、fire-and-forget 语义、生命周期、Micrometer 指标与 Spring Boot starter 自动装配 |
| [sdk-java-discovery.md](sdk-java-discovery.md) | v0.3 | Java 8 **发现侧** SDK：订阅推送/本地视图/磁盘快照/兜底拉取/地址容灾、`DiscoveryClient`/`Subscription` API、配置与指标、starter 自动装配 |
| [all-in-one.md](all-in-one.md) | v0.4 | all-in-one 单进程部署：registry + management + discovery 同 JVM 三上下文组合入口、治理链默认闭合、DB 双轨、端口表、`artemis.dist.*` 组合配置键与限制 |
