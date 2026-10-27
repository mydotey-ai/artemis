# Phase 1 测试：TLS 传输安全

版本: 1.0    更新时间: 2026-10-27

## 测试方案

形态：单测（配置解析与校验、SSL 上下文构建）+ IT（进程内多实例集群语义测试，TLS 全链路）。执行入口 `java/scripts/dev-test.sh`（模式定义与形态↔模式映射见 ⑤ 节，此处不复制）；本 phase 无新形态，脚本不变。

**证书 fixture**：测试工具类以 `${java.home}/bin/keytool` 生成自签 PKCS12（SAN = `dns:localhost,ip:127.0.0.1`），Java 侧从 KeyStore 导出 PEM（cert-chain / PKCS#8 private-key / CA cert）落临时目录——一次生成，registry 外部、peer、discovery、management 各写一份证书文件（同一 CA 签发语义由 keytool 自签近似，客户端 trust 锚 = 服务端证书本身）。无新增依赖（不引 BouncyCastle/openssl）。

**IT 拓扑**：registry（外部 gRPC+HTTP TLS + 内部 peer TLS）× 1、discovery（外部 gRPC+HTTP TLS + 上游 TLS）× 1、management（gRPC+HTTP TLS）× 1，全链路进程内拉起，SDK 以 `artemis.client.tls.*` 接入。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_fail_fast_when_tls_server_enabled_but_paths_missing | `TlsServerSettings` enabled=true 且 certChainPath/privateKeyPath 为空 → 启动即抛，报键名 | 单测 | 通过 |
| should_fail_fast_when_tls_cert_files_unreadable | enabled=true 且路径文件不存在/不可读 → fail-fast（server 与 trustCa 两侧） | 单测 | 通过 |
| should_not_validate_paths_when_tls_disabled | enabled=false 且路径项为空/非法 → 构造成功 | 单测 | 通过 |
| should_build_ssl_contexts_from_valid_pem | 合法 PEM cert+key / CA → server/client SslContext 构建成功；坏 PEM → 抛异常带路径 | 单测 | 通过 |
| should_register_heartbeat_and_subscribe_over_tls | 全链路 TLS：SDK 注册/心跳/更新/反注册走 registry 外部 gRPC TLS，订阅/查询走 discovery 外部 gRPC TLS，HTTP 前置寻址走 https | IT | 通过（RegistryTlsIT#register + DiscoveryTlsIT，SDK 订阅侧由 discovery TLS query stub 覆盖——discovery-server 无 artemis-client 测试依赖，SDK 共用 ClientTls 由 registry 侧覆盖） |
| should_replicate_and_project_over_tls_peer_channel | registry 内部 peer 端口 TLS：discovery 投影订阅/bootstrap 经 TLS 拉通（服务实例可见于 discovery 侧） | IT | 通过（RegistryTlsIT 双节点复制 + DiscoveryTlsIT 投影可见） |
| should_query_facts_and_stream_rules_over_tls | management→registry 只读查询与 discovery→management 规则流/逻辑实例流经 TLS 拉通（规则下发生效） | IT | 通过（DiscoveryTlsIT：规则发布生效 + /admin/v1/services 查询 200） |
| should_reject_tls_client_without_trust_anchor | 客户端未配/错配 trustCaPath → 连接失败（UNAVAILABLE），fail-fast 配置校验独立覆盖 | IT | 通过（RegistryTlsIT：明文与默认信任库两路均 UNAVAILABLE；fail-fast 由单测覆盖） |
| should_keep_plaintext_by_default | 全部 TLS 键缺省：既有全链路（明文）行为不变（既有 IT 全量回归即覆盖，此处显式断言默认配置无 TLS 属性参与） | 单测 | 通过（ClientTlsTest 默认明文 + 全量回归既有 IT 无回归） |

## 验证记录（⑨ 填）

自动化验证（工作流执行）：

| 命令 | 结果 |
|---|---|
| `java/scripts/dev-test.sh all`（⑤ 首跑） | BUILD SUCCESS，12 模块 0 失败（2026-10-27） |
| `mvn -f java/pom.xml -pl artemis-registry-server failsafe:integration-test -Dit.test=RegistryTlsIT` | 3/3 通过（SDK over TLS + peer 复制 + 拒绝用例） |
| `mvn -f java/pom.xml -pl artemis-discovery-server failsafe:integration-test -Dit.test=DiscoveryTlsIT` | 1/1 通过（三服务全 TLS 拓扑） |
| `java/scripts/dev-test.sh all`（⑧ 回归，review 修复后） | BUILD SUCCESS，13 模块 0 失败（2026-10-27） |

人工验证（用户执行）：待用户记录（所做检查 / 试用与结论）。

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| ⑤ 首跑 | all | 全 reactor（spotless + enforcer + 单测 + 全部 IT） | 通过（BUILD SUCCESS，12 模块 0 失败，2026-10-27） |
| ⑧ 回归（review 修复后） | all | 全 reactor | 通过（BUILD SUCCESS，13 模块 0 失败，2026-10-27） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.2 | 2026-10-27 | 验证/回归记录填齐（⑧ 全绿） |
| 1.1 | 2026-10-27 | 用例结果填齐（⑤ 首跑全绿） |
| 1.0 | 2026-10-27 | 测试设计定稿 |
