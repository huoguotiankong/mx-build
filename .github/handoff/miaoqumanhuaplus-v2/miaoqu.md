# 喵趣漫画 Plus

## 状态

- 目标：Tachiyomi / Mihon / Komikku 兼容扩展，MX 可选增强能力后续按宿主 ABI 决定是否启用。
- 模块规划：`src/zh/miaoqu/`
- 当前阶段：APK/API 逆向，尚未发布，尚未实机验证。
- 目标 APK 包名：`com.xinmiaoqu.app`。

## APK 观察（2026-09-06）

用户提供的喵趣 APK 使用加固/运行时释放业务代码。静态 APK 中 `classes.dex` 很小，核心载荷位于 `assets/5a90be0`、`assets/5a90be2`、`assets/5a90be3`。三个文件前部都是 121x121 JPEG，首个 JPEG EOI 后仍分别附带约 7.8 MB、8.27 MB、6.27 MB 数据；附加数据没有明文 DEX/ZIP/ELF magic，按加密载荷处理。APK 还包含 `lib/arm64-v8a/lib167d8.so`，会读取 Asset，说明不能依赖普通 jadx 静态字符串搜索直接取得业务 API。

资源/壳层中同时可见历史应用标识（例如 QingManApplication / `com.qingmanlsland.app`），不能据此把该 APK 当成青漫；目标身份以当前 APK 包名和用户提供的应用为准。

## 历史协议线索

喵趣是喵上漫画后续同内核产品。公开的旧版喵上逆向资料给出了以下协议族，当前只能作为逆向线索，不能直接宣称现版仍可用：

- 搜索：`/api/novel/search/associate`
- 详情：`/api/novel/book/info/{bookId}.json`
- 章节：`/api/novel/book/chapters/{bookId}.json`
- 图片列表：`/api/novel/book/chapters/images/{bookId}/{chapterId}.json`
- 响应可能使用 `safety` + `safetyData` 包装。
- 历史 `type=14339`：单 token 派生 DES key（旧实现为 token 字符串逆序变体）。
- 历史 `type=14439`：双 token 交错派生 DES key。
- 历史正文图片请求还需要 `sign` / `sign1` 等 native 签名参数，不能仅拿图片 URL 直接请求。

旧版曾使用 `com.aster.zhbj` appKey 和 `43.248.116.*` IP 服务。现版包名、版本、服务地址、签名算法都必须重新验证，禁止直接硬编码旧值作为正式实现。

## 当前可用外部线索

- 官方旧发布页：`https://miaoqumh.app/`
- 2026-09 第三方下载重定向暴露过 APK CDN：`new-comic.tiankongshuyu.cn`。这只能证明安装包分发域名，不能当作漫画 API Base URL。
- 公开资料确认当前喵趣仍支持账号登录（邮箱/手机号 + 密码），所以基础阅读稳定后应继续检查登录、云书架/收藏和账号同步能力。

## 实现计划

1. 先恢复/确认现版 API Base URL、公共请求参数、`safetyData` 解密规则。
2. 打通搜索 -> 详情 -> 章节 -> 图片列表 -> 图片字节的最小闭环。
3. 再补首页推荐/热门、最新、排行榜、分类筛选。
4. 处理图片签名、CDN/线路和异常回退；如果 native 签名已变化，优先还原纯 Kotlin 算法，不把 Unidbg/原 APP so 作为扩展运行时依赖。
5. 检查登录、收藏/书架、历史、求书/社区等能力，只有宿主 API/MX 可选 ABI 能合理承载时才实现。
6. 添加完整五档 `ic_launcher`，避免安装后回退默认图标。
7. Debug/Release 构建通过后进入隔离测试商店；CI 绿色只标记“构建通过”，功能必须等待实机验证。

## 已知风险

- 当前 APK 加固，静态分析无法直接取得真实业务 DEX。
- 历史协议可能仍同族但服务器、appKey、签名、DES token 规则可能已变。
- 正文图片签名是关键阻塞点；不能为了先出包而返回无法实际加载的图片 URL。
- 在完整闭环验证前，不发布到正式商店。
