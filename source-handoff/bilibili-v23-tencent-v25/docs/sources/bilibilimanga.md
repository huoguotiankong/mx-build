# 哔哩哔哩漫画

## 当前实现

- 模块：`src/zh/bilibilimanga/`
- 主站：`https://manga.bilibili.com`
- 当前版本：`versionCode = 23`
- 技术基线：Kotlin + `@Source` + `KeiSource` + `libVersion = "1.6"`
- 支持匿名浏览，也支持复用 Mihon / Komikku 源 WebView 中登录后的 Bilibili Cookie 会话。

## 已实现功能

- 热门漫画 / 最新更新 / 搜索 / 分类筛选
- 漫画详情、章节目录、正文图片
- Bilibili 图片 Token
- Bilibili 账号 WebView 会话
- 已购买 / 已解锁章节读取（以账号实际权限为准）
- 已解锁付费章节显示 `✅`，未解锁继续显示 `🔒`
- 仍锁定的付费章节显示 `🔒`
- 五档官方 launcher 图标
- MX 原生评论：使用 Bilibili PC 网页同款官方 WBI 评论接口；漫画评论 `type=22`，章节评论 `type=29`，支持分页与只读回复查看
- MX 源账号状态：向 MX 仅暴露脱敏后的账号 ID、昵称、头像和个人主页，不暴露 Cookie / Token / CSRF

## 账号登录与章节权限

v9 不保存用户名或密码，也不把任何账号 Cookie 写入 GitHub。登录改由扩展设置页提供独立入口：打开本源设置，点击“登录 Bilibili 账号（PC 网页）”，扩展会以同一 Source WebView 打开 `https://www.bilibili.com/cheese/mine/list`，并让 WebView 使用桌面 Chrome UA。该页面在未登录状态会提供 Bilibili 官方 PC 登录界面。登录完成后返回客户端刷新漫画详情/章节目录。漫画站 `manga.bilibili.com` 的移动首页自身没有可靠登录入口，因此不再要求用户从漫画首页寻找登录按钮。

登录成功后扩展从宿主 CookieJar 读取 Bilibili 官方登录 Cookie（核心判断为 `SESSDATA`），并与匿名访问需要的 `buvid3/buvid4` 合并后发送到漫画 API。v6 之前 `postApi()` 会用匿名指纹 Cookie 覆盖宿主已有登录 Cookie，因此即使 WebView 已登录，API 仍按匿名用户处理；v7 已修正这一点。

章节标记：

- 免费章节：不加图标。
- 已登录账号下，不再直接使用 `ComicDetail.is_locked` 作为最终账号权益。实机已经确认该字段可能仍是 `true`，但账号实际能够读取该章节。
- 扩展先通过 `user.v1.User/GetAutoBuyComics` 获取当前漫画的已购章节数量，再以只读的 `comic.v1.Comic/GetEpisodeBuyInfo` 对需要确认的章节进行账号级权益判断。
- 已确认可读的付费章节显示 `✅`；未解锁或无法确认权限的付费章节显示 `🔒`。
- 已登录时，即使缓存目录仍带 `locked=1`，阅读器也会重新尝试官方 `GetImageIndex`；如果账号确实无权限，再返回明确的权限错误。

注意：扩展不会购买章节、不会绕过付费、不会绕过 DRM。`✅` 只表示 Bilibili 官方接口对当前登录会话返回可读权限。

## 当前 API 契约

匿名访问首次请求 `https://api.bilibili.com/x/frontend/finger/spi` 获取 `buvid3/buvid4`。漫画接口继续使用 Android 漫画请求轮廓：

- `comic.v1.Comic/ClassPage`
- `comic.v1.Comic/ComicDetail`
- `comic.v1.Comic/GetImageIndex`
- `comic.v1.Comic/ImageToken`
- 搜索：`search.v1.Search/SearchKeyword`
- 账号已购摘要：`user.v1.User/GetAutoBuyComics`
- 单章账号权益：`comic.v1.Comic/GetEpisodeBuyInfo`

账号模式在上述请求中合并宿主 CookieJar 的 `SESSDATA`、`bili_jct`、`DedeUserID` 等官方 Bilibili Cookie。

## MX 原生评论

v14 开始为自制 MX 客户端提供可选增强能力。扩展仍保留标准 Tachiyomi/Mihon/Komikku `Source` 行为；MX 专用接口隔离在 `eu.kanade.tachiyomi.source.mx.*` 命名空间。扩展 APK 内携带与 MX 一致的兼容 stub，普通宿主可正常加载并忽略这些附加能力；MX 客户端则由 host-first 类加载规则复用宿主侧同一套接口类型。

2026-09-02 实网复核发现，Bilibili 当前移动漫画详情页展示的“漫画点评”**不是**此前假设的通用 `type=22 / oid=mcid` 评论区。对《咒术回战》`mc26505` 的真实探针结果为：

- 页面 SSR / hydration 中可直接解析到 6 条真实“漫画点评”，位于 `vike_pageContext.data.comments`。
- 每条点评当前包含 `id`、`face`、`nick_name`、`ctime`、`content`、`score`。
- 同一漫画调用 `GET https://api.bilibili.com/x/v2/reply?type=22&oid=26505` 虽然返回 `code = 0`，但 `replies` 为空；因此不能把该接口的成功状态当作漫画点评数据可用。
- `inreview=0/1/2/3` 均返回同一组 6 条 hydration 点评，它不是分页参数。
- 当前移动详情页没有暴露可验证的点评分页、回复、点赞或发表接口契约。

因此当前 v14 能力收敛为：

- 漫画级点评读取：启用，直接读取 Bilibili 当前网页真实展示的数据。
- 章节级评论：不启用。
- 分页：不宣称支持；当前只返回网页 hydration 中实际提供的点评集合。
- 回复：关闭。
- 发表评论：关闭。
- 点赞 / 取消点赞：关闭。
- 账号状态：继续实现 `AccountSource`，优先通过 Bilibili `/x/web-interface/nav` 获取脱敏账号信息，失败时退回当前登录 Cookie 中的用户 ID。

这样做的原则是：**宁可少暴露能力，也不把一个返回空列表的错误 API 伪装成已完成的评论功能。** 后续只有在真实接口或用户实机网络抓包能够证明分页/写入/互动契约后，才会重新打开对应 capability。

当前读取契约：

- 页面：`GET https://manga.bilibili.com/m/detail/mc<mcid>?inreview=0`
- hydration script：`<script id="vike_pageContext" type="application/json">...</script>`
- 点评数组：`data.comments`
- 当前账号信息：`GET https://api.bilibili.com/x/web-interface/nav`

兼容性约束：

- MX 专用接口只允许位于 `eu.kanade.tachiyomi.source.mx.*`。
- 不把 MX 能力塞入标准 `Source` / `source.model` 命名空间。
- 普通 Tachiyomi/Mihon/Komikku 宿主缺少 MX UI 时，原有浏览、目录、正文、登录和账号权益功能仍应保持不变。
- 只有 MX 检测到 `CommentSource` 时才展示原生评论入口。

验证状态：

- **真实网站数据契约已验证**：匿名探针可从 `vike_pageContext.data.comments` 读取真实漫画点评，并校验关键字段。
- **错误假设已撤销**：不再使用空的 `type=22 / oid=mcid` 通用评论区作为漫画点评。
- **当前修复分支已写入**：v14 改为真实网页点评只读实现，回复/发帖/点赞 capability 已关闭。
- **MX ABI 已同步**：扩展内 `Comment` 兼容 stub 与 MX 宿主保持相同字段顺序，包含 `displayTime`；相对时间直接交给宿主显示，避免运行时构造器签名不一致。
- **Extension Check 已通过**：v14 当前 Keiyoushi 全仓 Extension Check 成功。
- **私有签名构建已通过**：v14 使用长期稳定扩展签名构建并验证证书成功。
- **MX Test 商店已发布**：v14 已进入隔离测试商店 `https://github.com/huoguotiankong/mx-repo/raw/repo/test/repo.json`，不会改动正式仓库索引。
- **v15 MX 宿主联调已通过**：用户实机确认漫画详情页评论入口、头像、昵称、正文、时间均可显示。
- **v16 实机阻塞已确认**：MX 评论入口与章节入口可打开，但用户实机出现 `Could not load comments`；此前宿主吞掉了扩展异常文本，无法判断是 WBI、HTTP、Bilibili code、参数还是 JSON 解析层。
- **v17 实机诊断已完成**：用户实机明确返回 `阶段=WBI密钥；HTTP=200；endpoint=/x/web-interface/nav；Cookie=无；code=-101；message=账号未登录`。这证明请求和 JSON 解析均正常，阻塞点是扩展错误地把匿名 `/nav` 的 `-101` 当成 WBI 密钥失败。
- **未发布正式 v14**：至少完成一轮 MX 实机评论联调前不进入正式仓库。

## v17 评论诊断说明

v17 不记录、不显示 Cookie、Token、WBI 签名值或完整响应体。错误信息只保留阶段、HTTP 状态、Bilibili `code/message`、安全参数（type/oid/page/mode）、当前评论客户端是否携带 Cookie，以及 JSON 响应类型/长度，用于用户截图反馈时快速定位故障层。

当前 PC WBI 评论客户端仍使用独立 `CookieJar.NO_COOKIES`，因此诊断中会明确显示“Cookie=无”；若实机结果证明匿名 WBI 被风控，再根据 v17 错误码决定是否引入指纹 Cookie 或登录 Cookie 回退，不提前改变已验证的匿名请求模型。

### 2026-09-04 v22：评论图片显示

- MX 客户端已经支持从评论正文中的直链识别并原生渲染图片；扩展侧不新增私有 ABI 字段，继续保持 `Comment.content` 兼容。
- Bilibili PC WBI 评论响应中的评论图片位于 `content.pictures[]`，优先读取官方 `img_src`，并兼容 `img_url/url` 字段。
- 图片地址统一规范为 HTTPS 后追加到评论正文数据中，由 MX 富媒体评论渲染器显示；文字与多张图片会同时保留。
- 图片为空但存在文字时保持原行为；只有图片没有文字的评论也不会再被丢弃。
- 主评论与 `/x/v2/reply/reply` 回复列表共用 `commentFromReply()`，因此漫画评论、章节评论和已有回复都自动获得相同的图片读取能力。
- 普通 Mihon / Komikku 不识别 MX `CommentSource` 时不受影响。
- 本版本只增加只读图片展示，不改变评论排序、分页、账号 Cookie 隔离及任何写操作能力。

### 2026-09-04 v23：评论图片与特殊表情实机修复

用户实机确认 v22 虽然已经读取 `content.pictures[]`，但 MX 评论页仍把部分图片 URL 当普通文字显示；同时 Bilibili 特殊表情没有按图片渲染。根因是 v22 把图片 URL 直接拼进 `Comment.content`，依赖宿主对 URL 形态做启发式识别，而腾讯/Bilibili CDN URL 并不总以传统图片后缀结束。

v23 改为显式富媒体标记：

- `content.pictures[]` 仍读取官方 `img_src/img_url/url`，但统一输出为 Markdown 图片 `![](https://...)`，不再依赖宿主猜测 URL 是否为图片。
- 解析 Bilibili 评论 `content.emote` 映射，把正文中的 `[表情]` token 替换为对应官方表情图片 URL 的 Markdown 图片。
- 对新版响应可能提供的 `content.rich_text_nodes` 增加兼容：当原始 message 为空或包含 Unicode replacement character (`U+FFFD`) 时，优先用 rich-text nodes 重建正文，并读取 `emoji.icon_url/url`、`emoji_url`、`image.url/src`。
- 主评论和回复继续共用 `commentFromReply()`，因此漫画评论、章节评论和回复统一生效。
- 只读能力不变，不开放发表评论、回复或点赞写操作。

验证边界：源码构建与 Release 检查通过后仍需 Android 实机确认图片/表情最终显示效果。

## 图片加载

- 封面：最高 `512w.jpg`。
- 正文：最高 `1200w.jpg`，原图不足 1200px 不放大。
- `GetImageIndex` 获取 path，`ImageToken` 获取最终 CDN URL。
- CDN 图片本身不附带账号 Cookie；授权信息由图片 Token 提供。

## 历史与验证状态

- v4：匿名基础版正式发布。
- v5：恢复完整目录、付费章节标记、官方 launcher 图标。
- v6：调整付费字段判断并优化封面/正文尺寸；实机仍反馈《咒术回战》0卷未显示 `🔒`，不再继续围绕该显示问题单独迭代。
- v7：加入 Bilibili WebView 登录 Cookie 会话、账号权限读取、`🔓/🔒` 双状态章节标记。
- v7 源码：已写入 `main`。
- v7 匿名 API 探测：通过，未破坏匿名浏览链路。
- v7 专用构建与全仓 Extension Check：通过。
- v7 正式签名 APK/JAR：构建和签名校验通过。
- v7 `repo` 索引：已发布 `1.6.7`，CDN purge 成功。
- v7 账号实机：发现漫画移动首页没有登录入口，因此账号会话功能无法实际完成登录。
- v8：新增源设置中的“登录 Bilibili 账号”入口，直接用同一 Source WebView 打开 Bilibili 主站官方登录页。
- v8 专用构建：通过。
- v8 全仓 Extension Check：通过。
- v8 匿名 API 探测：通过。
- v8 正式签名 APK/JAR：构建和签名校验通过。
- v8 `repo` 索引：已发布 `1.6.8`，APK/JAR/icon/index 已更新，CDN purge 成功。
- v8 登录入口实机：设置页为空白，且移动网页不提供可靠登录入口。
- v9：登录设置项改用 Keiyoushi 当前可渲染的 `EditTextPreference(screen.context)`；WebView 使用桌面 Chrome UA；登录地址改为 Bilibili PC 账号页面。
- v9 专用构建：通过。
- v9 全仓 Extension Check：通过。
- v9 匿名 API 探测：通过。
- v9 正式签名 APK/JAR：构建与签名校验通过。
- v9 `repo` 索引：已发布 `1.6.9`，APK/JAR/icon/index 已更新，CDN purge 成功。
- v9 设置页 / PC 登录 / 已解锁章节实机：已验证。PC 登录成功，账号已解锁章节可正常读取；用户反馈 `🔓` 与 `🔒` 视觉上容易混淆。
- v10：已解锁章节曾改为 `✅ 已解锁`，同时增加 SESSDATA Cookie 预计到期时间显示。
- 登录会话由 Bilibili 官方 Cookie 决定，通常可跨 App 重启和扩展更新继续使用；如果 Cookie 到期、主动退出、账号安全策略触发或 Bilibili 主动撤销会话，则需要重新登录。
- 用户反馈希望仍使用锁形图标，而不是文字；要求开锁图形与 `🔒` 明显区分。
- v11：已解锁章节改为纯开锁符号 `🔓︎`（U+1F513 + text presentation），不再添加“已解锁”文字；未解锁保持 `🔒`。
- v11 专用构建：通过。
- v11 全仓 Extension Check：通过。
- v11 匿名 API 探测：通过。
- v11 正式签名 APK/JAR：构建与签名校验通过。
- v11 `repo` 索引：已发布 `1.6.11`，APK/JAR/icon/index 已更新，CDN purge 成功。
- v11 实机：Android / Komikku 字体仍把 `🔓︎` 渲染得与 `🔒` 非常接近，区分度不足。
- v12：已解锁章节改为单独 `✅`，未解锁继续 `🔒`；不增加文字，不额外占用章节名空间。
- v12 专用构建：通过。
- v12 全仓 Extension Check：通过。
- v12 匿名 API 探测：通过。
- v12 正式签名 APK/JAR：构建与签名校验通过。
- v12 `repo` 索引：已发布 `1.6.12`，APK/JAR/icon/index 已更新，CDN purge 成功。
- v12 实机：用户继续反馈账号实际可读的已解锁章节仍显示 `🔒`，确认根因不是 Emoji 样式，而是 `ComicDetail.is_locked` 不能代表当前账号最终权益。
- v13：改为账号级权益判断。登录后结合 `GetAutoBuyComics` 与 `GetEpisodeBuyInfo` 识别已购章节；已解锁显示 `✅`，未解锁显示 `🔒`。
- v13：成功读取付费正文后也会把该章节记为当前账号已解锁，后续刷新可直接命中本地缓存。
- v13：已解锁权益缓存 6 小时、未解锁缓存 5 分钟、已购数量缓存 10 分钟；缓存按账号隔离。
- v13 专用构建：通过。
- v13 全仓 Extension Check：通过。
- v13 匿名 API 探测：通过；`GetAutoBuyComics` 与 `GetEpisodeBuyInfo` 匿名请求均返回登录认证错误而非 404，确认当前路由存在。
- v13 正式发布：待发布工作流完成。
- v13 账号权益实机：待验证。
- v14：加入 MX 原生漫画点评能力。初版对通用 `type=22 / oid=mcid` 评论区的判断经实网复核为错误：接口返回成功但数据为空。修复版改为解析移动详情页 `vike_pageContext.data.comments` 中实际展示的点评；当前只读，关闭回复、发表和点赞，待 CI / MX 联调 / 实机验证。

## 权益探测与缓存

- `GetAutoBuyComics` 与 `GetEpisodeBuyInfo` 仅用于读取当前账号已有权益；扩展不会调用 `BuyEpisode`、充值、支付、订单创建等交易接口。
- 对章节很多且账号确有购买记录的漫画，首次刷新目录可能比匿名模式稍慢；后续刷新优先使用本地缓存。
- 如果账号刚购买新章节，未解锁缓存最多约 5 分钟后会重新确认；成功打开正文时会立即记录为已解锁。

## 已知限制

- Bilibili 账号权限无法在 CI 中使用真实账号自动验证；最终以用户实机账号结果为准。
- Bilibili 可能继续调整 Cookie、接口、DRM 或图片加密策略。
- 账号失效时需要重新在源 WebView 登录。
- 当前不实现自动购买、充值等交易能力。


### 2026-09-02 v15：评论入口实机缺失修复

用户实机确认：MX 已识别并安装 Bilibili Manga v1.6.14，但漫画详情页没有评论入口。检查 v14 Release APK 的 `classes.dex` 后确认，R8 已将扩展内用于 MX 兼容的 `eu.kanade.tachiyomi.source.mx.*` 接口/模型重命名，导致 MX 宿主即使采用该命名空间 parent-first 加载，也无法把源识别为宿主 `CommentSource`。

v15 修复策略：

- 保留整个 `eu.kanade.tachiyomi.source.mx.**` 的二进制类名和成员名，维持宿主/扩展 ABI 身份。
- Release 私有构建新增 APK 级校验，要求 `classes*.dex` 中仍存在 `CommentSource` 描述符和 `commentCapabilities` 成员名，防止后续 R8 再次破坏 ABI。
- 仍使用长期稳定扩展签名；先进入 MX Test 实机联调，不提前进入正式索引。


### 2026-09-02 v16：PC 网页评论与章节评论

用户实机确认 v15 评论入口可用，但移动详情页 hydration 仅提供少量“漫画点评”。v16 改用 Bilibili PC 网页实际使用的官方评论体系，不再以移动端 `vike_pageContext.data.comments` 作为 MX 评论主数据源。

实网匿名探针已经验证：

- PC 漫画详情页评论：`GET https://api.bilibili.com/x/v2/reply/wbi/main`，`type=22`，`oid=<mcid>`。
- 《咒术回战》`mc26505`：首屏匿名无 Cookie 返回 20 条，`all_count = 11847`；第二页继续返回 20 条。
- 同一 WBI 请求若带 `buvid3/buvid4`，首屏可能被限制到 3 条，因此 v16 评论读取使用独立无 Cookie 客户端；账号登录 Cookie 不会被发给评论读取链路。
- PC 漫画阅读器章节评论：同一 WBI endpoint，`type=29`，`oid=<episodeId>`。
- 探针样本：章节 `ep2113323` 首屏 20 条、总数 115；`ep2111966` 首屏 20 条、总数 45；`ep2110821` 首屏 20 条、总数 63。
- WBI 签名密钥从官方 `/x/web-interface/nav` 的 `wbi_img` 读取，按 Bilibili 当前 WBI 规则计算 `w_rid`。
- 主评论分页使用 `cursor.pagination_reply.next_offset`；MX 的页码接口在扩展内部映射到 Bilibili cursor。
- 子回复读取使用官方 `/x/v2/reply/reply`；当前仍保持只读，不开放发表、回复、点赞写操作。

因此 v16 capability：

- 漫画级评论：启用。
- 章节级评论：启用；MX 章节列表会自动出现章节评论气泡入口。
- 主评论分页：启用。
- 子回复查看：启用。
- 发表评论 / 回复 / 点赞写操作：保持关闭。

### 2026-09-02 v18：匿名 WBI 密钥兼容 `code=-101`

用户实机 v17 诊断确认：匿名请求 `GET https://api.bilibili.com/x/web-interface/nav` 返回 HTTP 200、`code=-101`、`message=账号未登录`。Bilibili 在该匿名响应的 `data.wbi_img` 中仍提供公开的 `img_url/sub_url` WBI 密钥材料，因此 `-101` 不能在“获取 WBI 密钥”阶段直接视为失败。

v18 修复：

- 仅在 `/x/web-interface/nav` 的 WBI 密钥请求中允许 `code=0` 或 `code=-101` 继续解析。
- 仍要求 `data.wbi_img.img_url/sub_url` 存在且密钥长度有效；缺失时继续报错。
- 主评论、章节评论和回复 API 仍只接受 `code=0`，不会放宽其它 Bilibili 错误码。
- 评论读取仍保持独立匿名 `CookieJar.NO_COOKIES`，避免登录 Cookie 或指纹 Cookie 改变 PC 评论返回量。
- 本次修复只解决 v17 实机已经定位到的 WBI 密钥阻塞；评论接口本身是否还触发后续风控，以 v18 实机结果为准。


### 2026-09-03 v19：正文替换入口改为插件显式启用

- MX 宿主不再对所有 HTTP 漫画源默认显示正文替换入口。
- Bilibili Manga v19 显式实现 `ChapterContentReplacementSource`，因此保留章节列表与阅读器正文替换入口。
- 其它未实现该接口的漫画源默认不显示替换按钮；评论入口继续只由 `CommentSource` capability 决定。
- 这是宿主能力声明，不改变 Bilibili 官方付费/已购章节权限判断，也不绕过任何官方付费机制。


### 2026-09-03 v20：评论排序

- 接入 MX 可选 `SortableCommentSource`，不改变原 `CommentSource` ABI。
- 书评和章评均提供“热门 / 最新”排序。
- Bilibili WBI 主评论接口：热门使用 `mode=3`，最新使用 `mode=2`。
- 分页 offset 缓存按“评论目标 + 排序模式”隔离，切换排序不会串页。
- 默认仍为“热门”。


### 2026-09-03 v21：评论三档排序

- MX 原生书评与章评统一提供“默认 / 最热 / 最新”三个排序项。
- “默认”使用 Bilibili WBI 评论接口 `mode=1`（热度 + 时间综合排序）。
- “最热”使用 `mode=3`（纯热度）。
- “最新”使用 `mode=2`（纯时间）。
- 默认选中“默认”；旧的基础 `CommentSource.getComments()` 也走“默认”，避免宿主没有排序 UI 时固定成纯热度。
- 分页 offset 缓存继续按“评论目标 + 排序 mode”隔离，三个排序互不串页。
- 本次只调整评论排序，不改变登录、章节权限、正文、回复只读等现有逻辑。
