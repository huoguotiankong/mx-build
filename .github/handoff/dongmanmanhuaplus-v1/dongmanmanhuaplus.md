# 咚漫画 Plus

## 定位

- Tachiyomi / Mihon / Komikku 兼容 APK 扩展。
- 模块：`src/zh/dongmanmanhuaplus/`。
- 当前基线：Keiyoushi `libVersion = 1.6`、`@Source`、`KeiSource`。
- 上游参考：Keiyoushi `src/zh/dongmanmanhua`，其现行网页解析能力保留并迁移到 KeiSource。
- 首版源码 `versionCode = 1`，Android PackageInfo 预期为 `106001`。

## 事实来源

- 官方移动站：`https://m.dongmanmanhua.cn/`。
- 官方备用网页主机：`https://www.dongmanmanhua.cn/`。
- 用户提供的官方 Android APK：`dongman-DM_M_Web.apk`，仅用于确认官方 App 的生产 API 路径和字段；APK 本身、账号数据和任何运行时凭据均不提交仓库。
- App 中确认的生产 API 主机：`https://apis.dongmanmanhua.cn`。

## 基础阅读

网页线路保留：

- 热门 / 每日更新；
- 搜索；
- 漫画详情；
- 完整章节目录；
- 网页正文图片。

网页解析失败时不得把 App API 当作无条件绕过入口。App API 只用于补充官方章节状态、章评，以及用户账号已合法解锁/购买章节的官方正文读取。

## 账号登录

APK 中确认的账号相关接口包括：

- `/app/rsakey/get`
- `/app/member/id/login`
- `/app/member/login/email`
- `/app/member/tokenLogin`
- `/app/member/checkToken`
- `/app/member/getProfile`
- `/app/member/revokeToken`

APK 模型同时确认 `RsaKey` 包含 `keyName`、`sessionKey`、`nValue`、`eValue` / `publicKey` 等字段，ID 登录请求存在 `encnm` / `encpw` 字段。

首版设置页提供账号（邮箱 / ID）、密码和“立即登录 / 检查登录”。令牌只保存在扩展 SharedPreferences，不写入仓库。首版对邮箱明文 JSON/Form 登录、ID 常见字段和 RSA `encnm` / `encpw` 登录进行兼容尝试；真实账号登录结果必须以实机为准。

## 付费章节与锁标记

APK 章节模型确认存在：

- `episodeNo` / `episodeTitle` / `episodeName`
- `serviceStatus` / `episodeServiceStatus`
- `isPaid` / `episodeIsPaid`
- `purchased` / `hasPurchased`
- `borrowed` / `hasBorrowed`
- `price`
- `FREE` / `LOCKED` / `UNLOCKED` / `CHARGE` / `PURCHASED` / `BORROW` / `BORROWED_END`

章节列表优先使用网页完整目录，再通过官方 `/app/episode/list/v6` 补充并校准付费状态。明确未解锁的章节标题前加 `🔒 `；已购买、已借阅或免费章节不加锁。App 章节状态请求失败时保留网页目录，不因为增强接口故障破坏基础阅读。

## 付费正文与 MX 正文替换

官方正文增强接口：`/app/episode/info/v5`。网页正文没有图片时，仅在能解析 `titleNo` / `episodeNo` 的情况下尝试官方 App 接口，并携带用户已登录令牌。它只用于用户账号已经合法解锁/购买的章节。

扩展同时实现 MX `ChapterContentReplacementSource`，在章节列表和阅读器中显式开放宿主的通用“章节正文替换”入口。替换内容由 MX 从用户已安装且可访问的其它漫画源中匹配，扩展本身不绕过咚漫付费限制。

## 章评

APK 中确认的评论相关接口/模型：

- `/v2/comment`
- `/v1/comment/ownall`
- `/v1/comment/{commentId}`
- `/v1/comment_reply`
- `/v1/comment_reply/{commentId}`
- `/app/comment/titleEpisodeInfo2`
- `commentId` / `commentNo`
- `commentContentText`
- `commentDate`
- `commentReplyList`
- `replyCount`
- `nickName` / `nickname`
- `profileImage`
- `likeCount`
- `titleNo` / `episodeNo` / `pageNo` / `pageSize`

首版通过 MX `CommentSource` 只声明章评读取，支持主评论和回复读取；不开放发评论、回复、点赞写操作，避免在未实机验证写接口前产生账号副作用。

## 图标

必须保留 Keiyoushi 现行咚漫画扩展的官方五档 launcher 图标：mdpi / hdpi / xhdpi / xxhdpi / xxxhdpi。Release 构建同时检查五档资源，避免安装后回落到 core 默认图标。

## 验证状态

- APK 端点/字段静态确认：已完成。
- 网页当前站点与作品/章节页面：已确认可访问。
- 源码：开发中，提交后更新。
- Spotless / Debug / Release / Lint：待 CI。
- 固定签名：待 CI。
- 登录：待真实账号实机验证。
- `🔒` 付费章节状态：待实机验证。
- 用户已购买章节官方正文：待实机验证。
- MX 章节正文替换入口：待实机验证。
- 章评 / 回复：待实机验证。
