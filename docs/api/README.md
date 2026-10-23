# API 文档索引

| 文档 | 状态 | 说明 |
|---|---|---|
| [registry-query-api.md](registry-query-api.md) | v0.1 | registry 内部只读查询 gRPC 契约（management-service 消费，非 SDK 公开 API） |
| [discovery-query-api.md](discovery-query-api.md) | v0.3 | discovery 查询 gRPC API（投影选择器查询） |
| [discovery-subscription-api.md](discovery-subscription-api.md) | v0.3 | discovery 订阅推送 gRPC API（推拉结合增量流，ADR-019：确认/基线/视图语义翻译/diff 续推/慢消费者断流） |
| [node-status-api.md](node-status-api.md) | v0.2 | registry 对外节点状态 gRPC API（节点就绪态、成员端点与后台探测观测态；SDK 节点列表来源） |
| [admin-api.md](admin-api.md) | v0.1 | management-service Admin 与状态 HTTP API（实例列表/详情/摘除/恢复、租约/配置排障面、有效视图语义） |
| [sdk-java.md](sdk-java.md) | v0.1 | Java 8 注册侧 SDK：公开 API、SCF 配置项、fire-and-forget 语义、生命周期、Micrometer 指标与 Spring Boot starter 自动装配 |
