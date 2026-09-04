# CopyManga Plus / 拷贝漫画 Plus 维护记录

## 当前身份

- 模块：`src/zh/copymangaplus/`
- Kotlin package：`eu.kanade.tachiyomi.extension.zh.copymangaplus`
- 扩展名：`CopyManga Plus`
- 源名称：`拷贝漫画 Plus`
- 源 ID：`4890981838474778925`
- 基线：当前 Keiyoushi `@Source` + `KeiSource`
- `libVersion = "1.6"`
- 源码 `versionCode = 5`
- Android APK versionCode：`106005`
- APK versionName：`1.6.5`
- 内容警告：NSFW
- 默认详情 URL：`https://www.mangacopy.com`

## 用户实机反馈基线

2026-09-04 的连续实机反馈必须优先于代码推测：

- v1：扩展整体可用，热辣线路正常，书评正常；Copy 线路会命中“更新正版 APP / 破解版本 / 等待解除限制”提示；章评无法加载。
- v2：热辣线路和书评仍正常；所有 Copy 固定线路仍不可用；章评入口出现但列表为空；扩展图标错误。
- v3：Copy 首页/列表已经可以加载；章评已经可以显示；热辣线路基本正常；剩余主要问题是 Copy 作品详情返回“拷贝漫画详情为空”，并继续优化图标和繁简显示。

因此 v4 不重写已实机正常的列表、书评和章评主流程，只针对 Copy 详情契约、繁体转简体和图标资源做定向调整。

## Copy / 热辣双线路

当前 Copy 固定候选：

- `api.copy2000.online`
- `api.copy-manga.com`
- `api.2026copy.com`
- `api.copy4000.com`
- `api.mangacopy.com`
- `api.copy3000.com`
- `mapi.copy20.com`

Copy 动态发现入口：

`https://api.2026copy.com/api/v3/system/network2?platform=3`

热辣固定候选：

- `api.2024manga.com`
- `mapi.hotmangasg.com`
- `mapi.hotmangasd.com`
- `mapi.hotmangasf.com`

热辣动态发现入口：

`https://api.2024manga.com/api/v3/system/network2?platform=3`

设置中提供自动线路、Copy 动态线路、Copy 固定节点、热辣动态线路和热辣固定节点。最近成功节点与动态发现结果会本地缓存；“刷新动态线路”会清除动态节点和最近成功节点。

## v3 已验证的 Copy 内容与章评契约

独立网络探针对七个 Copy 节点确认：

1. 使用移动 APP 请求头并补齐 `platform=3` 后，`/api/v3/comics` 可返回 HTTP 200 / API code 200 和真实漫画列表。
2. 同节点使用网页 / PC 风格请求会返回 API code 210 和“升级最新 APP”限制提示，说明接口对请求契约有明确区分。
3. `/api/v3/roasts` 使用 Copy 移动请求头可返回 HTTP 200 / API code 200 和真实章评。
4. 热辣节点适合内容列表和正文，但不能可靠作为 `/roasts` 章评节点；因此章评必须单独轮询 Copy 节点，并且只有真正存在 `results.list` 才算成功。

普通 Copy 内容请求继续使用 `COPY/3.0.0`、`version=3.0.9`、`source=copyApp`、`platform=3`、`region=1` 等既有移动契约。

## v4 Copy 详情页修复

用户实机确认 v3 Copy 首页/列表已经恢复，但详情仍出现“拷贝漫画详情为空”。独立探针可复现：旧契约请求 `/api/v3/comic2/<path_word>` 时可能 HTTP 200 / code 200，但 `results.comic` 为空。

v4 只给 Copy 详情请求使用独立契约：

- `COPY/3.0.6`
- `region=0`
- `platform=3`
- `in_mainland=true`
- `request_id=`（region=0 时为空）

详情接口仍为：

`/api/v3/comic2/<path_word>?in_mainland=true&request_id=&platform=3`

只有 `results.comic` 非空才接受当前节点，否则继续尝试下一 Copy 候选。普通列表、章节、书评和章评继续保留此前已经验证的请求契约，避免扩大回归面。

## 浏览 / 搜索 / 排行 / 正文

主要接口：

- 热门：`/api/v3/comics`，`ordering=-popular`
- 最新：`/api/v3/comics`，`ordering=-datetime_updated`
- 搜索：`/api/v3/search/comic`
- 排行：`/api/v3/ranks`
- 详情：`/api/v3/comic2/<path_word>`
- 分组章节：`/api/v3/comic/<path_word>/group/<group>/chapters`
- 正文：优先 `/api/v3/comic/<path_word>/chapter/<uuid>`，失败时回退 `/chapter2/<uuid>`

章节逐组读取，单次最多请求 500 条并按 URL 去重。正文读取 `contents` 图片数组；如果 `words` 数量一致，则按 `words` 页面位置重新排序。图片清晰度支持 800、1200、1500 和源站原始地址。

作者和题材对象会保存 `name -> path_word` 映射。MX 结构化详情页中的作者和标签均为可点击项，点击后通过源内搜索跳转到相应作者或标签结果；`replaceDefaultFields = true`。

## 繁体转简体

v4 增加可开关的繁体转简体功能，默认开启。实现使用 Maven Central 的纯 Java：

`io.github.laisuk:openccjava:1.4.2`

不依赖 JNI / NDK。转换范围：

- 漫画标题
- 作者
- 标签
- 简介
- 别名
- 章节分组名
- 章节名
- 详情字段
- 书评 / 章评昵称和正文

作者与标签同时缓存源站原名和简体显示名到同一 `path_word`，避免开启转换后详情页点击作者 / 标签失效。

转换只影响显示文本，不修改漫画 URL、章节 UUID、评论 ID、登录参数、图片地址或线路协议；用户主动发送的评论正文也不会在发送前强制改写。

## MX 评论能力

扩展实现：

- `CommentSource`
- `SortableCommentSource`
- `AccountSource`
- `MangaDetailSource`

当前能力：

- 书评 / 漫画评论：支持
- 章评：支持
- 发评论：支持
- 书评回复：支持
- 章评回复：当前未确认源站协议，不做伪实现
- 点赞写操作：不支持
- 发言需要登录

书评列表：

`GET /api/v3/comments?comic_id=<comic_uuid>&limit=20&offset=<offset>`

书评回复：

`GET /api/v3/comments?comic_id=<comic_uuid>&reply_id=<comment_id>&limit=20&offset=<offset>`

书评发表 / 回复：

`POST /api/v3/member/comment`

字段：`comic_id`、`comment`、`reply_id`。

章评列表：

`GET /api/v3/roasts?chapter_id=<chapter_uuid>&limit=20&offset=<offset>&_update=true`

章评发表：

`POST /api/v3/member/roast`

字段：`chapter_id`、`roast`、`_update=true`。

章评读取只轮询支持移动 `/roasts` 的 Copy 节点，并且响应必须真实包含 `results.list`。

评论筛选项为“默认 / 最热 / 最新”。当前接口没有可靠的服务端全量排序参数，因此“最热”和“最新”是在当前已取回页内分别按回复/点赞和时间重排，不能描述成源站全量服务端排序。

## 登录与账号

设置项提供用户名/邮箱、密码、登录/刷新登录和退出登录。

登录接口：`POST /api/v3/login`。密码按当前实现使用随机四位 salt，提交 `Base64(password-salt)` 与 salt。账号信息读取：`GET /api/v3/member/info`。

Token 仅保存在扩展本机 SharedPreferences；仓库不提交真实账号、密码、Cookie、Token 或其它敏感信息。账号协议和 Token 自动恢复仍需真实账号实机验证。

## 评论页“请先登录 / 漫画源设置”提示边界

用户截图中的“请先登录漫画源账号后再参与评论 / 漫画源设置”是 MX 宿主 APP 根据 `requiresLoginToPost` 主动绘制的评论页 UI，不是 CopyManga 扩展生成。

扩展必须如实声明“发言需要登录”，不能为了隐藏提示而伪报 `requiresLoginToPost=false`。正确方案是在 MX APP 项目中仅当用户实际点击发表 / 回复且尚未登录时再提示登录。该 UI 不能通过本扩展仓库伪造能力处理。

## v4 图标

v4 最终改为使用 CopyManga Android 客户端项目中的矢量 launcher 资源，而不是继续提交异常 PNG：

- `drawable/ic_launcher_background.xml`
- `drawable/ic_launcher_foreground.xml`
- `mipmap-anydpi/ic_launcher.xml`
- `mipmap-anydpi-v26/ic_launcher.xml`

这样避免此前异常密度 PNG 触发 AAPT2 资源编译失败，同时保持蓝色 CopyManga 图标视觉。

最终图标修复源码提交：

`1a938aec5014339db94850183ec536bc5ffa8280`

## v4 构建 / 签名 / 测试商店状态（2026-09-04）

当前已真实完成：

1. `mx-build` CopyManga 专项检查 run `33890767359`：**SUCCESS**。
   - source layout 校验通过
   - Spotless 通过
   - `assembleDebug` 通过
   - `lintRelease` 通过
   - Debug APK artifact 上传通过
2. `mx-build` 签名测试商店 run `33890932798`：**SUCCESS**。
   - 当前 Keiyoushi overlay 通过
   - 稳定签名材料准备通过
   - 固定签名身份校验通过
   - signed Release APK 构建通过
   - APK 签名证书二次校验通过
   - `extension-staging` 测试商店生成与验证通过
3. `mx-repo` promotion run `33891817659`：**SUCCESS**。
   - staging 中 `copymangaplus` 版本校验通过
   - `repo` 分支 `test/` 发布通过
   - 公开 `repo.json` / `index.json` 端点验证通过

长期签名证书 SHA-256：

`1bbfbdc401ab81dc227bf771c43d5b616d00149f5755691a317a819f5a88f620`

公开测试索引当前已确认：

- `CopyManga Plus`
- package：`eu.kanade.tachiyomi.extension.zh.copymangaplus`
- versionName：`1.6.4`
- Android versionCode：`106004`
- source：`拷贝漫画 Plus`
- APK：`https://github.com/huoguotiankong/mx-repo/raw/repo/test/apk/tachiyomi-zh.copymangaplus-v1.6.4.apk`
- icon：`https://github.com/huoguotiankong/mx-repo/raw/repo/test/icon/eu.kanade.tachiyomi.extension.zh.copymangaplus-v106004.png`
- 测试仓库：`https://github.com/huoguotiankong/mx-repo/raw/repo/test/repo.json`
- 现代索引：`https://github.com/huoguotiankong/mx-repo/raw/repo/test/index.json`

CI、签名和公开测试商店成功只证明源码、构建、签名与分发链路通过，不能替代 Android 实机验证。

## v4 待实机验证项目

安装 `1.6.4 / 106004` 后重点验证：

1. Copy 首页 / 搜索 / 排行仍正常，确认 v3 已恢复功能没有回归。
2. Copy 作品详情不再出现“拷贝漫画详情为空”。
3. 章节目录和正文正常。
4. 书评正常。
5. 章评正常。
6. 繁体转简体默认开启并能关闭，关闭后恢复源站原文。
7. 作者 / 标签在简体显示状态下仍可点击跳转。
8. 扩展列表图标显示正确。
9. 热辣线路继续保持正常。

只有用户真实设备验证后，才能把上述功能标记为“实机通过”。

## 发布基础设施注意

`mx-build` 内跨仓库 token 曾能读取 `mx-repo`，但向 `repo` 分支 push 返回 HTTP 403。因此测试发布采用已验证的安全链路：

`mx-build` 构建并写入 `extension-staging` → `mx-repo` 自仓库 Actions 使用自己的 `GITHUB_TOKEN` 完成 `repo/test` promotion → 公开端校验。

这不是 CopyManga Kotlin 源码错误。

## 下一版本规则

v4 已经生成并公开发布不同于 v3 的签名测试 APK。后续任何再次修改 `src/zh/copymangaplus/` 并重新生成不同 APK，都必须把源码 `versionCode` 从 4 增加到 5；不得以 `1.6.4 / 106004` 覆盖不同源码。


## v5 实机反馈与定向修复（2026-09-05）

用户安装 v4 后确认：繁体转简体正常、热辣线路仍正常，但 Copy 首页/最近更新变成“没有结果”；评论页仍显示“请先登录 / 漫画源设置”；v4 图标仍不是用户确认的官方蓝底白色图标。

v5 只修复扩展侧已确认回归：

1. 普通 Copy 内容请求恢复 v3 已经实机通过的轻量移动请求头：`COPY/3.0.0`、`version=3.0.9`、`source=copyApp`、`platform=3`、`region=1`、`webp=1`。不再把 v4 为详情诊断增加的 device/signature 请求头注入首页、搜索、排行、章节和评论等普通请求。
2. v4 的独立详情 `copyDetailHeaders()` 保持不变，详情仍单独使用详情契约，从而隔离“首页已验证契约”和“详情专用契约”。
3. 路由 schema 从 3 增加到 4，v5 首次运行清除 v4 保存的 Copy 动态节点和最近成功节点缓存。
4. 扩展图标改用用户确认的 `com.copymanga.app` 蓝底白色原子状图标 PNG，不再使用 fumiama 第三方客户端的黄底图标资源。

评论页长期登录提示属于 MX App 宿主 UI，不通过扩展伪报 `requiresLoginToPost=false` 隐藏。当前 MX App `main` 已经让 `state.loginRequired` 的底栏分支直接返回 `Unit`；用户截图说明实机仍在使用未包含该 APP 修改的构建，需要单独升级 MX App。

由于 v4 已公开发布签名测试 APK，v5 使用新的 `versionCode=5` / Android `106005`。
