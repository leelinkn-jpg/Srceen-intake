# SECURITY — 哪些数据会离开手机？

## 默认（未开启 WebDAV）

- **不会**向任何外部服务器发送屏幕内容或元数据。
- 捕获的 Markdown 仅写入应用私有目录 `files/inbox/`（或分享缓存）。
- 使用系统「分享」时，内容仅发给您选择的目标 App（由您确认）。

## 开启 WebDAV 上传后

离开手机的数据：

1. **Markdown 正文**：无障碍提取的可见文本 + YAML frontmatter（时间戳、`packageName`、`captureMethod`、可选 `tag` / `windowTitle`）
2. **WebDAV 凭据**：仅在 HTTPS/HTTP 请求的 `Authorization` 头中发送到您配置的 URL（您的 NAS / Nextcloud 等）
3. **测试连接**：对配置的 WebDAV 根发起 PROPFIND/GET，不上传正文

**不会**发送到：分析平台、广告 SDK、作者服务器、崩溃统计（本 MVP 未集成）。

## 本机存储

- WebDAV 用户名/密码：`EncryptedSharedPreferences`（AndroidX Security Crypto，AES-256）
- `allowBackup=false`，降低备份导出凭据风险
- 无障碍服务：仅在调用捕获流水线时遍历当前活动窗口节点；不持续外传

## 权限说明

| 权限 | 用途 |
|------|------|
| 无障碍 | 读取当前窗口可见文本（核心能力） |
| INTERNET | WebDAV（可选） |
| 前台服务 / 通知 | 通知栏「立即捕获」快捷入口 |
| FileProvider | 安全分享本地 Markdown |

## 建议

- 优先 HTTPS WebDAV；家庭 NAS 若用 HTTP，请限定局域网
- Nextcloud 建议使用「应用专用密码」
- 不信任的应用窗口请勿触发捕获（文本可能含隐私）
- OCR / 截屏路径未实现；未来若加入 MediaProjection，需另行评估图像外传策略
