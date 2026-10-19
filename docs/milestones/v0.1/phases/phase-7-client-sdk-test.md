# Phase 7 测试：Java 8 SDK 注册侧

版本: 1.8    更新时间: 2026-10-19

## 测试方案

两组形态，对应既有模式映射（单测 → surefire `*Test`、集群语义 → failsafe `*IT`），无新模式扩展；测试基础设施变化（非形态）随 ④ 落地：

1. **SDK 单测（client 模块，fork JDK 8 JVM——同时验证 Java 8 运行时兼容）**：进程内裸 gRPC 假服务（`ServerBuilder` + 测试 `RegistryServiceImplBase`，随机端口，可脚本化断流/拒绝）驱动 SDK 协议行为；纯逻辑（实例集、快照编解码、选址）无服务直测。两个硬边界决定不起真 registry-server：client test 依赖服务端模块撞 enforcer 硬约束；JDK 8 JVM 无法加载 release=25 服务端类。
2. **端到端 IT（registry-server 模块，`ClientSdkIT`，failsafe）**：registry-server test 依赖 `artemis-client`，`@SpringBootTest` 起 真 registry 服务（随机端口），SDK 驱动端到端链路（wire + 认证 + 租约调度），服务端侧注入设施断言（`RegistryGrpcIT` 同款）。

执行入口 `java/scripts/dev-test.sh`（unit / integration / all）。fire-and-forget 语义下断言一律经服务端（fake/真实）观察上报与自愈，无返回值断言；可观测经 **Micrometer**（SimpleMeterRegistry 注入断言，如 retry 用例的 `operation.failure` counter）；生产注入走 `newClient(properties, meterRegistry)`，starter（phase-8）自动接 Boot `MeterRegistry`。

## 用例清单

| 用例 | 场景 | 形态 | 结果 |
|---|---|---|---|
| should_reportRegister_when_register | register 异步上报到达假服务（内容/状态正确），心跳全量携带 | 单测 | pass |
| should_selfHealViaHeartbeat_when_reportFails | 上报失败 void 不抛、本地集不回滚，即时补投 + 心跳全量持续携带 | 单测 | pass |
| should_removeFromLocalSet_when_deregister | deregister 异步上报到达、后续心跳不再含 | 单测 | pass |
| should_reportUpdate_when_update | update 异步上报到达，心跳收敛到新内容（host 变更） | 单测 | pass |
| should_retryReport_when_transientFailure | 瞬态失败有界重试（多次到达服务端），恢复后成功即停；Micrometer `operation.failure` counter 断言 | 单测 | pass |
| should_notThrow_when_serverUnavailable | 服务不可达时操作吞掉失败（重试 → WARN + metrics + 补投内化），用户无感 | 单测 | pass |
| should_rejectOperation_when_notStarted | start 前调用操作抛 IllegalStateException | 单测 | pass |
| should_sendMonotonicSeqAndTrackAck_when_heartbeat | 心跳 seq 单调递增、ack echo seq 被记录 | 单测 | pass |
| should_reconnectWithBackoff_when_streamBroken | 假服务断流后 SDK 退避重连、心跳恢复上报 | 单测 | pass |
| should_heartbeatImmediately_when_started | start 即首跳（不等首个周期） | 单测 | pass |
| should_stopHeartbeatAndDeregisterAll_when_close | close：先停心跳 → 全量 deregister → 关资源（服务收到注销且此后无心跳） | 单测 | pass |
| should_selectRandomlyAndSwitch_when_reconnect | 多地址随机选址（select 动态重读）、断流重连恢复上报 | 单测 | pass |
| should_sendBasicAuthHeader_when_credentialsConfigured | 配置凭证后请求携带 authorization Basic 头 | 单测 | pass |
| should_notSendAuthHeader_when_credentialsAbsent | 凭证未配置（默认空）不发认证头，裁决归服务端 | 单测 | pass |
| should_keepSending_when_ackLost | ack 丢失（收而不回）时心跳不停发、不阻塞等待 | 单测 | pass |
| should_registerAndBecomeVisible_when_sdkStarted | SDK 注册实例经真服务端在注册表可见 | 集成 | pass |
| should_renewLease_when_heartbeatPeriodic | 持续心跳下租约续期、实例不过期 | 集成 | pass |
| should_coverInstance_when_updateCalled | SDK update 后服务端实例内容更新 | 集成 | pass |
| should_deregisterImmediately_when_close | close 优雅下线后服务端实例即刻移除 | 集成 | pass |
| should_reject_when_wrongCredentials | 错误凭证不抛不返回，服务端拒绝 → 实例不可见 | 集成 | pass |

## 验证清单（人工，⑨ 填）

准备（一次）：

```bash
# classpath（SDK + 依赖）
mvn -f java/client/artemis-client/pom.xml dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt -q
CP="java/client/artemis-client/target/classes:$(cat /tmp/cp.txt)"
```

demo 单文件（`/tmp/ArtemisSdkDemo.java`，模式：`default` 时间线 / `hold` 长跑 / `bad` 错凭证）：

```java
import java.util.LinkedHashMap;
import java.util.Map;
import org.mydotey.ai.artemis.client.ArtemisClient;
import org.mydotey.ai.artemis.client.ArtemisClients;
import org.mydotey.ai.artemis.client.ServiceId;
import org.mydotey.ai.artemis.client.ServiceInstance;

public class ArtemisSdkDemo {
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "default";
        System.setProperty("artemis.client.serverAddresses", "localhost:9090");
        System.setProperty("artemis.client.auth.username", "artemis");
        System.setProperty("artemis.client.auth.password", "bad".equals(mode) ? "wrong" : "artemis");
        ArtemisClient client = ArtemisClients.newClient();
        client.start();
        client.register(instance("10.0.0.1"));
        System.out.println("[demo] register sent (fire-and-forget)");
        if ("bad".equals(mode)) {
            Thread.sleep(1_000); // 上报异步：等一次尝试到达服务端
            client.close();
            return;
        }
        if ("hold".equals(mode)) {
            Thread.sleep(300_000);
            client.close();
            return;
        }
        Thread.sleep(35_000); // > 服务端默认 TTL 30s：验证默认参数（心跳 5s）下持续续约
        client.update(instance("10.0.0.9"));
        System.out.println("[demo] update sent (fire-and-forget)");
        Thread.sleep(3_000);
        System.out.println("[demo] close (graceful deregister)");
        client.close();
    }

    static ServiceInstance instance(String host) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("version", "v1");
        return ServiceInstance.newBuilder()
                .service(ServiceId.newBuilder().namespace("default").service("order-service").build())
                .instanceId("i-1")
                .host(host)
                .port(8080)
                .weight(100)
                .metadata(metadata)
                .build();
    }
}
```

| # | 命令 / 步骤 | 预期 | 结论 |
|---|---|---|---|
| 1 | `java/scripts/dev-test.sh all` | BUILD SUCCESS；client 21 单测、registry 25 IT（含 ClientSdkIT 5）全绿 |通过 |
| 2 | 构建并启动两服务：`mvn -f java/pom.xml -pl :artemis-registry-server,:artemis-management-server -am package -DskipTests`；终端 A：`java -jar java/server/artemis-registry-server/target/artemis-registry-server-0.1.0-SNAPSHOT-exec.jar`；终端 B：`java -jar java/server/artemis-management-server/target/artemis-management-server-0.1.0-SNAPSHOT-exec.jar` | 两进程正常启动（registry 9090、management 8081） |通过 |
| 3 | 编译 demo：`javac -cp "$CP" -d /tmp /tmp/ArtemisSdkDemo.java`；后台运行 `java -cp "/tmp:$CP" ArtemisSdkDemo default` | 立即打印 `[demo] register sent (fire-and-forget)`（void 无返回值） |通过 |
| 4 | demo 运行满 35s 期间（已过服务端默认 TTL 30s）任意时刻：`curl -s -u artemis:artemis localhost:8081/admin/v1/services` | `i-1` 实例持续可见（默认心跳 5s 续约生效，未过期） |通过 |
| 5 | demo 打印 `[demo] update sent` 后：同上 curl | 实例 `host` 变为 `10.0.0.9` |通过 |
| 6 | demo 打印 `[demo] close` 后：同上 curl | 实例即刻消失（优雅下线显式注销，不等 TTL） |通过 |
| 7 | 断流重连：`java -cp "/tmp:$CP" ArtemisSdkDemo hold` 后台跑；kill registry 进程并重新 `java -jar ...exec.jar` 起回；等 ~10s 后 curl | 实例自动恢复可见（SDK 断流退避重连 + 重连即全量心跳自愈） |通过 |
| 8 | 错凭证：`java -cp "/tmp:$CP" ArtemisSdkDemo bad` | 打印 `register sent`，进程正常退出不抛异常栈；实例始终不可见（服务端 UNAUTHENTICATED，SDK 内部 WARN + 指标计数） |通过 |

## 回归记录（⑧ 填，经 dev-test.sh）

| 轮次 | 模式 | 范围 | 结果 |
|---|---|---|---|
| ⑤ 首跑 | all | 全 reactor | 全绿（client 单测 19/19 fork JDK 8、ClientSdkIT 5/5、registry 回归 89+22 单测/19 IT、management 回归 16 单测/11 IT） |
| ⑧ 回归 | all | 全 reactor | 全绿（review 15 findings 修复 + 2 新用例后：client 21 单测、registry 89+22 单测/25 IT、management 16 单测/11 IT，BUILD SUCCESS） |
| ⑧ 回归 R2 | all | 全 reactor | 全绿（用户三轮修订后：凭证默认空 + 配置化创建 + fire-and-forget 重塑；client 23 单测、ClientSdkIT 5/5） |
| ⑧ 回归 R3 | all | 全 reactor | 全绿（/code-review 第二轮 15 findings 修复后：client 24 单测（含 Micrometer counter）、registry 25 IT，BUILD SUCCESS） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.8 | 2026-10-19 | /code-review 第二轮修复：demo 适配 void API（编译修复），预期输出同步 |
| 1.7 | 2026-10-19 | 用户修订：Micrometer 替换自建 metrics 接口（SimpleMeterRegistry 断言入 retry 用例） |
| 1.6 | 2026-10-19 | 用户修订：操作级重试用例（should_retryReport_when_transientFailure） |
| 1.5 | 2026-10-19 | 用户修订：fire-and-forget 重塑（用例断言改经服务端观察）+ 配置化创建与凭证默认空联动修订 |
| 1.4 | 2026-10-19 | 用户修订：凭证默认空（未配置不发认证头）；demo 同时补配置化创建（1.3 demo 未落盘的回归） |
| 1.3 | 2026-10-19 | 用户修订：SDK 配置化创建（newClient + 系统属性），demo 与验证步骤同步 |
| 1.2 | 2026-10-19 | code-review 修复后补 2 用例（多址换址重连、ack 丢失不停发），结果列置 pass |
| 1.1 | 2026-10-19 | review 修订：删注册侧快照 5 用例（快照归发现侧 v0.3），close 用例改停心跳+注销断言 |
| 1.0 | 2026-10-19 | 测试文档骨架 |
