# E-Hentai Plus

## 定位

MX / Mihon / Komikku 兼容的 E-Hentai / ExHentai 扩展。模块路径：`src/zh/ehentaiplus`。

实现参考：

- Keiyoushi / Tachiyomi 系 E-Hentai 扩展的画廊列表、筛选、详情与阅读链路。
- JHenTai 的论坛账号密码登录流程、Cookie 登录思路、`hc=1` 全部评论行为与评论提交表单。
- Venera 的表站 / 里站切换、Cookie 字段和中文化体验。
- EhTagTranslation `DatabaseReleases/db.html.json` 作为可选中文标签数据源。

## v1 功能

- 表站：`https://e-hentai.org`
- 里站：`https://exhentai.org`
- 站点模式：自动 / 表站 / 里站；自动模式仅在账号验证确认里站可用时优先 ExHentai。
- 默认中文：浏览与搜索默认加入 `language:chinese`，可在设置或搜索筛选中关闭。
- 账号：支持论坛账号密码登录；登录请求按论坛 `act=Login&CODE=01` 流程提交，并保存站点返回的登录 Cookie。
- Cookie：支持手动填写 `ipb_member_id`、`ipb_pass_hash`、`igneous`、`star`，并支持从宿主 WebView 同步 Cookie。
- 账号状态：实现 MX `AccountSource`，可显示论坛用户名和个人页链接。
- 搜索：标题 / 标签搜索、女性标签、男性标签、分类、最低评分、页数区间、高级选项、收藏、监视列表、直接画廊 URL / `gid/token` 导入。
- 详情：实现 MX `MangaDetailSource`；分类、上传者、语言、时间、文件大小、页数、收藏次数、评分、画廊 ID、站点、分组标签等结构化显示。标签点击使用原始 E-Hentai 查询串，避免中文翻译破坏检索。
- 中文标签：默认开启。优先使用本机已缓存翻译；遇到未缓存标签时尝试读取 EhTagTranslation 数据库，本次进程内建立映射并只持久化实际使用过的标签。读取失败自动回退英文原标签。
- 评论：实现 MX `CommentSource`；画廊评论读取，默认 `hc=1` 请求全部评论；解析作者、论坛用户 ID、正文、时间和投票分数；登录后支持发表画廊评论。
- 评论限制：E-Hentai 评论不是线程回复模型，因此不声明回复能力；站点是“赞 / 踩投票”而 MX 当前 ABI 是布尔 Like，语义不等价，因此 v1 不声明 Like 能力，避免错误写操作。
- 阅读：遍历缩略图分页，逐个解析 viewer 页面得到正文图片；可选优先原图。
- 深链：支持 `e-hentai.org/g/.../...` 与 `exhentai.org/g/.../...`。

## 安全与登录

- 仓库不包含任何真实账号、密码、Cookie、Token 或密钥。
- 账号密码只保存在用户设备的扩展 SharedPreferences；密码登录成功后立即清除保存的密码字段，保留登录 Cookie。
- ExHentai 可用性必须单独验证；论坛登录成功不等于账号必然具有里站权限。

## 验证状态

2026-09-05 当前状态：**构建与固定签名测试发布已验证通过；功能仍待用户实机验证。**

已完成：

- `mx-build` 的当前 Keiyoushi Debug 校验 run `33891457459`（attempt 2）完整通过：Spotless、当前 Keiyoushi 编译和 Debug APK 生成成功。
- 通用 `MX Extension Test Store` run `33894021445` 完整通过：源码布局校验、当前 Keiyoushi Spotless、长期固定签名证书校验、Release APK 编译、APK 签名证书复核、测试仓库生成和 staging 在线校验全部成功。
- `mx-repo` 的 `Promote MX Extension Test Store` run `33894288986` 完整通过：staging 包校验、`repo/test` 发布和公开端在线校验成功。
- 测试版本：`1.6.1`，Android `versionCode=106001`，源码递增号 `versionCode=1`。
- 测试 APK：`test/apk/tachiyomi-zh.ehentaiplus-v1.6.1.apk`。
- 测试仓库索引已确认包含 `eu.kanade.tachiyomi.extension.zh.ehentaiplus`，签名 fingerprint 为项目长期固定扩展签名。

仍需重点实机确认：

1. 当前 E-Hentai 列表的各种显示模式是否都能正确解析封面和标题。
2. 论坛账号密码登录是否能稳定取得 `ipb_member_id` / `ipb_pass_hash`。
3. ExHentai 权限检测和 `igneous` 更新是否符合账号实际情况。
4. `hc=1` 是否返回完整评论，以及评论时间 / 分数解析。
5. 发表评论表单 `commenttext_new` 的成功与错误返回。
6. EhTagTranslation 首次读取时的耗时、内存占用和失败回退。
7. 原图模式与普通图片模式在 E-Hentai 配额限制下的表现。
8. MX 结构化详情页的中文标签分组、点击搜索动作和整体布局是否符合实机预期。

CI / Release / 签名 / 测试仓库均通过只代表构建与发布链已验证，不能替代用户实机功能验证。


## v2 实机修复候选（2026-09-05）

根据 v1.6.1 实机反馈：

- 正文不再使用伪造 loopback imageUrl + OkHttp interceptor；改用当前 KeiSource 官方 `getImageUrl(Page)` 懒解析链路，避免宿主图片解码器直接拿到非图片响应而报 `Failed to initialize decoder`。
- 按 JHenTai 行为将 `s.exhentai.org` 图片地址归一到 `ehgt.org`，并识别 E-Hentai/ExHentai 的 509 图片配额占位图，返回明确错误。
- 评论页在未登录时不再触发宿主“请先登录/漫画源设置”底栏；只有已登录时才声明可发表评论，实际提交仍二次校验登录。
- 评论作者选择器不再强依赖 `showuser` URL，兼容当前评论 HTML；评论时间改为统一数字时间显示。
- 设置页新增“账号状态”，明确显示未登录 / 已登录账号 / ExHentai 里站验证状态，并在登录、同步 Cookie、验证和退出后即时刷新。
- 扩展图标替换为 E-Hentai 官方 favicon。

验证边界：CI 验证当前 Keiyoushi Spotless、Debug/Release 构建、Lint、固定签名、公开 E-Hentai viewer→image 链路和测试仓库生成；账号、里站与评论写操作仍需用户实机验证。
