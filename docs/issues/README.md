# 问题记录索引

`docs/issues/` 目录的索引。记录开发过程中遇到的问题：现象、根因、诊断依据、修复方式与已知残留。**环境 / 工具链问题与产品问题都记在这里**，以「影响范围」小节区分。

| 文档 | 状态 | 说明 |
|---|---|---|
| [playwright-mcp-chrome-channel.md](playwright-mcp-chrome-channel.md) | 已修复 | Playwright MCP 因 chrome channel 硬编码 `/opt/google/chrome/chrome` 而无法启动浏览器；含 linglong 版 Chrome 的符号链接解法、残留事项与「勿用 `npx playwright install chrome`」的踩坑记录 |
