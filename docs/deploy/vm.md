# VM 标准安装包部署

版本: 1.1    更新时间: 2026-10-30

tar.gz 标准安装包 + systemd 维护的虚拟机部署形态（架构 §2.3 形态 I-b）。包内服务端为 all-in-one fat jar，形态语义（端口矩阵、治理链默认闭合、DB 双轨）见 [all-in-one 文档](../api/all-in-one.md)——安装包只是交付载体，不改变配置出口。

## 构建

```bash
java/scripts/build-dist.sh
# 产物：build/artemis-<version>.tar.gz（结构：bin/ lib/ data/ artemis.properties artemis.service）
```

模板仓内化于 [deploy/vm/](../../deploy/vm/)：`bin/app.sh`（运维脚本）、`artemis.service`（systemd unit）、`artemis.properties`（全注释配置模板）。

## 安装流程

```bash
# 1. 解包到安装目录（示例 /opt，解出 /opt/artemis）
sudo tar -xzf artemis-<version>.tar.gz -C /opt

# 2.（建议）专用账户
# sudo useradd --system --home-dir /opt/artemis artemis && sudo chown -R artemis:artemis /opt/artemis

# 3. 配置（可选）：编辑 /opt/artemis/artemis.properties（组合端口/认证键见 all-in-one 文档；
#    接 DB 用标准环境变量 SPRING_DATASOURCE_URL 等，建议经 unit Environment 注入）

# 4. 安装 systemd 服务（模板路径按实际安装目录实例化）
sudo /opt/artemis/bin/app.sh install
sudo systemctl start artemis
```

`app.sh install` 写入 `/etc/systemd/system/artemis.service`（`SYSTEMD_UNIT_DIR` 可覆盖，rootless 测试用）并 `enable`。

## 运维命令

| 命令 | 说明 |
|---|---|
| `app.sh run` | 前台运行（systemd `ExecStart` 即此；`exec` 替换 shell，信号直达 JVM） |
| `app.sh start` / `stop` / `restart` / `status` | 后台手跑生命周期（stop = SIGTERM 优雅停机，上限 90s 与 unit `TimeoutStopSec` 对齐） |
| `app.sh logs` | 跟随日志（journalctl 优先；手跑模式兜底 `console.log`） |
| `app.sh version` | 版本与构建时间 |
| `app.sh install` / `uninstall` | systemd 服务装卸 |

日志为结构化 JSON、stdout 输出：systemd 路径进 journal（`journalctl -u artemis`），手跑路径进 `console.log`。

## 配置外置

- **包根 `artemis.properties`**：SCF 工作目录 source（`WorkingDirectory=/opt/artemis`），组合端口（`artemis.dist.*`）与认证键模板内见注释；修改重启生效。
- **`JAVA_OPTS`**：环境变量注入（unit 内 `Environment=JAVA_OPTS=-Xms512m -Xmx2g` 或 shell 前缀），模板不设默认堆参数。
- **DB（可选）**：`SPRING_DATASOURCE_URL` 等标准键（unit `Environment=` 或 `/etc/artemis.env` 自行集成），仅 management 消费。
- **数据目录**：registry 快照 `<安装目录>/data/registry-snapshot.bin`。

## 升级

tar.gz 无版本目录嵌套，覆盖解包升级需先 `systemctl stop artemis`；`artemis.properties`、`data/` 为用户数据，升级前备份。多节点滚动升级口径见[运维手册 §1](operations.md#1-启停与升级)。

## 冒烟

```bash
java/scripts/smoke-deploy.sh vm   # 打包 + 解包结构断言 + app.sh start/stop 生命周期 + SDK 注册订阅走通
```

systemd install/uninstall 流程需 root/systemd 环境，人工执行（用例 `should_install_systemd_unit_when_install_command`）。

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.1 | 2026-10-30 | review 修订：滚动升级引用改指运维手册（原文误指「路线图 v1.0 phase-5」工程文档） |
| 1.0 | 2026-10-28 | v1.0 phase-3 首版 |
