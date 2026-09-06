# 栗子漫画

## 来源与逆向基线

- 上游 APK 包名：`com.hbsclj.uth`
- 当前样本 Manifest：`versionName=1.1.0`、`versionCode=3`
- 客户端：Flutter AOT（Dart 3.5.4）
- 扩展模块：`src/zh/lizimanhua/`
- 初版目标：热门、最新、搜索、分类筛选、详情、完整目录、正文阅读、官方图标。

## 动态 API 寻址

官方客户端不会固定写死业务 API。启动时会通过 DoH 查询 TXT 记录并解密得到 API Base URL。

TXT 主机：

- `adnet.lizimh.com`
- `adnet.lizi.lat`

客户端内置 DoH：

- `doh.pub`
- `120.53.53.53`
- `dns.alidns.com`
- `223.5.5.5`
- `doh.360.cn`
- `dns.google`

查询格式：`https://<resolver>/resolve?name=<host>&type=txt`。

TXT 的 `data` 是 Base64 编码的 AES-256-ECB/PKCS5Padding 密文。协议密钥来自官方 APK：

`f8d992c74b29491d8a3e3fd5f07389d8`

2026-09-06 实测 TXT 解密得到的可用 API 包括：

- `http://ai.xajtl.com/`
- `http://crm.weichu.asia/`

其中 `https://ai.xajtl.com/app/api/health` 同日实测也返回 HTTP 200；`https://crm.weichu.asia` 当前返回 HTTP 421，因此扩展保留官方 TXT 返回协议并通过健康检查选择可用线路。

扩展必须优先执行动态 TXT 发现；只有动态发现失败时再回退到官方 COS 静态配置与最后已知地址，避免固定域名失效。

官方 COS fallback：

`https://lz-1382057604.cos.ap-hongkong.myqcloud.com/5A6F4D2B7E9A1C3D8F0B2E4A6C8E0D2F4B6A8C0E2D4F6A8B0C2E4D6F8A0C2E4B.json`

该配置使用同一 AES 协议密钥解密。曾观察到 fallback 域名在云构建环境解析到 `127.0.0.1`，因此不能把 fallback 当作主线路。

## 已确认业务 API

- `app/api/health`
- `app/api/config`
- `app/api/home/data`
- `app/api/home/tab/data`
- `app/api/category/list`
- `app/api/rank/list`
- `app/api/search/full?q=`
- `app/api/search/suggest?q=`
- `app/api/detail/<comicId>`
- `app/api/chapter/v2/<chapterId>`

### 分类筛选

`app/api/category/list` 的参数：

- `page`
- `tag`：标签；“全部”时不传
- `class`：漫画地区/类型 ID；全部为 0、不传
- `isend`：连载状态；全部为 0、不传

`app/api/config` 中：

- `cfg_comic_class` 提供国漫、日漫、韩漫、美漫、精选推荐及其 ID。
- `cfg_general.category_tabs` 提供动态标签列表。

### 详情与目录

`app/api/detail/<comicId>` 返回 `data`，漫画主要字段：

- `id`
- `name`
- `alias`
- `author`
- `tags`
- `content`
- `picY` / `picX`
- `isend`
- `hits`
- `score`
- `nums`
- `class`
- `chapters`

章节字段：

- `id`
- `created_at`
- `name`
- `type`
- `cover`
- `order`

扩展输出目录必须按 `order` 从大到小，保证 Mihon/Komikku 的上一章/下一章导航方向正确。

### 正文鉴权

正文请求：

`app/api/chapter/v2/<chapterId>?packname=com.hbsclj.uth&appsign256=<signature>`

`appsign256` 必须使用官方 APK 签名证书 SHA-256 的**大写十六进制**：

`CDD266DF8B2399C24DA38E408B6D9825C7BD2AF073229847F551EA653EA096E1`

小写形式实测返回 HTTP 400 / `未授权的客户端`；大写形式返回 HTTP 200，正文数组位于 `data.pics`。

## 图片线路

`app/api/config -> data.cfg_general.img_generator` 动态下发图片线路。当前无需权限的直连线路包括：

- `https://img.kunmu.asia`（官方配置默认国内线路）
- `https://i.lzimg.xyz`
- `https://cf-1.imgio.club`
- `https://p.imgo.buzz`

扩展优先使用动态配置中的第一个无需权限、非代理直连线路；缺失时回退 `https://img.kunmu.asia`。不要直接把图片相对路径拼到 `cfg_general.pic_domain`，因为该字段当前是百度图片中转 URL 前缀，不是普通 Base URL。

## 验证状态

- APK 身份与版本：已确认。
- Flutter AOT 业务字符串/调用逻辑：已通过 Blutter 逆向确认。
- DoH TXT 动态 API：已在 GitHub Actions 实际查询并解密成功。
- 首页、分类、搜索、详情 API：已在云端实际 HTTP 请求成功。
- 正文 API：已确认只有大写 `appsign256` 可通过鉴权，且测试章节实际返回 62 张 `pics`。
- 图片线路：四条当前无需权限的直连线路均已实测返回 HTTP 200 图片。
- 扩展 `1.6.1`：Spotless、Debug、Release、稳定签名校验均已通过。
- 测试商店：已生成、提交 staging，并由 `mx-repo` 本地 promotion workflow 推送公开测试商店且回读验证成功。
- Mihon/Komikku 实机：待用户安装测试版本验证。


## v1.6.2 性能优化（2026-09-06）

用户实机确认 v1.6.1 基本功能正常，但首页、封面和正文加载偏慢。针对这次反馈做以下优化：

- 动态 DoH 寻址改为“首个成功解析即返回”，不再把 2 个 TXT 主机 × 6 个 DoH 全部串行查询完。
- DoH 成功时不再额外请求 COS fallback；只有动态发现完全失败才访问 fallback。
- `app/api/config` 本身作为线路可用性验证，移除初始化阶段重复的 `app/api/health` 往返。
- 调整最后已知 API 顺序，云端实测 `crm.weichu.asia` 首页约 0.95 秒，`ai.xajtl.com` 约 1.24 秒。
- 图片线路不再机械使用官方配置第一条。云端同一资源测速显示：默认 `img.kunmu.asia` 封面约 1.66 秒、正文首张约 7.10 秒；`p.imgo.buzz` 封面约 0.34 秒、正文约 0.58 秒；`i.lzimg.xyz` 正文约 0.35 秒。因此 v1.6.2 优先 `p.imgo.buzz`，再回退 `i.lzimg.xyz`、`cf-1.imgio.club`、`img.kunmu.asia`。
- 以上测速来自 GitHub Actions 云端，仅用于线路排序；最终速度仍以用户实机网络为准。

验证状态：代码修改后需重新完成 Spotless、Debug/Release 构建、固定签名和测试商店发布；实际速度改善待用户安装 v1.6.2 后确认。
