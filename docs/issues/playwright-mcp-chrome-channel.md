# Playwright MCP 不可用：chrome channel 路径不匹配

版本: 1.0    更新时间: 2026-10-08

> 目的：记录 playwright MCP 插件在本机（Deepin 23.1）无法启动浏览器的根因、诊断依据与修复方式，以及修复后的已知残留与踩过的坑。
> 定位：**环境 / 工具链问题**，与 Artemis 产品本身无关——仅因本仓库的架构图工作依赖该工具而登记。
> 结论：**已修复**（符号链接）；Chrome 经应用商店更新后需重做一次。

---

## 1. 现象

调用任意 `browser_*` 工具立即失败，从未成功启动过浏览器：

```text
Error: async initializeServer: Chromium distribution 'chrome' is not found at /opt/google/chrome/chrome
Run "npx playwright install chrome"
```

## 2. 根因

三层叠加，缺一不可：

| 层 | 事实 | 证据 |
|---|---|---|
| 插件配置 | 只跑 `npx @playwright/mcp@latest`，**不传任何参数** | `~/.claude/plugins/cache/claude-plugins-official/playwright/*/.mcp.json` |
| MCP 默认值 | 不传 `--browser` 时走 **`chrome` channel**——即*系统安装*的 Google Chrome，不是 Playwright 自带的 | 报错信息本身 |
| 路径解析 | chrome channel 在 Linux 上**只查 `/opt/google/chrome/chrome`**，无 `/usr/bin` 兜底 | `grep -r "/opt/google/chrome" playwright-core/lib/` 仅此一处 |

本机无系统 Chrome（`dpkg -l` 无记录、`/opt/google/chrome` 不存在），故启动失败。

## 3. 为什么自带的 Chromium 用不上

`~/.cache/ms-playwright/` 里一直有 Playwright 自带的 Chromium，但 `--browser` 的取值枚举是：

```text
--browser <browser>   browser or chrome channel to use,
                      possible values: chrome, firefox, webkit, msedge.
```

**没有 `chromium`**。所以 MCP 无法指向 bundled Chromium——这是 `@playwright/mcp` 的设计，不是配置疏漏。「装个浏览器就好」在本机之所以行不通，根因在此。

## 4. 修复

本机 Chrome 由应用商店以 **linglong（如意玲珑）** 容器形态安装，宿主侧二进制位于内容寻址的层目录：

```text
/var/lib/linglong/layers/<内容哈希>/files/bin/google/chrome/chrome
```

实测该二进制**可脱离容器独立运行**：`--version` 正常，Playwright 以 `executablePath` 启动成功（带不带 `--no-sandbox` 都可以）。因此只需把它接到 Playwright 认的路径上：

```bash
sudo mkdir -p /opt/google
sudo ln -sfn "$(dirname "$(ls -d /var/lib/linglong/layers/*/files/bin/google/chrome/chrome | head -1)")" /opt/google/chrome
```

此处用通配符现场解析层目录（而非写死哈希），使命令在 Chrome 更新后也能直接复用。

验证：

```bash
ls -l /opt/google/chrome/chrome && /opt/google/chrome/chrome --version
```

通过后**插件配置一个字都不用改**。

## 5. 修复后的验证

| 项 | 结果 |
|---|---|
| Playwright `channel: 'chrome'` 启动 | 成功，`Google Chrome 153.0.8010.52` |
| MCP `browser_navigate` | 成功（`https://example.com`，标题正确） |
| MCP `browser_take_screenshot` | 成功（1042×781 PNG） |

## 6. 已知残留

### 6.1 Chrome 更新后符号链接悬空

linglong 层路径含内容哈希，应用商店更新 Chrome 会生成新层，旧链接悬空，MCP 重新报出第 1 节那个错误。**症状与首次故障完全相同**，容易误判为新问题。遇此重跑第 4 节命令即可。

### 6.2 `file://` 仍被 MCP 主动拦截

```text
Error: Access to "file:" protocol is blocked.
```

这是 MCP 的默认安全策略，需 `--allow-unrestricted-file-access` 才能放开；又因插件不传参（第 2 节）而难以启用。

**约定：本地 HTML 预览走浏览器直接打开，或用原生 headless 截图，不经 MCP。** MCP 用于线上页面。

### 6.3 参数覆盖无处安放

插件自身的 `.mcp.json` 位于插件缓存，**插件升级即被覆盖**，故任何参数覆盖都不能写在那里。理论上的替代层是用户级 `mcpServers`（`~/.claude.json`）或项目级 `.mcp.json`，但与插件同名服务的优先级未验证。符号链接方案零配置可用了，故未采用。

## 7. 踩过的坑（勿重蹈）

`npx playwright install chrome` **不可用**，两个独立原因：

1. **会清缓存。** `npx` 总会拉最新版 Playwright，而新版在安装前清理它不认识的浏览器。本机 `~/.cache/ms-playwright/chromium-1228` 等（由其他插件安装）被判为 unused 并删除。**该缓存是全用户共享的**——影响范围超出本仓库，波及本机所有依赖 Playwright 的插件。事后以 `npx playwright install chromium` 重建（现为 `chromium-1248` + `ffmpeg-1013`，版本号不同但功能等价）。
2. **发行版白名单。** 该命令的安装脚本读 `/etc/os-release` 的 `ID`，只放行 `ubuntu` / `debian`，Deepin 被直接拒绝：

   ```text
   ID=deepin
   ERROR: cannot install on deepin distribution - only Ubuntu and Debian are supported
   ```

   **与 sudo 权限无关**——密码验证成功后才被拒。

**正确做法：用发行版包管理器或官方 `.deb`，或如第 4 节直接符号链接；不要让 `npx @latest` 经手。**

> 附注：若走官方 `.deb` 路线，注意 `fonts-liberation` 在 Deepin 仓库**无候选版本**（`fonts-liberation2` / `ttf-liberation` 同样没有），apt 会因单个依赖无法满足而整体拒绝安装，且**报错在下载之后**，容易误判为已安装。

## 8. 影响范围

| 项 | 说明 |
|---|---|
| MCP 交互式工具 | 修复前全不可用；修复后可用（`file://` 除外，见 6.2） |
| 原生 headless 截图 | **全程可用**，与 channel 无关——本次架构图的渲染验证即走此路径 |
| 其他依赖 Playwright 的插件 | 曾受 7.1 波及；浏览器缓存已重建并复验 |
| 仓库与文档产出 | 无影响（`.svg` 是落盘文件，不依赖浏览器） |

## 更新历史

| 版本 | 日期 | 变更说明 |
| ------ | ------ | ------ |
| 1.0 | 2026-10-08 | 初版 |
