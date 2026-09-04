# 禁漫天堂 Plus（jmcomicplus）维护记录

## 模块

- 目录：`src/zh/jmcomicplus/`
- 扩展：禁漫天堂 Plus
- 技术基线：Kotlin + `@Source` + `KeiSource` + libVersion 1.6
- 内容警告：NSFW

## 线路架构

扩展提供三种数据线路模式：自动、APP/API、网页。

- 自动模式会优先复用上次成功线路；失败时自动切换另一条线路。
- APP/API 节点从站点公布的域名配置获取，缓存 6 小时，并保留内置候选作为故障回退。
- 网页域名通过站点跳转页、发布页和候选域名探测自动更新，缓存 3 小时；扩展设置中可手动固定网页域名。
- 扩展设置提供“立即刷新动态域名”，用于站点换域名后主动重新发现。

## 功能

- 热门、最新、搜索、分类、日/周/月热度排序。
- APP/API 与网页双线路漫画详情、章节目录和正文图片读取。
- 图片分流 1–4 可切换。
- 正文支持 JM 当前纵向分块反混淆算法；根据 `scramble_id`、章节 ID 和图片文件名计算分块数。
- v5 对齐 Keiyoushi 当前禁漫天堂实现：现代章节的 MD5 分片键使用“章节 ID + 去扩展名后的图片序号”，避免把 `.jpg/.webp` 扩展名错误计入哈希导致分片数不一致。
- 账号登录同时尝试 APP/API AVS 登录和网页登录；账号、密码及登录状态只保存在本机偏好，不写入仓库。
- MX 评论能力：书评、章评、评论回复、发表评论、回复评论；当前不声明评论点赞能力。
- MX 增强详情页：作者、作品、登场人物、分类、标签可点击跳转同源搜索/筛选，并显示 JM 编号、章节数、浏览、点赞、评论、当前线路等信息。

## 上游参考

- 参考 Keiyoushi `extensions-source/src/zh/jinmantiantang` 的长期维护经验，重点吸收 Referer/Cloudflare 兼容、封面与正文图片加载、Base64 详情页恢复、镜像域名和筛选逻辑。
- Keiyoushi 当前源仍是网页线路，不提供本项目需要的 APP/API、AVS 登录和 MX 书评/章评能力，因此本源保持 Plus 双线路架构，不直接替换成上游实现。
- 后续计划继续合入上游成熟的网页详情 Base64 恢复、多页正文递归读取和动态镜像源，同时保持 APP/API 优先兜底。

## 评论目标

- 书评：使用 album / manga ID。
- 章评：使用 photo / chapter ID。
- 本源不实现段评。MX 当前评论接口只定义 MANGA 与 CHAPTER 两类目标。

## v4 实机反馈（2026-09-04）

- 扩展可安装且宿主可识别，但图标为错误占位图。
- APP/API：多个 `cdngwc` 节点返回内容被当成 JSON 直接解析，实机出现二进制乱码 + `Unexpected JSON token`；根因修复方向为移除 OkHttp 手工 `Accept-Encoding`、补齐 APP 请求头/基础 Cookie，并对非 JSON 节点自动切换。
- 网页：进入列表持续加载；v5 改为当前 Keiyoushi 已维护的 `18comic.vip / 18comic.ink / jmcomic-zzz.*` 镜像优先，正常请求不再先同步探测一长串候选，失败后再自动刷新域名。
- 网页解析同步参考当前 Keiyoushi 禁漫天堂实现：`/albums` 热门/最新、详情页 Base64 注入内容展开、章节选择器、分页正文选择器。
- v5 图标直接同步当前 Keiyoushi 禁漫天堂官方扩展图标资源。

## 验证状态

- 当前源码候选：`1.6.6`，Android `versionCode=106006`；v4 测试版继续保留用于回退。
- 源码静态结构：已按当前 `mx-dev` 的 MX ABI 与 Keiyoushi 1.6 模块结构编写。
- v4 Keiyoushi 当前上游兼容：已在 `mx-build` 使用当时最新 `keiyoushi/extensions-source` 实际覆盖构建验证。
- v5 正文解混淆修正已合入；v6 在用户实机反馈基础上继续修复 APP/API 解压/请求头、Web 镜像/解析和扩展图标。
- v4 Spotless：`spotlessApply`、`spotlessCheck` 已通过。
- v4 Release 编译：`assembleRelease` 已真实通过。
- v4 Release Lint：`lintRelease` 已真实通过。
- 固定签名：v4 Release APK 已使用项目长期签名构建，并通过 APK 签名证书校验；指定证书 SHA-256 为 `1bbfbdc401ab81dc227bf771c43d5b616d00149f5755691a317a819f5a88f620`。
- 测试仓库：v4 已发布到 `mx-repo` 的 `repo/test`，`repo.json`、`index.json` 和 JMComic Plus APK 公开端点均已通过构建链校验。
- v4 实机已确认：APP/API 与网页线路不可用、图标错误；以上问题进入 v5 修复。v5 构建完成后，登录、书评、章评、回复、增强详情页及长篇正文反混淆仍需继续实机回归。

## 维护注意

JM 的 API 节点、网页域名、APP 版本、图片域名、加密参数和页面结构可能随站点更新变化。出现失效时优先检查动态域名、API setting、登录 AVS、评论接口和 `scramble_id`，不要只替换固定域名。
