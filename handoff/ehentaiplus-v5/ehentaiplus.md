# E-Hentai Plus

## 定位

MX / Mihon / Komikku 兼容的 E-Hentai / ExHentai 扩展。模块路径：`src/zh/ehentaiplus`。

实现参考：

- Keiyoushi / Tachiyomi 系 E-Hentai 扩展的画廊列表、筛选、详情与阅读链路。
- JHenTai 的论坛账号密码登录流程、Cookie 登录思路、`hc=1` 全部评论行为、评论提交表单以及 `nl(...)` 图片服务器重试机制。
- Venera 的表站 / 里站切换、Cookie 字段和中文化体验。
- EhTagTranslation `DatabaseReleases/db.html.json` 作为可选中文标签数据源。

## 功能范围

- 表站：`https://e-hentai.org`
- 里站：`https://exhentai.org`
- 站点模式：自动 / 表站 / 里站；自动模式仅在账号验证确认里站可用时优先 ExHentai。
- 默认中文：浏览与搜索默认加入 `language:chinese`，可在设置或搜索筛选中关闭。
- 账号：支持论坛账号密码登录；登录请求按论坛 `act=Login&CODE=01` 流程提交，并保存站点返回的登录 Cookie。
- Cookie：支持手动填写 `ipb_member_id`、`ipb_pass_hash`、`igneous`、`star`，并支持从宿主 WebView 同步 Cookie。
- 账号状态：实现 MX `AccountSource`，区分未登录、检测到 Cookie 但待验证、已验证登录三个状态，并显示 ExHentai 权限结果。
- 搜索：标题 / 标签搜索、女性标签、男性标签、分类、最低评分、页数区间、高级选项、收藏、监视列表、直接画廊 URL / `gid/token` 导入。
- 详情：实现 MX `MangaDetailSource`；分类、上传者、语言、时间、文件大小、页数、收藏次数、评分、画廊 ID、站点、分组标签等结构化显示。标签点击使用原始 E-Hentai 查询串，避免中文翻译破坏检索。
- 中文标签：默认开启。优先使用本机已缓存翻译；遇到未缓存标签时尝试读取 EhTagTranslation 数据库，失败自动回退英文原标签。
- 评论：实现 MX `CommentSource`；画廊评论读取，默认 `hc=1` 请求全部评论；解析作者、论坛用户 ID、正文、时间和投票分数；账号验证成功后支持发表画廊评论。
- 评论限制：E-Hentai 评论不是线程回复模型，因此不声明回复能力；站点是“赞 / 踩投票”而 MX 当前 ABI 是布尔 Like，语义不等价，因此不声明 Like 能力，避免错误写操作。
- 阅读：遍历缩略图分页，逐个解析 viewer 页面得到正文图片；可选优先原图；v4 对图片缓存、响应校验与备用图片服务器做额外防御处理。
- 深链：支持 `e-hentai.org/g/.../...` 与 `exhentai.org/g/.../...`。

## 安全与登录

- 仓库不包含任何真实账号、密码、Cookie、Token 或签名密钥。
- 账号密码只保存在用户设备的扩展 SharedPreferences；密码登录成功后立即清除保存的密码字段，保留登录 Cookie。
- ExHentai 可用性必须单独验证；论坛登录成功不等于账号必然具有里站权限。
- 手动 Cookie、WebView Cookie 与密码登录 Cookie 均只在本机处理，不写入源码或日志。

## 当前验证状态

2026-09-05 当前状态：**E-Hentai Plus v1.6.5（Android `versionCode=106005`，源码递增号 `versionCode=5`）进入实机优化候选：v1.6.4 已由用户确认正文恢复可读，但图片加载速度仍偏慢，且登录状态/网页登录体验仍需继续优化。v1.6.5 构建与发布验证完成前不得视为已验证。**

已验证：

- v1.6.1 为首版实机测试基线。
- v1.6.2 的构建与发布链通过，但用户实机继续出现 `IllegalStateException: Failed to initialize decoder`，因此该问题不能视为已解决。
- v1.6.3 的构建、Lint、固定签名和测试仓库发布通过，并增加图片 CDN Cookie 与账号状态持久显示；仍继续按实机反馈深化修复。
- v1.6.4 的 `mx-build` run `33933377134` 完整成功：v4 patch、当前 Keiyoushi Spotless、公共 E-Hentai reader smoke、Release、Lint、长期固定签名证书复核、测试仓库 staging 生成与在线校验全部通过。
- v1.6.4 的 `mx-repo` `Promote MX Extension Test Store` run `33933618047` 完整成功，公开测试仓库已发布并在线复核。
- 公开 `repo/test/index.json` 已确认 `eu.kanade.tachiyomi.extension.zh.ehentaiplus` 为 `1.6.4 / 106004`，APK 为 `test/apk/tachiyomi-zh.ehentaiplus-v1.6.4.apk`。
- v1.6.4 经验证源码已经写回 `feat/ehentai-plus`：主要源码提交 `fc450d36ee1ff4ffea4843b94b148e6d23ec7ef7`，版本递增提交 `b1c36a265f599c878e3213b0bd9359740f759e6b`。

仍需重点实机确认：

1. v1.6.4 打开此前 v1-v3 已经失败过的画廊时，是否不再复用旧错误图片缓存并彻底消除 `Failed to initialize decoder`。
2. 再测试一本此前从未打开过的画廊，区分旧缓存影响与当前网络 / CDN 问题。
3. 设置页顶部是否明确显示“账号状态：未登录 / 待验证 / 已登录”，登录或验证后是否显示用户名、E-Hentai 验证结果、ExHentai 权限和“最近操作”。
4. 论坛账号密码登录是否能稳定取得有效 Cookie，且不会被旧 WebView Cookie 反向覆盖。
5. 手动填写 `ipb_member_id` / `ipb_pass_hash` / `igneous` / `star` 后执行验证，是否真正以这些值访问站点。
6. WebView 同步 Cookie 后执行验证，是否能正确区分 E-Hentai 登录有效与 ExHentai 里站权限。
7. 正文首线路异常时，`nl(...)` 备用图片服务器重试是否能在实机自动恢复；若两条线路都失败，是否返回明确网络 / Content-Type / 图片文件头错误而不是模糊 decoder 异常。
8. 评论读取是否保持正常；只有账号验证成功后才应开放发表评论能力。

CI / Release / 签名 / 测试仓库通过只能证明代码与公开网络链路在 CI 环境可构建、可请求，不能替代 MX 实机功能验证。

## v2 实机修复记录（2026-09-05）

根据 v1.6.1 实机反馈：

- 正文从伪造 loopback imageUrl + OkHttp interceptor 改为当前 KeiSource 官方 `getImageUrl(Page)` 懒解析链路。
- 尝试处理 `s.exhentai.org` / `ehgt.org` 与 509 图片配额占位图。
- 评论页未登录时不再声明可发表评论，减少宿主无意义登录提示。
- 评论作者选择器兼容当前评论 HTML；评论时间统一数字显示。
- 设置页新增账号状态显示。
- 扩展图标替换为 E-Hentai 官方 favicon。

结果：构建与公开 reader smoke 通过，但用户实机仍出现 decoder 异常，因此继续进入 v3/v4。

## v3 实机修复记录（2026-09-05）

根据 v1.6.2 实机反馈继续处理：

- 最终图片 CDN 请求显式携带当前 E-Hentai Cookie，包括 `*.hath.network`。
- 不再提前无条件改写 viewer 返回的真实图片地址。
- 用内部图片请求标记让 OkHttp interceptor 在响应交给宿主前检查 `Content-Type`，非 `image/*` 不再直接进入解码器。
- 章节 URL 加入 `mx_reader=3` 修订标记，用于刷新 PageList 缓存键。
- 设置页增加可持续显示的账号状态和最近操作结果。

进一步复查发现：仅修改章节 URL 仍不足以保证 MX 不复用旧的最终图片 URL 缓存；此外 v1-v3 的手动 Cookie 字段虽然能被本地状态读取，却没有完整进入 `cookieHeader()` 的实际网络请求。上述问题进入 v4 修复。

## v4 实机修复候选（2026-09-05）

继续针对实机 `Failed to initialize decoder` 与账号状态不明确做防御性修复：

- 直接图片 URL 增加仅用于宿主缓存键的 `#mxeh-4` fragment。fragment 不会发送给图片服务器，但会让 MX 图片缓存与 v1-v3 的旧错误缓存分离；此前只修改章节 URL，旧的最终图片 URL 缓存仍可能被复用。
- 图片响应不再只检查 `Content-Type: image/*`，同时检查 JPEG / PNG / GIF / WebP / AVIF/HEIF 文件头。站点/CDN 即使把错误页伪装成图片 MIME，也不会再写入阅读缓存。
- 图片响应无效时，扩展利用当前 viewer 的 `nl(...)` reload token 自动请求备用图片服务器，再把备用图片响应交给宿主；不依赖 MX 内置 E-Hentai 专用 retry 特判。
- viewer 和正文图片请求显式 `Cache-Control: no-cache`，减少旧 viewer / CDN 错误响应被网络缓存复用。
- 手动填写的 `ipb_member_id` / `ipb_pass_hash` / `igneous` / `star` 现在真正进入请求 Cookie。修复此前“本地状态看起来有 Cookie、实际请求没有完整携带”的问题。
- 密码登录成功后不再立即从 WebView 反向同步 Cookie，避免新论坛 Cookie 被 WebView 中旧值覆盖；验证成功后再把已确认 Cookie 同步回 WebView。
- WebView Cookie 的普通后台同步改为只补缺失值；只有用户显式点击“同步 WebView Cookie”时才覆盖现有 Cookie。
- 新增 `sessionValidated` 状态，把“存在 Cookie”和“账号已验证”分开。设置页标题直接显示“账号状态：未登录 / 待验证 / 已登录”，摘要显示用户名、E-Hentai 验证结果、ExHentai 权限和最近操作。
- 发表评论能力只在账号真正验证成功后开启，避免仅存在失效 Cookie 时宿主误判为可发评论。
- 退出登录同时清除自动 Cookie、手动 Cookie 和验证状态，避免手动字段残留导致退出后仍显示已登录。

验证边界：v4 的当前 Keiyoushi Spotless、Release、Lint、固定签名、公共 reader smoke、测试仓库生成和公开测试仓库发布已经验证；正文解码与账号状态仍以 MX 实机反馈为最终判断。


## v5 实机优化候选（2026-09-05）

基于 v1.6.4 用户实机反馈：正文已经恢复可读，decoder 问题暂时视为实机修复成功；本轮继续优化加载速度与账号登录体验。

- 正常 viewer 与正文图片请求恢复 HTTP/宿主缓存，不再对每一页无条件发送 `Cache-Control: no-cache`；v4 的图片文件头校验、错误响应拦截和 `nl(...)` 备用图片服务器重试继续保留。
- 新增 30 分钟 viewer→最终图片 URL 内存缓存，重复打开、返回上一页或宿主重试时不再重复请求同一 viewer 页面。
- `READER_REVISION` 保持 4，不主动清掉已经确认可用的 v4 图片缓存，避免升级后重新全量冷加载。
- 未登录时漫画源“网页”主页改为 E-Hentai 论坛登录页，便于直接在宿主内置 WebView 登录。
- 设置页新增“网页登录（推荐）”：通过宿主自己的 WebViewActivity 打开论坛登录页；登录过程中轮询 Android WebView CookieManager，一旦获得 `ipb_member_id` / `ipb_pass_hash` 就自动写入扩展、验证账号、检测 ExHentai 权限并刷新登录状态。
- 如果宿主不兼容该内部 WebView Activity，保留“漫画源页面 → 网页”登录的兼容回退，不直接崩溃。
- 手动 Cookie 字段修改后自动同步回宿主 WebView；当 `ipb_member_id` / `ipb_pass_hash` 齐全时自动尝试验证，避免“字段填好了但网页仍显示未登录”。
- `AccountSource.getSourceAccount()` 在检测到 Cookie 但未验证时会自动验证；失效 Cookie 不再作为已登录账号返回给 MX。
- 设置页账号状态改为醒目的 `✅ 已登录 / ⚠️ 已获取 Cookie，待验证 / ❌ 未登录`，点击状态本身即可强制重新读取网页登录 Cookie 并验证。
- 搜索筛选顶部同步显示当前账号状态，便于不进入设置页时快速确认。

验证边界：v1.6.5 仍需当前 Keiyoushi Spotless、Release、Lint、固定签名、公共 reader smoke 与测试仓库发布验证；网页登录自动 Cookie 捕获、状态刷新和实际加载速度改善必须以 MX 实机结果为准。
