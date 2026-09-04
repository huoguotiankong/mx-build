# CopyManga Plus / 拷贝漫画 Plus 维护记录

## 当前身份

- 模块：`src/zh/copymangaplus/`
- Kotlin package：`eu.kanade.tachiyomi.extension.zh.copymangaplus`
- 扩展名：`CopyManga Plus`
- 源名称：`拷贝漫画 Plus`
- 源 ID：`4890981838474778925`
- 基线：当前 Keiyoushi `@Source` + `KeiSource`
- `libVersion = "1.6"`
- 源码 `versionCode = 3`
- Android APK versionCode：`106003`
- APK versionName：`1.6.3`
- 内容警告：NSFW
- 默认详情 URL：`https://www.mangacopy.com`

v1 已生成签名测试 APK。2026-09-04 用户实机进一步确认：**v1 扩展整体可用，热辣漫画线路可正常使用；异常集中在拷贝漫画线路本身，以及章评读取。** v2 对线路和章评做了第一次定向修复；随后用户实机反馈 v2 的热辣线路仍基本正常、书评正常，但所有拷贝线路仍不可用，章评入口已经出现但打开为空。因此 v3 继续只修复这些已确认问题，不重写已经实机正常的主体解析和书评逻辑。

## v2 实机反馈与修复目标（2026-09-04）

### v1 用户已实机确认

- v1 扩展可以正常安装和使用。
- 热辣漫画线路正常。
- 书评 / 漫画评论列表正常。
- 默认 / 最热 / 最新三个评论筛选项可以显示。
- 拷贝漫画线路会返回官方限制提示，典型文本包含“请到官网更新最新 APP”“曾经下载过破解版”“等待 1 小时限制自动解除”等内容。
- 章评列表无法加载。

### v2 已写入源码的修复

1. 拷贝 API 固定候选更新为当时测试的节点：
   - `api.2026copy.com`
   - `api.copy4000.com`
   - `api.mangacopy.com`
   - `api.copy3000.com`
   - `mapi.copy20.com`
2. 拷贝动态线路 bootstrap 更新为 `api.2026copy.com`。
3. v2 首次运行会清除 v1 保存的旧拷贝动态节点和最近成功节点；如果用户原先固定选择了已经移除的旧拷贝节点，会自动迁移到“拷贝漫画·动态线路”。
4. 拷贝请求头改为移动 API 请求契约：`COPY/3.0.0`、`version=3.0.9`、`source=copyApp`、`referer=com.copymanga.app-3.0.0`、`webp=1`、`region=1`。
5. 如果某个拷贝节点返回已知“更新正版 APP / 破解版本 / 等待解除限制”文本，则判定该节点本次不可用并继续尝试下一个节点；自动线路最终仍可回退热辣线路。
6. 章评读取从错误的书评接口 `/api/v3/comments?comic_id=...` 改为章节吐槽接口：
   - `GET /api/v3/roasts?chapter_id=<chapter_uuid>&limit=<n>&offset=<n>&_update=true`
7. 章评发表改为：
   - `POST /api/v3/member/roast`
   - 字段：`chapter_id`、`roast`、`_update=true`
8. 书评仍使用原先已经实机可用的 `/api/v3/comments` 和 `/api/v3/member/comment`。
9. 当前没有可靠证据证明章节吐槽支持回复层级，因此章评回复不做伪实现。

v2 已完成源码提交、当前 Keiyoushi 框架检查、签名 Release 构建、固定证书校验和公开测试商店发布。

## v3 实机反馈与定向修复（2026-09-04）

### v2 用户已实机确认

- 热辣漫画线路基本正常。
- 书评仍能正常显示。
- v2 的所有“拷贝漫画”固定线路仍无法正常浏览。
- 章评入口已经出现，但直接进入章评页为空。
- 扩展列表里的图标仍是错误的占位图标。

### v3 线路诊断证据

在独立 GitHub Actions 网络探针中对以下七个拷贝节点逐一测试：

- `api.copy2000.online`
- `api.copy-manga.com`
- `api.2026copy.com`
- `api.copy4000.com`
- `api.mangacopy.com`
- `api.copy3000.com`
- `mapi.copy20.com`

得到的结果：

1. 使用 v2 同类移动 APP 请求头，并补齐 `platform: 3` 后，七个拷贝节点的 `/api/v3/comics` 均返回 HTTP 200 / API code 200，并能取得漫画列表。
2. 同一批拷贝节点在网页 / PC 风格请求头下会返回 API code 210 和“升级最新 APP”限制提示，说明内容接口对请求契约有明确区分。
3. 七个拷贝节点使用移动 APP 请求头读取 `/api/v3/roasts` 均返回 HTTP 200 / API code 200；测试章节实际返回了 5 条章评。
4. 热辣节点适合漫画列表和正文，但 `/roasts` 会出现 404，或者 HTTP 200 但不含 `results.list`。因此热辣 / 通用动态节点不能被当成章评读取成功节点。

这解释了 v2 的两个实机问题：

- 拷贝线路：v2 的 Copy 请求头缺少 `platform=3`，导致实机请求契约不完整。
- 章评为空：v2 的章评仍经过通用评论候选，可能先命中一个“HTTP 成功但没有章评列表”的热辣 / 非章评节点，随后被错误当成空结果返回，未继续尝试真正支持 `/roasts` 的拷贝节点。

### v3 已写入源码

1. Copy 移动 API 请求头补齐 `platform = 3`，继续保留 `COPY/3.0.0`、`version=3.0.9`、`source=copyApp`、`region=1` 契约。
2. 增加 `api.copy2000.online` 与 `api.copy-manga.com` 两个固定备用节点；原五个节点继续保留。
3. 路由 schema 从 2 升到 3，升级 v3 后会重新清除旧的拷贝动态节点和最近成功节点缓存。
4. 章评 GET 不再走书评 / 通用评论候选，而是只轮询已验证支持移动 `/roasts` 的固定拷贝节点。
5. 章评 GET 只有在响应真实包含 `results.list` 时才算成功；HTTP 200 但没有章评列表的节点会继续尝试下一个节点。
6. 章评发表 `/api/v3/member/roast` 同样固定走拷贝移动节点。
7. 书评读取和书评回复逻辑保持原样，避免破坏用户已经实机确认正常的功能。
8. 扩展 launcher 图标改为 CopyManga Android 客户端项目使用的 CopyManga 图标资源，替换当前错误占位图标。

上述 API 探针属于网络 / 协议侧验证；最终 Android 实机功能仍以用户安装 v3 后反馈为准。

## 功能架构

### 浏览 / 搜索 / 排行

当前主要使用 v3 JSON API：

- 热门：`/api/v3/comics`，`ordering=-popular`
- 最新：`/api/v3/comics`，`ordering=-datetime_updated`
- 普通搜索：`/api/v3/search/comic`
- 排行：`/api/v3/ranks`
- 详情：`/api/v3/comic2/<path_word>`
- 分组章节：`/api/v3/comic/<path_word>/group/<group>/chapters`
- 正文：优先 `/api/v3/comic/<path_word>/chapter/<uuid>`，失败时回退 `/chapter2/<uuid>`

筛选当前包含排序、地区、题材和排行榜周期。

作者和题材对象解析时会保存 `name -> path_word` 映射。MX 结构化详情页中的“作者”和“标签”均为可点击项，点击后通过源内搜索跳转到对应作者或标签结果。详情页同时展示别名、地区、状态、类型、更新时间、人气和最新章节；`replaceDefaultFields = true`。

### 章节与图片

章节从详情返回的 group 信息逐组读取，单次最多请求 500 条，并按 URL 去重。

正文会读取 `contents` 图片数组；如果接口同时返回数量一致的 `words`，按 `words` 给出的页面位置重新排列图片。图片支持清晰度偏好：800、1200、1500 和源站原始地址。

图片顺序、长章节完整性以及不同线路下的真实加载速度继续以实机反馈为准。

## 多线路与动态发现

当前是“拷贝 API + 热辣 API”双路由体系。

### 拷贝线路（v3）

当前固定候选：

- `api.copy2000.online`
- `api.copy-manga.com`
- `api.2026copy.com`
- `api.copy4000.com`
- `api.mangacopy.com`
- `api.copy3000.com`
- `mapi.copy20.com`

动态发现入口优先从：

`https://api.2026copy.com/api/v3/system/network2?platform=3`

获取；发现失败时固定候选仍可继续工作。

### 热辣线路

动态发现入口：

`https://api.2024manga.com/api/v3/system/network2?platform=3`

固定候选：

- `api.2024manga.com`
- `mapi.hotmangasg.com`
- `mapi.hotmangasd.com`
- `mapi.hotmangasf.com`

### 路由策略

设置中提供：

- 自动：拷贝优先，失败继续热辣
- 拷贝动态线路
- 指定当前拷贝 API 节点
- 热辣动态线路
- 指定热辣节点

最近成功节点会保存在本地并优先参与后续请求。动态发现结果也会缓存；“刷新动态线路”会清除动态节点和最近成功节点。

对于普通漫画内容，请求仍按用户设置的线路选择；章评则单独使用支持 `/roasts` 的 Copy 移动节点，不再让热辣内容节点伪装成章评成功响应。

## 登录与账号

设置项提供用户名/邮箱、密码、登录/刷新登录、退出登录。

登录接口：`POST /api/v3/login`。

密码按当前实现使用随机四位 salt，提交 `Base64(password-salt)` 与 salt。拷贝和热辣线路分别附带各自的 source/version/platform 参数。登录成功后仅把 Token 保存在扩展本机 SharedPreferences，不向仓库写入真实账号、密码、Cookie 或 Token。

账号信息读取：`GET /api/v3/member/info`。

评论请求遇到登录失效且本机仍有用户主动保存的账号信息时，会清除旧 Token 并尝试重新登录一次。

账号协议和 Token 自动恢复仍需真实账号实机验证。

## MX 评论能力

扩展实现：

- `CommentSource`
- `SortableCommentSource`
- `AccountSource`
- `MangaDetailSource`

当前声明：

- 书评/漫画评论：支持
- 章评：支持
- 发评论：支持
- 书评回复：支持
- 章评回复：当前未确认源站协议，不做伪实现
- 点赞写操作：不支持
- 发言需要登录

### 书评

列表：

`GET /api/v3/comments?comic_id=<comic_uuid>&limit=20&offset=<offset>`

回复列表：

`GET /api/v3/comments?comic_id=<comic_uuid>&reply_id=<comment_id>&limit=20&offset=<offset>`

发表 / 回复：

`POST /api/v3/member/comment`

字段：`comic_id`、`comment`、`reply_id`。

### 章评

列表：

`GET /api/v3/roasts?chapter_id=<chapter_uuid>&limit=20&offset=<offset>&_update=true`

发表：

`POST /api/v3/member/roast`

字段：`chapter_id`、`roast`、`_update=true`。

v3 章评读取只在响应包含真正的 `results.list` 时接受该节点，从而避免热辣 / 错误节点返回空结构后提前结束。

### 评论排序

评论筛选项为“默认 / 最热 / 最新”。当前接口请求本身没有额外 sort 参数；“最热”和“最新”是在当前已取回页内分别按回复/点赞和时间重新排序，因此不能描述为源站全量服务端排序。

## 关于评论页底部“漫画源设置”提示

用户截图中的“请先登录漫画源账号后再参与评论 / 漫画源设置”是 **MX 宿主 APP 根据 `requiresLoginToPost` 能力主动绘制的评论页 UI**，不是 CopyManga 扩展内部生成的 Toast 或列表项。

本扩展仍必须如实声明“发言需要登录”，不能为了隐藏宿主提示而把 `requiresLoginToPost` 伪报为 `false`。正确方案是在 MX APP 项目中改成：评论页平时不显示永久底栏，只有用户实际点击发表 / 回复且尚未登录源账号时，再弹出登录提示或跳转漫画源设置。

因此该 UI 修改不能写进本 Tachiyomi 扩展仓库；需要在 MX APP 项目中单独处理。

## 版本与验证状态

### v1 已确认 / 已反馈

- 签名测试 APK 可安装。
- 用户确认 v1 扩展整体可用。
- 热辣线路可用。
- 书评列表可用。
- 拷贝线路会命中限制提示。
- 章评无法加载。

### v2 已完成（源码 / 构建 / 测试分发）

- `versionCode` 从 1 增加到 2。
- `mx-build` CopyManga 专项检查 run `33869205390` 成功：源码布局校验、Spotless、Debug 编译、Release lint 均通过。
- `mx-build` 测试商店 run `33869364086` 成功：签名 Release APK 构建通过，并校验长期证书指纹 `1bbfbdc401ab81dc227bf771c43d5b616d00149f5755691a317a819f5a88f620`。
- `mx-repo` 测试商店 promotion run `33869566858` 成功：`CopyManga Plus 1.6.2` / Android `106002` 已发布到公开 `test/` 索引并完成公开端校验。

### v2 实机结论

- 热辣线路基本正常。
- 书评正常。
- 所有拷贝线路仍不可用。
- 章评入口存在，但打开为空。
- 图标错误。

### v3 当前状态

- `versionCode` 已增加到 3。
- Copy 请求头已补齐 `platform=3`。
- 七个 Copy 固定节点已纳入候选。
- 章评已从通用候选拆分为只使用支持 `/roasts` 的 Copy 移动节点，并校验 `results.list`。
- 图标替换列入本版本。
- 网络探针已经确认“补齐 platform 后 Copy 内容接口可返回 code 200”和“Copy `/roasts` 可返回实际章评”。
- 当前仍需完成 v3 当前 Keiyoushi 编译 / lint、固定签名 Release、测试商店发布和 Android 实机验证。

CI、签名和公开测试商店成功只证明构建 / 签名 / 分发链路通过；只有用户真实 Android 设备验证后才能标记对应功能“实机通过”。

## 发布基础设施注意

2026-09-04 的测试发布中，`mx-build` 内配置的 `MX_REPO_TOKEN` 可以读取 `huoguotiankong/mx-repo`，但向 `repo` 分支 push 返回 HTTP 403。测试 APK 通过 `mx-build/extension-staging` 生成并校验，再由 `mx-repo` 自仓库 Actions 使用其 `GITHUB_TOKEN` 完成 staging 文件复制、`repo` 分支发布和公开端校验。

这不是 CopyManga Kotlin 源码错误。当前测试发布已使用经过验证的本仓库 promotion 路径；正式生产发布仍应继续遵守独立的生产发布流程与权限校验。

## 下一版本规则

v3 一旦生成并公开不同于 v2 的签名测试 APK，后续任何再次修改 `src/zh/copymangaplus/` 并重新生成不同 APK，都必须继续递增源码 `versionCode`；不得用相同版本号覆盖不同源码。


## 繁体转简体

v4 增加可开关的繁体转简体功能，默认开启。实现使用 Maven Central 的纯 Java `io.github.laisuk:openccjava:1.4.2`，不依赖 JNI / NDK。转换范围包括漫画标题、作者、标签、简介、别名、章节分组名、章节名、详情字段，以及书评/章评的昵称和评论正文。关闭设置后保留源站原文。

作者与标签点击搜索同时缓存源站原名和简体显示名到同一 `path_word`，避免开启转换后详情页点击作者/标签失效。用户主动发表的评论正文不在发送前强制改写，只转换服务端返回后的展示文本。

此功能只处理文本显示，不修改漫画 URL、章节 UUID、评论 ID、登录参数、图片地址或线路协议。
