# MX Build

MX 项目的公开构建基础设施仓库。

本仓库只保存 GitHub Actions 工作流、构建辅助脚本和最小化说明，不保存：

- `mx-app` / `mx-dev` 私有源码副本
- Cookie、Token、账号密码
- Android 签名密钥 / keystore
- 私有源码构建日志之外的诊断包
- 私有 MX漫画 APK

私有源码仍保存在：

- `huoguotiankong/mx-app`
- `huoguotiankong/mx-dev`

扩展公开分发仍使用：

- `huoguotiankong/mx-repo`

公开 Actions 只作为 MX 项目的构建基础设施。涉及私有源码和签名的工作流必须采用手动可信触发、最小权限 Fine-grained PAT 与 GitHub Actions Secrets。

## 已验证

- 2026-09-02：公开 Runner 已成功构建私有 `mx-app/main` 提交 `f319e7452e6a9816caffea1293e38a2b9233df5b`。
- Spotless、Preview APK、单元测试、MX App 稳定签名、应用身份与私有 `downloads` 发布均通过。
- Android 实机功能仍以用户测试反馈为准。
