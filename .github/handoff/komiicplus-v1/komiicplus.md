# Komiic Plus

## 定位

- Tachiyomi / Mihon / Komikku 兼容 APK 扩展。
- 模块：`src/zh/komiicplus/`
- 基线：当前 Keiyoushi `src/zh/komiic`，`@Source` + `KeiSource`，`libVersion = 1.6`。
- 首版源码 `versionCode = 1`。

## 站点与线路

当前已确认的官方/上游兼容站点：

- `https://komiic.com`
- `https://komiic.cc`

不加入未经验证的第三方内容站点。保留 Keiyoushi 的多镜像基线，并增加失败自动尝试另一 Komiic 站点的容错；401/402 不作为线路故障切换条件。

## 基础阅读能力

保留当前 Keiyoushi Komiic 的：

- 热门 / 最近更新
- 搜索
- 分类、排序、状态、色气程度筛选
- 漫画详情
- 章节（卷/话可筛选）
- 相关推荐
- GraphQL 章节图片列表
- 图片 Referer
- Komiic 图片接口与额度错误处理

GraphQL 主端点：`/api/query`。

## 登录

当前公开实现与站点行为确认：

- 首选 `POST /api/login`
- JSON：`email` / `password`
- 成功返回账号 Token
- API 使用 `Authorization: Bearer <token>`
- 为兼容旧实现保留 `/auth/login` 回退
- Token 临近过期时使用本地保存的账号密码重新登录
- 登录失败不删除用户填写的账号密码

账号密码和 Token 仅保存在扩展运行时 SharedPreferences，禁止提交仓库。

## 评论

Komiic 当前漫画评论 GraphQL：

- 漫画评论：`getMessagesByComicId(comicId, pagination)`
- 回复链：`messageChan(messageId)`
- 评论总数：`messageCountByComicId(comicId)`

首版通过 MX `CommentSource` 暴露漫画书评和回复读取。当前公开可交叉验证实现同样只确认读取链路，因此 v1 不开放发评论、回复、点赞写操作。

Komiic 当前没有在已验证接口中确认独立的章节评论能力，因此 v1 只声明漫画评论，不伪造章评。

## 图片额度

额度 GraphQL：

```graphql
query getImageLimit {
  getImageLimit {
    limit
    usage
    resetInSeconds
  }
}
```

设置页显示：已用 / 上限 / 剩余 / 重置倒计时，并支持点击刷新；登录成功后自动刷新一次。图片接口 HTTP 402 或 `reachedImageLimit` 为真时显示明确的额度耗尽错误。

额度属于账号/站点实时状态，不在每张图片请求前额外发一次额度查询，避免明显放大请求量。

## 图标

必须从当前 Keiyoushi `src/zh/komiic` 保留完整五档官方 launcher 图标：mdpi / hdpi / xhdpi / xxhdpi / xxxhdpi。Release 构建同时校验五档资源，避免安装后回落到 shared core 默认图标。

## 验证状态

- 源码：已写入 `mx-dev/main`，持续构建修正中。
- Spotless / Debug / Release / Lint：待当前 v1 CI 完成后更新。
- 固定签名：待当前 v1 CI 完成后更新。
- 登录：待真实账号实机验证。
- 评论 / 回复：待 MX 实机验证。
- 图片额度：待真实账号/匿名额度实机验证。
- `.com` / `.cc` 线路切换与自动回退：待实机网络验证。
