# 快看漫画（Kuaikan Manhua）

- 模块：`src/zh/kuaikanmanhua/`
- 主站：`https://www.kuaikanmanhua.com`
- 移动站：`https://m.kuaikanmanhua.com`
- API：`https://api.kkmh.com`
- 当前版本：`versionCode = 24`
- 基线：Kotlin + `@Source` + `KeiSource` + `libVersion = "1.6"`
- Source ID：`8099870292642776005L`
- 语言：`zh`（MX 客户端可见性兼容；Source ID 保持不变）

## 目标

按 MX 哔哩哔哩漫画扩展的能力层级实现：热门、最新、搜索、筛选、详情、目录、正文、账号登录、已购章节读取，以及 MX 漫画评论/章节评论入口。

## v24 核心变化

- 配合 MX 漫画 `663ddc65f2186aeca2da669ef9cdbc96ffc5c0ec` 起的原生评论图片渲染，快看 APP 评论 `content` / `contents` / `content_info` 中的图片 URL 会和文字一起保留下来，不再只读取 `content/text`。
- 图片识别限制在快看实际使用的 `kkmh.com` / `v3mh.com` 图像域名，并覆盖 `/comment/image/`、`/social/`、`-watermark`、常见图片扩展名以及旧版 `v3mh.com/<hash>_<timestamp>` 形式。
- 纯图片评论不会再因为文字为空被通用 `commentFromObject()` 丢弃；图文评论会保留正文和去重后的图片 URL，同时只扫描评论内容字段，避免把用户头像误当评论图片。
- PC 网页评论回退同步读取评论正文里的 `<img>` 和图片链接，避免 APP 评论接口回退后再次丢图。
- 评论能力继续保持只读；本版不开放发表评论、回复写入或点赞写入。
- CI 成功仅代表源码格式、构建、lint 与 MX ABI 可通过；快看真实图片评论显示仍需 Android 实机验证。

## v16 核心变化

- APP API 请求改用独立 `CookieJar.NO_COOKIES` 客户端，并由扩展显式写入合并后的账号 Cookie，避免 `api.kkmh.com` 自身 CookieJar 覆盖从 `kuaikanmanhua.com` 登录得到的 `session` 会话。
- 账号 Cookie 按名称去重，并优先采用官方移动登录域的值，降低多域残留旧会话导致重复 Cookie 名称的风险。
- 章节完整评论 `/v1/comics/{id}/comments/{cursor}` 增加真实 `since` 游标映射；不再假设所有后续页游标都严格等于 `(page-1)*20`。
- 章节评论接口明确返回 `since < 0` 且评论为空时直接终止分页，避免落入网页热门评论回退后出现重复数据。
- 本版继续保持评论只读，不开放发表、回复写入或点赞写入。

## v20 核心变化

- 根据用户 v19 实机反馈修复“全部作品详情页变成共 0 章 / 没有章节”的回归。
- 根因：v19 为 PC 登录把源级 User-Agent 改为 Desktop，快看 PC 作品页的 Nuxt 章节数组字段为 `comics`，而此前详情解析只读取移动链路字段 `comicList`，因此作品信息能显示但章节数组被当成空列表。
- 详情章节解析改为兼容 `comicList` / `comics` 两套当前站点结构，并保留显式 PC 登录与 PC 评论链路。
- 这次不回退 PC 登录方案，避免修章节时重新引入移动登录问题。

## v19 核心变化

- 根据用户 v18 实机反馈继续修复：测试仓库已正常显示快看，漫画评论页可读取大量 PC 评论；当前问题集中在登录、评论页底部登录提示与章节评论总数。
- 登录改为 PC 端链路：Source WebView 改用桌面浏览器 User-Agent，登录入口改为 PC 站 `/webs/loginh?redirect=...`，并优先识别 PC 登录产生的 `passToken` Cookie；`session` 仅保留兼容。
- 账号 Cookie 合并顺序改为 PC 主站 → API → 移动/H5，避免旧移动端 Cookie 抢占 PC 登录会话。
- 评论仍为只读，因此 `requiresLoginToPost` 改为 `false`，避免 MX 评论页底部错误显示“请先登录漫画源账号后再参与评论 / 漫画源设置”。
- 修正章节评论总数：不再递归把任意嵌套 `count` 当评论总数；优先使用评论专属字段，并从 PC reader 页面“评论 N”工具栏读取真实章节评论数。
- 章节 PC 页面若只嵌入少量热门评论，会依据真实总数判断其“不完整”，继续回退完整 JSON 评论流；已知总数会传给分页结果，使标题栏直接显示正确“共 N 条”。

## v18 核心变化

- 修复 MX/Komikku 扩展商店中“测试仓库已添加但快看漫画不显示”的问题。根因不是测试仓库缺失条目，而是 MX 客户端 `GetExtensionsByType` 会按用户启用语言过滤 `Extension.Available.sources`；当前用户环境启用了 `zh`，而快看测试索引发布为 `zh-Hans`，因此条目在 UI 层被过滤掉。
- 扩展 `source.lang` 从 `zh-Hans` 调整为 `zh`，与本 MX 仓库其它中文扩展一致；显式 Source ID `8099870292642776005L` 保持不变，因此不通过重新计算 ID 改变源身份。
- `versionCode` 升至 18，测试仓库重新构建并发布后应以 `zh` 出现在扩展列表。

## v17 核心变化

- 评论网页链路明确固定为 PC 站 `www.kuaikanmanhua.com`，所有评论网页请求强制使用桌面浏览器 User-Agent；不把 `m.kuaikanmanhua.com` / `h5.kuaikanmanhua.com` 的移动 H5 评论页作为数据源，避免移动页要求跳转快看 APP。
- 漫画评论继续优先读取 PC 作品页 `/web/topic/{topicId}` 的真实热门评论。
- 章节评论会先探测 PC reader `/webs/comic-next/{comicId}` 的桌面 Nuxt 评论数据；只有当 PC 页面只嵌入少量热门评论、无法代表完整评论流时，才回退直连 JSON `/v1/comics/{comicId}/comments/{cursor}?order=score`。
- JSON 评论 API 回退仅用于读取数据，不打开移动网页，也不会触发 APP 跳转；现有真实 `since` 游标分页继续保留。
- 通用 `getNuxt()` 支持传入独立请求头，评论专用网页全部改用 `desktopHeaders()`，避免 PC URL 因 Android Mobile User-Agent 返回移动端结构。

## v15 核心变化

- v15 起沿用 Keiyoushi 官方快看源的显式 Source ID；v18 为兼容 MX 客户端语言过滤将语言标识改为 `zh`，但 Source ID 始终保持 `8099870292642776005L` 不变。
- 详情与正文继续使用当前网站 `window.__NUXT__` 数据，正文保留 APP API → 网页 reader 双链路。
- 章节 source order 改为按当前网站 `comicList` 原始顺序反转，与当前 Keiyoushi 修复版一致；不再按时间戳强行重排。
- 章节评论优先使用当前可返回 20 条的 `/v1/comics/{id}/comments/{offset}` 完整评论流；失败时再回退网页 Nuxt/热门评论接口。
- 漫画评论继续优先解析当前作品页 Nuxt 中的真实网页评论，再回退历史 APP 评论 API。
- 评论解析优先进入名称包含 `comment` / `floor` / `replies` 的数据容器，降低推荐作品等普通对象被误识别成评论的概率。
- 列表封面统一规范为 HTTPS URL。

## 账号登录

v19 起登录入口改用快看 PC 登录页：

`https://www.kuaikanmanhua.com/webs/loginh?redirect=https%3A%2F%2Fwww.kuaikanmanhua.com%2F`

扩展通过宿主 Source WebView 完成官方登录，不保存用户名、密码、验证码或 Token。Source WebView 会继承扩展桌面 User-Agent；登录后优先检测 PC 端 `.kuaikanmanhua.com` 的 `passToken`，并兼容旧 `session` Cookie。

为兼容网页登录域与 APP API 域不同，API 请求会合并以下域当前 Cookie：

- `www.kuaikanmanhua.com`（优先）
- `api.kkmh.com`
- `m.kuaikanmanhua.com`（兼容）
- `h5.kuaikanmanhua.com`（兼容）

账号资料尝试通过 `/v1/passport/user` 获取；失败时只向 MX 暴露脱敏后的“已检测到快看账号会话”状态。

## 已购 / 付费章节

- 目录来自详情页 Nuxt `comicList`。
- 付费属性综合 `is_free`、`has_pay`、`need_pay`、`is_pay`、`pay_type`、`price` 等字段判断。
- 当前账号可读状态综合 `can_view`、`is_bought`、`has_bought`、`purchased`、`is_purchased` 等字段判断。
- 已确认可读的付费章节显示 `✅`；未解锁显示 `🔒`。
- 匿名正文优先 `/v2/comic/{id}?is_preview=1`；登录后尝试 `is_preview=0`。
- API 未返回图片时回退 `/webs/comic-next/{id}` 当前 Nuxt reader 数据。

扩展不会购买章节、不会绕过付费或 DRM；能否读取只取决于快看官方对当前账号会话返回的权限。

## MX 评论

当前实现 `CommentSource`：

- 漫画评论：启用。
- 章节评论：启用。
- 评论读取：只读。
- 子回复：只读回退。
- 发表评论：关闭。
- 回复写入：关闭。
- 点赞写入：关闭。
- 因写入能力全部关闭，`requiresLoginToPost = false`，评论页不再显示无意义的登录/漫画源设置提示。

### v17 读取顺序

1. 漫画：优先 PC 作品页 `https://www.kuaikanmanhua.com/web/topic/{topicId}`，使用桌面 User-Agent 直接解析服务端评论 DOM / 网页数据。
2. 章节：先探测 PC reader `/webs/comic-next/{comicId}`，同样使用桌面 User-Agent；PC 页面若提供完整或至少一整页评论，则直接采用 PC 数据。
3. PC reader 只嵌入少量热门评论时，为避免评论数量缩水，回退直连 JSON `/v1/comics/{comicId}/comments/{cursor}?order=score`，继续按真实 `since` 游标翻页；该请求不是移动 H5 页面，不会触发 APP 跳转。
4. PC 兼容页 `/web/comic/{comicId}` 仍作为网页兜底，并强制桌面 User-Agent。
5. 以上路径均未返回可解析评论时，再尝试历史 JSON 路由：
   - `/v2/comments/hot_floor_list`
   - `/v1/comments/floor_list`
   - `/v1/comics/{id}/comments/{offset}`
   - `/v1/comments/feed/{id}/order/time`
   - `/v1..v4/comments/cruel/hot_floor_list`

网页一次嵌入的评论在扩展侧按 20 条切页；如果网页只提供热门评论集合，则不会伪造不存在的完整分页。**评论数据源禁止使用移动 H5 评论页作为主链路**；移动站仅保留账号登录用途。章节 JSON API 已匿名探测确认 `/v1/comics/{id}/comments/{offset}?order=score` 可返回 20 条正常评论页，因此它只作为 PC 页面数据不完整时的无跳转数据回退。

## 验证状态

### 已确认

- 当前 Keiyoushi 官方快看源已改用网页 Nuxt 修复详情与正文，证明这条基础读取链路仍是当前有效方向。
- 快看官方移动登录页仍提供官方账号登录界面。
- 快看 PC 作品页仍展示“热门评论”，所以网页评论是当前可见的真实数据来源。
- 2026-09-02 匿名探测确认章节 `/v1/comics/{id}/comments/{offset}?order=score` 返回 20 条评论；`/v2/comments/hot_floor_list` 与 `/v1/comics/{id}/hot_comments` 各返回 10 条热门评论；作品级旧 `target_type=topic` API 对测试作品返回空，因此作品评论继续采用 PC 网页优先。
- 2026-09-02 当前 Keiyoushi 官方快看源源码版本为 v13；MX 自制版从 v14 起承接其版本序列。
- MX ABI 兼容类与 R8 keep 规则已经存在于本模块。
- v15 已在公共构建环境针对私有源码提交 `7674911fa1e46b69ca79cdb20f1bd72cdcfbf60e` 执行 `:src:zh:kuaikanmanhua:assembleDebug` 与 `:src:zh:kuaikanmanhua:lintRelease`，两项均通过。
- v16 已针对私有源码提交 `95ed50714b7eee4ecd97bc48cdb8e851554862cd` 执行 Debug、Release、`lintRelease`，并检查 Release APK 中 MX `CommentSource` / `commentCapabilities` ABI 在 R8 后仍保持可识别；全部通过。
- 2026-09-02 章节评论分页实测：offset 0 返回 20 条且 `since=20`；offset 20 返回 9 条且 `since=29`；继续请求 cursor 29 返回 0 条且 `since=-1`，与 v15 的终止判断一致。
- 2026-09-02 公共 `mx-build` run `33639720748` 已完整通过：私有源码检出、源码布局校验、固定扩展签名解析、签名证书指纹校验、Release 编译、APK 签名校验、`extension-staging` 测试仓库生成/提交/在线校验全部成功。
- v16 暂存 APK：`mx-build/extension-staging/test/apk/tachiyomi-zh.kuaikanmanhua-v1.6.16.apk`，Android versionCode 为 `106016`。
- 2026-09-02 已再次确认 PC 作品页存在“热门评论”，PC reader `/webs/comic-next/{id}` 也暴露章节评论入口/评论数；因此 v17 将评论网页链路固定为 PC 域名 + 桌面 User-Agent。
- 2026-09-02 针对用户“已添加 MX Test 但快看不显示”的实机反馈检查 MX 客户端源码：`GetExtensionsByType` 会执行 `ext.sources.filter { it.lang in enabledLanguages }`；因此 `zh-Hans` 在只启用 `zh` 的环境中会被 UI 过滤。v18 将 `source.lang` 改为 `zh`，Source ID 保持 `8099870292642776005L`。
- v17 源码提交 `136042528331bf985e5aee72428a059804a81c8f`，版本递增提交 `5f29a2c44113d26cf6d3a19800f915edd5bff1c0`。
- 2026-09-02 公共 `mx-build` run `33643021341` 已完整通过：私有源码检出、布局校验、固定签名解析、签名身份校验、Release 编译、APK 签名校验、测试仓库生成与在线校验全部成功。
- v17 暂存 APK：`mx-build/extension-staging/test/apk/tachiyomi-zh.kuaikanmanhua-v1.6.17.apk`，Android versionCode 为 `106017`。
- v17 已迁移到 `mx-repo/repo/test`，发布提交 `0b657467c8db8aceba5805be13b09aadc0bdb0f5`；测试索引现指向 v1.6.17 APK 与 v106017 图标。
- v18 公共 `mx-build` run `33647730498` 已完整通过：固定签名校验、Release 编译、APK 证书校验、隔离测试商店生成与在线校验全部成功。
- v18 已迁移到 `mx-repo/repo/test`，发布提交 `ca9b48208d1efc016c146a5610760f1e39d336a1`；当前测试索引为 v1.6.18 / Android versionCode `106018`，source language 为 `zh`，APK 与图标均已存在。
- v19 公共 `mx-build` run `33650137582` 已完整通过：Spotless、Release 编译、固定签名身份、APK 证书、隔离测试商店生成与在线校验全部成功。
- v19 已发布到 `mx-repo/repo/test`，发布提交 `c0da7b2fa6f89b9698ec3a0df55219459be8caec`；测试索引当前指向 `Kuaikan Manhua 1.6.19` / Android versionCode `106019`，APK 与 v106019 图标均已确认存在。
- v20 公共 `mx-build` run `33650942483` 已完整通过：Release 编译、固定签名身份、APK 证书、隔离测试商店生成与在线校验全部成功。
- v20 已发布到 `mx-repo/repo/test`，发布提交 `9497dc67ea9385784034b36de4ef97494ae65952`；测试索引当前指向 `Kuaikan Manhua 1.6.20` / Android versionCode `106020`，APK 与图标已确认存在。
- 2026-09-04 已用匿名官方 APP 请求探测确认作品评论 `/v1/graph/unified_feed` 可用；`feedType=50` 下 `pageId=6` 对应热门/默认候选，`pageId=7` 对应最新，返回真实帖子、用户、点赞数、回复数与下一页 `since`。
- 2026-09-04 已确认作品评论回复 `/v1/graph/posts/{postId}/comments/v5` 返回 `commentList`、回复作者、正文、点赞、时间与分页游标；v23 将作品评论回复改为 APP 优先。
- 2026-09-04 已确认章节评论 `/v2/comments/cruel/floor_list` 在匿名官方 APP User-Agent 下可返回 20 条，`order=score` / `order=time` 均可用，`normal_total` 可作为总评论数；v23 将该接口设为章评主链路。
- 2026-09-04 候选 APP-first 评论补丁在 `mx-build` run `33827548636` 已通过 Debug、Release、lint 与 R8；随后源码分支提交 `26fcd7d314a5f016e34b23575ca817942bdd2e83` 修正了 Trusted Check 唯一发现的 Spotless 空行格式问题。

### 待验证

- v23 最终源码仍需通过一次 Trusted Check、固定签名测试仓库构建与公开测试仓库提升；CI 全绿后仍需要用户实机验证，不得把构建成功等同于功能实测。
- v23 实机重点：作品评论默认/最热/最新是否与官方 APP 排序一致；作品评论回复能否完整打开并继续翻页；章节默认/最热/最新是否正常切换且不串页；章评总数是否与官方一致。
- v23 章节楼中楼回复仍保留旧只读回退。已探测 `/v1/graph/comments/{commentId}/reply/v2`，但当前真实样本返回“内容已被删除”，因此尚未把该路由作为正式主链路。
- v20 实机详情页章节目录是否恢复；重点复测用户截图中的《整容游戏》以及其它任意快看作品，确认不再显示“共 0 章 / 没有章节”。
- v19 PC WebView 登录是否能稳定获得 `passToken`，并能被 `/v1/passport/user` / 已购章节 API 识别。
- v19 章节评论标题栏总数是否与 PC reader 工具栏一致；用户反馈示例《怪奇实录》第1话 PC 页显示“评论 100”，v18 MX 错误显示“共 5 条”。
- v19 评论页底部登录/漫画源设置提示是否已消失。
- v18 已由用户实机确认可在 MX Test 中正常显示并打开；语言可见性问题已验证解决。
- MX 实机漫画评论入口、内容、头像、时间与分页。
- MX 实机章节评论。
- WebView 登录后 `session` Cookie 是否能稳定供 APP API 使用。
- 登录账号已购章节的 `✅/🔒` 判断与正文读取。
- 2026-09-02 测试发布链采用 `mx-build/extension-staging` → GitHub 已授权连接 → `mx-repo/repo/test` 的隔离流程；v17 已按此流程发布。当前测试工作流不会读取 `MX_REPO_TOKEN`，因此 v17 测试成功只能确认固定扩展签名与测试仓库发布链正常，不能据此宣称 `MX_REPO_TOKEN` 已通过权限验证。正式仓库发布前仍需单独验证该 Token 的 `mx-repo` Contents 写权限。

CI 绿色只能证明构建，不代表登录、评论、付费章节已经实机验证。

## 版本历史

- v1：基础浏览、详情、章节、正文、初版登录/评论候选。
- v2：加强官方登录入口、付费章节标记、评论 API 回退、MX ABI 兼容与官方图标。
- v3：内部开发候选；对齐官方 Source ID/语言、修正章节 source order，并把网页 Nuxt 评论设为优先。
- v14：首个准备对外测试的 MX 自制版本；版本号承接 Keiyoushi 官方快看源 v13，避免同 Source ID/包身份下出现仓库降级判断。
- v15：章节评论改为完整 20 条接口优先，并保持 `since` 游标分页；修正父回复 ID=0 误判，并从 hot-floor `children_total` 补充回复数。
- v16：加固跨域账号 Cookie 传递；章节评论改为真实 `since` 游标映射，并修正终止页回退重复问题。
- v17：评论网页固定 PC 域名 + Desktop User-Agent；章节先探测 PC reader，只有 PC 仅提供热门子集时才回退无跳转 JSON 完整评论流，明确不使用移动 H5 评论页。
- v18：修复 MX 商店语言过滤导致快看条目不可见；`lang` 由 `zh-Hans` 改为 `zh`，显式 Source ID 不变。
- v19：登录切换 PC 链路并识别 `passToken`；移除只读评论的登录提示；章节评论总数改用评论专属字段 + PC reader 实际计数。
- v20：修复 Desktop UA 下详情页章节字段从 `comicList` 变为 `comics` 导致所有作品显示 0 章的问题；两套字段同时兼容。
- v21：显式实现 `ChapterContentReplacementSource`，只让支持正文替换的插件显示 MX 替换入口。
- v22：接入 `SortableCommentSource`，书评与章评提供“默认 / 最热 / 最新”三档排序。
- v23：评论链路改为 APP API 优先：作品使用 `unified_feed`，作品回复使用 `posts/{id}/comments/v5`，章节使用 `cruel/floor_list`；网页和历史 JSON 路由保留为故障回退。

## v21：MX 正文替换入口改为插件显式启用

- MX 宿主不再给所有 HTTP 漫画源默认显示正文替换按钮。
- 快看 v21 显式实现 `ChapterContentReplacementSource`，因此仍可在章节列表和阅读器中调用 MX 正文替换能力。
- 未实现该接口的其它源默认不显示替换入口；评论入口继续只由 `CommentSource` capability 决定。
- 本变化只控制 MX UI 能力声明，不改变快看官方已购章节权限、登录或评论链路。

## v22：MX 评论三档排序

- 接入 MX 可选 `SortableCommentSource`，保留基础 `CommentSource` ABI。
- 书评和章评统一提供“默认 / 最热 / 最新”三个筛选项。
- “默认”保留 v21 及以前已经实机使用的多级回退链，不改变原评论读取行为。
- “最热”优先使用 PC 热门评论、章节 `order=score`、`hot_comments` 与 hot-floor 系列接口，不会回退到时间流。
- “最新”作品评论使用现有 `/v1/comments/feed/{topicId}/order/time` 时间流；章节评论使用 `/v1/comics/{comicId}/comments/{cursor}?order=time`。
- 章节评论游标缓存按“章节 ID + 排序模式”隔离，切换默认 / 最热 / 最新不会串用上一种排序的 `since`。
- “最新”不会静默回退成热评；接口不可用时明确报错，避免 UI 显示“最新”但实际数据仍是热门。
- **待实机验证**：章节 `order=time` 链路目前按同一官方评论接口的排序参数实现；在用户设备验证前不标记为已验证。

## v23：APP 评论主链路

- 评论读取改成“官方 APP API 优先，PC/历史 JSON 仅回退”。
- 作品评论使用 `POST /v1/graph/unified_feed`，`feedType=50`；默认/最热采用 `pageId=6`，最新采用 `pageId=7`，后续页按返回的 `since` 游标继续。
- 作品评论卡片解析官方 `post` 数据：标题、正文、作者、头像、发布时间、点赞数、回复数和当前点赞状态。
- 作品评论回复使用 `GET /v1/graph/posts/{postId}/comments/v5`，解析 `postReply.root`，并保存下一页 `since`。
- 章节评论使用 `GET /v2/comments/cruel/floor_list`，`target_type=comic`；默认/最热传 `order=score`，最新传 `order=time`，使用 `normal_total` 作为总数并按官方 `since` 分页。
- APP 请求仍使用独立无 CookieJar 客户端，并显式合并当前快看账号 Cookie；匿名情况下使用已在线验证可接受的官方 APP User-Agent。
- 章节楼中楼回复暂不切换到未经确认的 Graph 回复接口，继续保留 v22 只读回退，避免为了追求“全 APP”引入已知回归。
