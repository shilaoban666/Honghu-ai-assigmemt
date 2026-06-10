# Copilot PR Review 配置说明

本仓库已包含两类 PR 质量门：

1. `CI`：执行 Maven `-Pci clean verify`，验证可自动化测试和构建。
2. `Copilot PR Review`：在 PR 创建、同步、重新打开或从 draft 变为 ready 时，用 GitHub CLI 请求 `@copilot` 作为 reviewer。

## 已落地文件

- `.github/workflows/ci.yml`
- `.github/workflows/copilot-pr-review.yml`
- `.github/copilot-instructions.md`
- `.github/pull_request_template.md`

## GitHub 页面需要开启的设置

进入仓库：

`Settings -> Rules -> Rulesets -> New ruleset`

建议规则：

- Target branches：`main`、`master`、`dev`
- Pull request：Require a pull request before merging
- Copilot：Request pull request review from Copilot

如果仓库或账号没有 Copilot Code Review 权限，workflow 会给出 notice，不会阻塞普通 CI。

官方文档：

- https://docs.github.com/en/copilot/how-tos/use-copilot-agents/request-a-code-review/use-code-review
- https://docs.github.com/en/copilot/how-tos/copilot-on-github/set-up-copilot/configure-automatic-review
- https://docs.github.com/en/copilot/how-tos/configure-custom-instructions/add-repository-instructions
