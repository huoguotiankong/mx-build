# 喵趣漫画 Plus

## 状态

- 目标：Tachiyomi / Mihon / Komikku 兼容扩展。
- 正式模块：`src/zh/miaoqumanhuaplus/`。
- 扩展包名：`eu.kanade.tachiyomi.extension.zh.miaoqumanhuaplus`。
- 当前源码版本：`1.6.2`（source versionCode `2` / Android versionCode `106002`）。
- 当前阶段：现版 APP 协议已完成服务器侧闭环验证；Spotless、Debug、Release、Lint、固定签名验证已通过；已发布到隔离测试商店，等待用户实机验证。
- 目标官方 APK 包名：`com.xinmiaoqu.app`。
- 未经实机验证前，不宣称正式功能验证完成，也不进入正式商店。

## 现版 APK 逆向结论（2026-09-06）

用户提供的喵趣 APK 使用加固/运行时释放业务代码。静态 APK 中 `classes.dex` 很小，核心载荷位于 `assets/5a90be0`、`assets/5a90be2`、`assets/5a90be3`。三个文件前部是 JPEG，EOI 后仍带有大体积加密载荷；APK 同时包含 `lib167d8.so`、`libDexHelper.so` 等加载组件，因此不能依赖普通 jadx 静态字符串搜索直接取得完整业务代码。

壳层/资源可见 `QingManApplication` / `com.qingmanlsland.app` 命名空间，说明现版喵趣继承了青漫系客户端内核，但不能因此把喵趣当成青漫 API 直接套用。

`libappkey.so` 已从用户 APK 静态恢复 JNI 配置：

- `Java_com_qingmanlsland_app_config_NativeKey_getKeyId` -> `0000o9iu10pol777`
- `Java_com_qingmanlsland_app_config_NativeKey_getMangaInfo` -> `miaoqu46`

现网验证进一步确认：

- `0000o9iu10pol777` 是详情/目录密文的 AES-128-GCM key。
- `miaoqu46` 是详情/目录 CDN 路径段。

这两个值来自官方客户端本身，不是用户账号密码、Cookie、Token 或仓库签名密钥。

## 2026-09-06 现网协议验证

公开构建机已对当前服务做完整服务器侧探测，验证样本：

- mangaId：`25803`
- chapterId：`306749`
- 样本漫画：`斗罗大陆5重生唐三`

动态配置入口：

- `https://apitong.zqykfz.cn/api/com.xinmiaoqu.app/index.json`
- 备用入口：`https://apitong.trgfd.cn/api/com.xinmiaoqu.app/index.json`

当前动态配置解析结果：

- `txturl`：`https://txtong.yishizhige.cn`
- `qitaurl`：`https://qitatong.lyyssl.top`
- 封面图域名：`https://imgtong.sanluchun.cn`
- 正文图域名：`https://mhatt.cougou.tw`

这些是动态下发结果，不应永久硬编码成唯一线路。插件每次进程生命周期缓存成功配置，并保留两个配置入口回退。

### 分类/首页

当前分类列表：

`推荐、恋爱、都市、玄幻、奇幻、冒险、武侠、仙侠、校园、竞技、古风、穿越、其他、大女主`

主分类接口：

`{txturl}/api/com.xinmiaoqu.app/{分类}.json`

兼容回退：

`{txturl}/fenlei/com.xinmiaoqu.app/{分类}.json`

现网 `推荐` 返回可解析漫画 `46` 本。

### 搜索

接口：

`{qitaurl}/sousuo?keyword={关键词}`

现网使用“斗罗”验证，返回可解析漫画 `3` 本。

### 详情与目录

接口：

`{txturl}/miaoqu46/{mangaId / 1000}/{mangaId}/info.json`

响应格式：

1. Base64 解码。
2. 前 12 bytes 为 AES-GCM nonce。
3. 剩余部分为 ciphertext + 16 bytes authentication tag。
4. 使用 UTF-8 key `0000o9iu10pol777` 执行 `AES/GCM/NoPadding`。
5. 解密结果为 JSON。

现网验证已成功解密；样本漫画读取到 `41` 章。

章节字段中已经确认 `geshu` 为正文页数、`paixuid` 为章节排序值。插件按 `paixuid` 最新 -> 最旧输出，避免宿主“下一章/上一章”跳错。

### 正文

图片规律：

`{manhuatuurl}/tu/{mangaId / 1000}/{mangaId}/{chapterId}/{pageNo}.jpeg`

页码从 `1` 开始；Tachiyomi `Page.index` 从 `0` 开始，两者在实现中分开处理。

现网样本：

`https://mhatt.cougou.tw/tu/25/25803/306749/1.jpeg`

验证结果：HTTP `200`、`Content-Type: image/jpeg`、JPEG magic 正确。

正文请求使用单独 Reader User-Agent，图片拦截器负责喵趣图片域名请求兼容。

## 当前扩展实现

`src/zh/miaoqumanhuaplus/` 已实现：

- 动态配置双入口 + 成功配置缓存
- 推荐/首页
- 最近更新聚合
- 动态分类筛选
- 搜索
- 详情信息
- AES-GCM 详情/目录解密
- 完整章节目录
- 最新 -> 最旧章节排序
- 正文页 URL 生成
- 图片专用请求头/拦截
- 五档 `ic_launcher`
- `@Source` + `KeiSource`
- `libVersion = "1.6"`

当前扩展 source ID：`4073761417982452876`。

旧的 `src/zh/miaoqu/` 仅为逆向早期占位模块，已在现版实现稳定后删除，避免生成两个同名“喵趣漫画 Plus”扩展。

## 构建与测试商店

2026-09-06 公共构建链真实完成：

- 项目级 source validator：通过
- Spotless Apply/Check：通过
- Debug APK：通过
- Release APK：通过
- R8：通过
- `lintRelease`：通过
- Release APK `apksigner verify`：通过
- 固定扩展签名身份校验：通过
- 五档 launcher icon 检查：通过
- 测试商店生成：通过
- `mx-repo/repo/test` promotion：通过
- 公开测试索引回读校验：通过

测试商店当前条目：

- 名称：`Miaoqu Manhua Plus` / `喵趣漫画 Plus`
- package：`eu.kanade.tachiyomi.extension.zh.miaoqumanhuaplus`
- versionName：`1.6.2`
- Android versionCode：`106002`
- APK：`test/apk/tachiyomi-zh.miaoqumanhuaplus-v1.6.2.apk`

以上只证明“协议服务器侧闭环 + 构建/发布链”通过。安装、首页、搜索、详情、目录和正文仍必须由用户在 MX/Mihon/Komikku 实机验证。

## Plus 后续能力

青漫系客户端中还能看到登录、收藏、评论、回复、圈子等能力，但尚未证明喵趣当前服务对这些接口全部开放。因此 v2 先保证阅读闭环，不把未经验证的账号能力硬塞进正式实现。

实机基础阅读稳定后，再逐项验证：

1. 账号登录/游客登录。
2. 云收藏。
3. 漫画评论。
4. 章节评论。
5. 评论回复。
6. MX 可选增强接口。

任何登录态数据不得写入源码、文档或仓库。

## 已知风险

- 动态配置/CDN 域名可能轮换；必须继续依赖配置入口，而不是固定当前 `txtong/qitatong/imgtong/mhatt` 域名。
- 官方 `baseUrl` 主要用于宿主展示/跳转；实际阅读链依赖动态配置。
- 服务可能根据网络区域、TLS、UA 或 CDN 状态表现不同，服务器侧 CI 成功不能替代用户实机验证。
- 当前未实现账号/评论等 Plus 第二阶段功能。
- 在用户确认基础阅读闭环前，不发布正式商店。
