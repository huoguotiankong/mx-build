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

## 验证状态

- 源码由当前 Keiyoushi 再漫画模块生成并增加 Plus 能力。
- Bootstrap workflow 会在当前 Keiyoushi 框架下执行 Spotless、Debug、Release 与 lint，并验证 Release APK 中保留 MX `CommentSource` ABI。
- CI 通过只代表构建/ABI验证；登录、自动签到、书评、章评和正文仍需用户实机确认。
