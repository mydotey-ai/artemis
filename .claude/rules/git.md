# Git 规则

## 分支命名

`<name>` 使用 `kebab-case`：

| 类型 | 格式 | 示例 |
|------|------|------|
| 功能 | `feature/<name>` | `feature/user-auth` |
| 修复 | `bugfix/<name>` | `bugfix/login-error` |
| 紧急修复 | `hotfix/<name>` | `hotfix/security-patch` |
| 发布 | `release/v<version>` | `release/v1.0.0` |
| 文档 | `docs/<name>` | `docs/api-guide` |

## Commit 规范

格式：`<type>(<scope>): <subject>`，`<scope>` 可选

**Type**: `feat` | `fix` | `docs` | `style` | `refactor` | `test` | `chore` | `perf`

**示例**：
- `feat(auth): add OAuth2 login`
- `fix: resolve null pointer in parser`
- `chore: bump dependencies`

## 提交控制

- commit 与 push 仅在用户明确要求时执行（如"提交"、"commit"、"帮我提交"），完成代码修改后不自动提交
- 提交前展示变更摘要，不额外等待确认

## 分支合并策略

- **默认使用 squash merge**：功能分支合入主分支时，将所有 commit 压缩为一个，保持主分支历史线性整洁
- squash merge 的 commit message 取该分支的核心变更描述，遵循 Commit 规范格式
- 如遇特殊场景需保留完整提交历史（如大规模重构），需用户明确指定方可使用 merge commit 或 rebase

## Worktree 规范

- **默认目录**：`.claude/worktrees/`，创建 worktree 前必须检查 `.gitignore` 中是否已包含该路径，未包含则自动添加
- **命名**：worktree 目录名与分支名保持一致，例如分支 `feature/auth` 对应 `feature-auth`
