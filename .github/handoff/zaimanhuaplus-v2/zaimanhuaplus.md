# 再漫画 Plus

## 上游基线

- 2026-09-05 新建。
- 直接以当前 Keiyoushi `src/zh/zaimanhua` 为功能基线，保留其登录、账号等级校验、排行榜、筛选、详情、章节、正文、图片重试和章末吐槽图片。
- 当前上游再漫画仍使用 `HttpSource` + `libVersion = "1.4"`，并依赖 legacy `fetchPageList`/Observable、自定义鉴权和图片重试拦截器。为避免首版 Plus 在迁移到 `KeiSource` 时破坏已经验证的登录/阅读链路，v1 暂时保持上游架构；这是项目对旧模板的明确兼容例外。后续若上游迁移到 KeiSource，再同步升级。

## Plus 增强

- MX 书评：`GET /app/v1/comment/list`，参数 `type=4`、`objId=comicId`，分页读取。
- 书评嵌套回复：从 `replyList/replies` 读取，并提供 MX 回复列表。
- MX 章评/吐槽：`GET /app/v1/viewpoint/list?type=0&comicId=...&chapterId=...`。
- 自动签到：账号 API `GET /v1/task/list` 检查 `signInfo.current_sign`，未签到时 `POST /v1/task/sign_in`，随后再次检查确认。
- 自动签到默认开启；成功后当天不重复，失败 10 分钟内不重复尝试。
- 保留上游设置中的“章末吐槽页”，它是阅读器末尾生成图片的兼容功能；MX 章评页面与其互不冲突。

## 登录

保留上游现行登录：

- `POST https://account-api.zaimanhua.com/v1/login/passwd`
- 用户名 + 密码 MD5
- JWT Token 本地持久化
- Token 失效后按上游逻辑重新登录

账号、密码、Token、Cookie 均只保存在客户端运行时/本地偏好，不写入仓库。

## 评论写操作

首版只开放读取书评、书评回复和章评。虽然已找到章评发送接口 `/viewpoint/add`，但书评写入接口和宿主统一写操作尚未完成同等级验证，因此 v1 不声明发布评论、回复或点赞能力。

## v1 发布记录

- 源码版本：`versionCode = 1`。
- 扩展版本：`1.4.1` / Android `versionCode = 104001`。
- 当前源码已写入 `mx-dev/main`。
- 当前源码已重新在现行 Keiyoushi 框架中完成 `spotlessCheck`、Debug/Release 构建、`lintRelease`、APK 签名和 MX ABI 校验。
- 固定签名证书 SHA-256：`1bbfbdc401ab81dc227bf771c43d5b616d00149f5755691a317a819f5a88f620`。
- 已生成隔离测试商店快照并提升到 `mx-repo/repo/test`。
- 测试商店公开索引已确认包含 `eu.kanade.tachiyomi.extension.zh.zaimanhuaplus`，版本为 `1.4.1`。
- 测试商店：`https://github.com/huoguotiankong/mx-repo/raw/repo/test/repo.json`。
- 测试 APK：`test/apk/tachiyomi-zh.zaimanhuaplus-v1.4.1.apk`。

## 验证状态

### 已验证

- 源码结构与项目校验脚本通过。
- Spotless 通过。
- Debug / Release 编译通过。
- `lintRelease` 通过。
- Release APK 固定签名校验通过。
- R8 后 MX `CommentSource` / `CommentTarget` ABI 保留验证通过。
- 隔离测试商店生成、staging 校验、提升和公开端点验证通过。

### 待实机验证

- 用户名 / 密码登录与 Token 自动刷新。
- 普通浏览、详情、目录、正文和图片重试。
- 账号等级相关章节读取。
- 自动签到：未签到、已签到以及失败重试状态。
- 书评列表与翻页。
- 书评嵌套回复。
- 章评 / 吐槽列表。
- 可选“章末吐槽页”。

CI 和公开测试商店通过不等于上述功能已经实机可用。收到实机反馈后再决定是否升版修复并进入正式发布流程。


## v2 实机反馈修复记录

- 2026-09-06 用户实机确认 v1.4.1 无法完成账号登录，且安装后的源列表显示为共享 core 默认书签图标。
- 登录链路不再照搬旧上游裸请求：账号接口增加当前 APP 风格参数 `platform=android`、`timestamp`、`_v=2.3.7`、`_c=101_01_01_000`，并增加 `Platform`、持久化随机 `X-Client-ID`、`AppVersion`、`BuildNumber=1502277`、`Channel` 等请求头。
- 登录表单改为正常 URL 编码，登录失败不再清空用户名和密码；新增可点击的“立即登录 / 检查登录”和可读登录状态，便于直接看到服务端错误。
- Token 有效性优先通过账号 API 校验，不再依赖本地 JWT payload 解析作为登录成功的前置条件。
- 图标问题根因是 v1 仅保留 `mipmap-xxhdpi/ic_launcher`；Android 在其它屏幕密度上可命中 shared core 默认图标。v2 恢复 Keiyoushi 再漫画的 mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi 五档完整图标资源。
- v2 源码版本 `versionCode = 2`，扩展版本 `1.4.2` / Android `versionCode = 104002`。
- v2 已在现行 Keiyoushi 框架完成 Spotless、Debug/Release、lint、固定签名和五档图标资源校验，并生成隔离测试商店 APK。登录成功本身仍需真实账号实机确认。
