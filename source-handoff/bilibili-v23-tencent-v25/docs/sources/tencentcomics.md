# 腾讯动漫（Tencent Comics）

- 模块：`src/zh/tencentcomics/`
- PC 主站：`https://ac.qq.com`
- 移动站：`https://m.ac.qq.com`
- 当前开发版本：`versionCode = 25`
- Source ID：`6353436350537369479L`
- 语言：`zh`
- 兼容基线：沿用当前 Keiyoushi 官方腾讯动漫源的 `HttpSource + libVersion = "1.4"`

> 历史 v11-v15 的完整维护记录保留在 Git 历史；本文件当前聚焦 v17-v23 的 APP 章评、书评、回复、排序、表情与正文替换能力验证。

## v15 实机结论

用户已安装 v15 测试版确认：

- 漫画书评：基本正常，当前不再作为主要阻塞项。
- APP 真章节评论：仍未正常读取。
- 普通标题搜索：搜索报错。

## v16 搜索修复

旧普通搜索仍走失效的移动接口：

`https://m.ac.qq.com/search/result?word=<query>&page=<page>`

v16 已切换到 PC 搜索页：

`https://ac.qq.com/Comic/searchList?search=<query>`

同时：

- 兼容 PC `ret-search-list / mod_book_list` 等结果容器。
- 搜索结果统一映射为 `/comic/index/id/<comicId>`。
- 按 ID 搜索直接请求 PC `/Comic/comicInfo/id/<id>`，避免移动详情页交给 PC 详情解析器。

提交：

- 搜索修复：`0805fea4b0c69283cf9fa82556a8f3fcef74bf80`
- versionCode 16：`1c8f15c4b852a68db65bce303aa1f0a3299aeac5`

当前状态：源码已写入 `mx-dev/main`，尚未构建/实机验证。

## APP 真章节评论

官方 APK `qqcomic_android_12.19.9_dm306015002_arm64.apk` 已确认完整章节评论接口：

`Community/getChapterTopicList`

参数：`comic_id/chapter_id/page/type`，其中历史静态逆向曾推断 `type=1` 最新、`type=2` 热门；v23 已依据用户实机列表顺序反馈修正 UI 映射，详见本文 v23 记录。

响应 `ChapterTopicInfoListResponse` 包含 `topicList/totalCount/tagId/endOfList`；`endOfList == 2` 表示无下一页。

Topic 可映射：`topicId/content/nickName/qqHead/date/goodCount/commentCount/hostQq`。

读取 API 使用 GET + 版本化 Path：

`https://a.ac.qq.com/12.19.9/Community/getChapterTopicList/comic_id/<comicId>/chapter_id/<chapterId>/page/<page>/type/1`

兼容 Host：`android.ac.qq.com`。

## Native 响应解密链

v15 只追到 RequestHelper 的 `ResponseBody.string() -> Gson.fromJson()`，遗漏了更底层的 OkHttp Interceptor。继续逆向后已确认：

`ChapterTopicViewModel`
→ `qe.t`
→ OkHttp
→ `com.network.d.intercept()`
→ `CryptUtils.gudKsDdgRDftyhRz(responseBytes)`
→ 重建 ResponseBody
→ `ResponseBody.string()`
→ Gson。

`CryptUtils` 加载 `lib/arm64-v8a/libcryptutils.so`。

通过 `JNI_OnLoad` / `RegisterNatives` / ELF relocation / DEX native 签名交叉确认：

`CryptUtils.gudKsDdgRDftyhRz([B)[B -> libcryptutils.so + 0x7B658`

因此官方 APP 的 JSON 是在 OkHttp Interceptor 中完成 Native 解密后才交给上层。

## 已确认的密码处理流程

继续追 `0x7B658` 的真实成功控制流后，当前已经确认：

1. HTTP 原始响应先进入 Crypto++ `Base64Decoder`。
2. Base64 解码结果再进入 DES-EDE3（3DES）ECB 解密路径。
3. 解密使用 Crypto++ `StreamTransformationFilter`。
4. `BlockPaddingScheme` 参数明确为 `2`，对应 `PKCS_PADDING`。
5. 解密结果通过 `StringSink` 输出，随后交给上层作为 JSON。

因此当前可复现的数据层流程为：

`HTTP Body -> Base64 decode -> 3DES-EDE3/ECB + PKCS padding -> JSON`

Java/Kotlin 侧预计对应 `DESede/ECB/PKCS5Padding`；在没有确认 Key 前不提交猜测实现。

## 3DES Key 已完整还原

继续对 `0x8BD60` 的 Crypto++ RTTI、vtable、构造参数与最终 `substr(0, 24)` 路径交叉确认后，Key material 已不再是推测状态。

实际生成链：

1. 固定 14 字节二进制输入：
   `CE C7 DA DE EA 98 9A 98 99 89 EB FA FA AA`
2. 进入 `CryptoPP::Weak::MD5` / `HashFilter`，得到 16 字节 MD5 digest。
3. digest 进入 `CryptoPP::HexEncoder`，参数 `uppercase=false`，得到 32 字节小写十六进制 ASCII。
4. 最终执行 `substr(0, 24)`，取前 24 个 ASCII 字节作为 DES-EDE3 Key。

对应：

- MD5 hex：`79739d5caeb71d25be4a5bae2d9bb14d`
- 最终 24 字节 Key：`79739d5caeb71d25be4a5bae`

该 Key 是官方 APK 内固定协议常量派生结果，不是用户 Token、Cookie、账号凭据或设备标识。

## v16 章评 Kotlin 实现

提交 `1898dd8d03f04798c7dc98ccc51a10c90f28db30` 已加入官方 APP 响应解密：

- 如果响应本身已是 JSON，则直接解析，保留兼容性。
- 否则先 Base64 解码。
- 使用 `DESede/ECB/PKCS5Padding` 和上述 24 字节 ASCII Key 解密。
- 解密结果按 UTF-8 解析 JSON。
- 错误诊断只输出 Content-Type、长度和异常类型/消息，不输出完整响应正文或会话信息。

提交 `39ffc9ecf4b0c4e709969ad8bfaf369e54be32b0` 另外兼容 APP JSON 中可能出现的 snake_case 字段：

- `topicList / topic_list`
- `totalCount / total_count`
- `endOfList / end_of_list`
- `topicId / topic_id`
- `hostQq / host_qq`
- `nickName / nick_name`
- `qqHead / qq_head`
- `goodCount / good_count`
- `commentCount / comment_count`

## 本地密码学闭环验证

由于当前执行容器无法解析 `a.ac.qq.com`，无法在 CI 外直接取得带 APP Header 的真实线上响应样本。因此功能真值仍需 Android 实机验证。

但 Java/Android 等价密码学实现已做本地闭环：

`JSON -> DES-EDE3/ECB + PKCS padding -> Base64 -> DESede/ECB/PKCS5Padding -> 原 JSON`

闭环可无损恢复原始 JSON，确认 Java `DESede/ECB/PKCS5Padding` 与逆向得到的 Crypto++ 参数一致。

## 已排除的错误方向

`0x2295D0/0x2295E0/0x229600/0x229640` 等静态数据块经还原是 Native 日志字符串，不是 Key/IV。

例如包括 `ACNativeLog`、参数为空/异常等日志。后续不再把这些数据块作为密钥候选。

## v16 当前验证状态

- v15 书评：用户实机基本正常。
- v16 搜索源码修复：已写入仓库，待 v16 实机验证。
- APP 章评接口/参数/Path：已确认。
- APP API Native 解密拦截器：已确认。
- Native 方法地址：已确认。
- Base64 -> 3DES-EDE3/ECB -> PKCS padding：已确认。
- Key material 生成算法/输入：已确认（原始 14 字节二进制常量 -> MD5 -> lowercase Hex -> 前 24 字节）。
- 正确 24 字节响应 Key：`79739d5caeb71d25be4a5bae`。
- APP GET `sc` 签名：已接入源码。
- v16 章评 Kotlin 解密实现：已写入仓库。
- 首次 Key 修正后重构建 run `33774121757`：因 `runCatching(::decodeAppApiResponse)` 未传 body 导致 Kotlin 编译失败。
- 编译修复提交：`84e06de7c58801d04b065e6bb48602f9de00836d`。
- v16 最终测试构建 run `33774446528`：Release 构建、固定签名证书校验、staging 测试商店生成与在线 staging 索引验证全部通过。
- staging 提交：`dd91ea4`；APK `tachiyomi-zh.tencentcomics-v1.4.16.apk`，Android versionCode `104016`。
- 测试商店提升 run `33774775219`：通过。
- v16 已发布到 `mx-repo/repo/test`，repo 分支提交 `5d856c41412a484af8e56c7fc1efcbd200f1b25b`。
- 公开 `test/index.json` 已确认包含 `eu.kanade.tachiyomi.extension.zh.tencentcomics` v1.4.16 / 104016。
- v16 搜索与 APP 真章评功能：**待用户实机验证**；CI/索引成功不等于功能实机通过。

### v16 Key 回归修正

后续加入 APP GET `sc` 签名时，提交 `1586d7f00020031e11db9bc435c229b486b93c85` 曾把已经逆向确认的响应 3DES Key 误改为对可读字符串 `dmpt@2023#APP` 直接做 MD5 后的前 24 位：

`42e0d587d5bda41326c2000d`

重新对 `0x8BD60` 的实际 Crypto++ 数据流复核后，确认 Key 生成输入是 APK 中的 **14 字节原始二进制常量**：

`CE C7 DA DE EA 98 9A 98 99 89 EB FA FA AA`

它直接进入 `Weak::MD5 -> HashFilter -> HexEncoder(lowercase) -> substr(0, 24)`，并没有先做 XOR 还原。因此正确 24 字节 Key 仍为：

`79739d5caeb71d25be4a5bae`

源码已在提交 `97c74f62588e003e1faf945910b41bdf5e0f0390` 恢复正确 Key。该修正同时解释了为什么“解密代码已经接入”仍可能在实机继续报解密失败：后续签名提交引入了 Key 回归。

## v17：以 Unidbg 动态捕获修正章评响应 Key

v16 实机反馈：两个 APP API Host 均返回 HTTP 200、`Content-Type=text/html; charset=UTF-8`、约 31 KB 响应，但 Kotlin 侧 3DES 解密报 `pad block corrupted`。

重新运行签名匿名请求探针后确认，腾讯该 API 的**正常加密响应本身就使用 `text/html; charset=UTF-8` Content-Type**；不能据此判断为网页。干净探针返回 34176 字节且整段为合法 Base64，两个 Host、OkHttp 4.10.0/4.12.0 结果一致。

随后以 Unidbg 直接执行官方 `libcryptutils.so` 的 `CryptUtils.gudKsDdgRDftyhRz()`，并在 DES-EDE3 `SetKey()` 位置动态捕获实际 24 字节 Key。捕获 Key 的 SHA-256：

`b1df9ba224ae925bbd5f9c6a39396da87e9b76f7cf0621dafa554665232e4387`

候选验证：

- `79739d5caeb71d25be4a5bae` 的 SHA-256 不匹配。
- `42e0d587d5bda41326c2000d` 的 SHA-256 与 Native 捕获值完全一致。

同一次 Unidbg 运行已使用官方 Native 成功解密真实 `getChapterTopicList` 响应，得到 `error_code=2`、`total_count` 和真实 `topic_list` JSON。因此 v16 后续把 Key 改回 `79739...` 是错误回归。

v17 修复：

- 响应 3DES Key 恢复为动态捕获确认的 `42e0d587d5bda41326c2000d`。
- 保留 v16 已确认的 APP Path API、`sc` 签名、Base64 -> DESede/ECB/PKCS5Padding 解密。
- 保留 PC 搜索修复。
- versionCode 升至 17。

当前功能真值：
- Native 响应解密：已通过官方 .so + 真实线上响应动态验证。
- Kotlin 等价实现：待 v17 Android 实机最终确认。

### v17 构建与测试商店发布

- v17 响应 Key 修复提交：`e9e57b5ee83ad4bd000b87c2a908978fa11e33aa`。
- versionCode 17 提交：`a88014bb3f27c97959c09523508686caa77e80e6`。
- v17 维护记录提交：`e2c11aa9edde042bd3ea32fd08451424e522e893`。
- 公共测试构建 run：`33776024353`。
- Release 编译：通过。
- 固定扩展签名身份校验：通过。
- APK 签名证书校验：通过。
- staging 测试商店生成与在线索引验证：通过。
- staging 索引确认：`eu.kanade.tachiyomi.extension.zh.tencentcomics` v1.4.17 / Android versionCode `104017`。
- 测试商店提升 run：`33776215145`，通过。
- 公开 `mx-repo/repo/test/index.json` 已确认包含 v1.4.17 / 104017。
- 公开测试 APK：`tachiyomi-zh.tencentcomics-v1.4.17.apk`。
- `mx-repo/repo` 发布提交：`89f1f5df3e37f29b4305de769486b48944df84f3`。

当前实机待验证：

- 普通标题搜索是否恢复。
- APP 真章节评论是否能正常解密并显示正文、昵称、头像、时间、点赞和回复数。

特别说明：v17 的 3DES Key 以 Unidbg 动态执行官方 `libcryptutils.so`、真实线上响应成功解密和 `SetKey()` 捕获 SHA-256 三重证据为准；此前仅靠静态控制流推导得到的 `79739d5caeb71d25be4a5bae` 已确认不是实际运行时 Key。

## v18：章节评论回复列表

v17 实机反馈：

- APP 真章节评论主列表：已成功显示，章评读取和响应解密实机通过。
- 点击某条章评的“回复(n)”后，MX 显示 `Comment replies are not supported`。

根因不是腾讯 API 故障，而是腾讯扩展只实现了 `getComments()`，没有覆盖 MX `CommentSource.getCommentReplies()`；同时 `canReply=false` 只表示不开放**发回复**，并不妨碍读取已有回复。

官方 APK 12.19.9 字节码已确认一级回复接口：

`Comment/getTopicCommentList`

`q3.z()` 参数精确映射：

- `topic_id`
- `target_type`
- `page`
- `listcnt`
- 可选 `comic_id`

章节话题使用 `target_type=3`。

线上签名探针已以真实公开话题验证成功：

- `error_code=2`
- data keys：`comment_count,end_of_list,list`
- 一级回复字段：`comment_id/content/host_qq/nick_name/qq_head/date/good_count/reply_count/reply_list`
- `reply_list` 中还包含二级回复预览。

v18 实现：

- 覆盖 `getCommentReplies(target, comment, page)`。
- 对 `CHAPTER` 目标请求官方 `Comment/getTopicCommentList`。
- 复用 v17 已实机通过的 APP `sc` 签名和 Base64 + 3DES 解密。
- 将一级回复映射为 MX `Comment`。
- 使用 `comment_count` / `end_of_list` 做分页。
- `canReply` 继续保持 `false`，即只读取已有回复，不开放发回复写操作。
- 作品书评回复暂未接入，当前修复范围仅为 APP 真章节评论回复。
- versionCode 升至 18。

当前状态：源码已写入，待构建和 Android 实机验证。

### v18 构建与测试商店发布

- v18 回复列表实现提交：`341ce2d9b489bc09bc7337776056248d34e3c3a6`。
- versionCode 18 提交：`c8c16db9a8f73ea8d0e6f57368e33828080a861d`。
- v18 维护记录提交：`8e2d5f8585ffa547e7f5b8ef20356ab340a12f7d`。
- 公共测试构建 run：`33778012830`，Release 编译和固定签名证书校验通过。
- staging 测试商店生成与在线索引验证通过。
- staging 索引：v1.4.18 / Android versionCode `104018`。
- 测试商店提升 run：`33778208216`，通过。
- 公开 `mx-repo/repo/test/index.json` 已确认包含腾讯动漫 v1.4.18 / 104018。
- 公开测试 APK：`tachiyomi-zh.tencentcomics-v1.4.18.apk`。
- `mx-repo/repo` 当前发布提交：`1d8a7efad0bb95165c083043ac7ae4de20ac1a92`。

v18 后续实机反馈：
- 章节评论“回复(n)”已可正常进入并加载一级回复列表。
- 回复读取链路已实机确认可用。
- 当前仍保持 `canReply=false`，即不开放发回复写操作。

## v19：章节评论“默认 / 最新”官方排序

用户实机继续反馈：v18 章评与回复链路已经可用，但章评主列表没有覆盖官方 APP 的两种排序语义。

重新对官方 APK 12.19.9 的 `ChapterTopicViewModel` 字节码核对后曾得到静态推断：

- `loadInitData...hot...` 调用 `Community/getChapterTopicList` 时传 `type=2`。
- `loadInitData...new...` 调用同一接口时传 `type=1`。

因此 v19 当时实现为 `默认 -> type=2`、`最新 -> type=1`。v23 用户实机截图证明这组 UI 标签语义与实际显示顺序相反，后续以实机反馈为准。

v19 实现：

- 新增 MX `SortableCommentSource` ABI 到腾讯模块。
- 章评筛选项加入 `默认`、`最新`。
- 保留 v17 已实机通过的 `sc`、Base64 + 3DES 解密。
- 保留 v18 已实机通过的章评回复读取。
- `end_of_list == 2` 继续按官方 `PagingData.isNoMore` 语义判定无下一页。
- versionCode 升至 19。

提交：

- SortableCommentSource ABI：`2bb081e0b7f33cc45b0ce33e171c18d7e42dec36`
- 章评默认/最新排序：`85ff9781e565240d9dc1b6c396500618996812e3`
- versionCode 19：`1ffe35eda2545945f0082e4ac25551e145dedfcc`
- v19 测试构建目标：`9a7eb70d09642712b2d846a723fbad33c3610d2b`

### v19 构建与测试商店发布

- 公共构建检查 run：`33780356023`，Debug、Release、lint 及 MX ABI/R8 校验通过。
- 固定签名测试商店构建 run：`33780625640`，Release 编译、固定签名校验、staging 生成与在线 staging 索引校验通过。
- staging 已确认：v1.4.19 / Android versionCode `104019`。
- 测试商店提升 run：`33780837524`，通过。
- 公开 `mx-repo/repo/test/index.json` 已确认腾讯动漫为 v1.4.19 / `104019`。
- 公开测试 APK：`tachiyomi-zh.tencentcomics-v1.4.19.apk`。

## v20-v22：APP 书评与正文替换能力

### v20：APP 作品书评主列表

官方 12.19.9 APK 已确认作品社区接口：

- `CommunityTag/detailPage`：通过 `target_type=1 / target_id=<comicId> / comic_id=<comicId>` 获取作品社区 `tag_id` 与首屏数据。
- `CommunityTag/topicList`：参数 `tag_id / type / page_id / comic_id`，使用 `next_page_id` 游标连续分页。
- `recommend_topic` 对应官方精选/推荐话题。
- `new_publish_topic` 对应按发布时间的新话题。
- `new_topic` 对应按最新回复/活动排序的数据。

扩展采用 APP 优先、PC `Community/topicList` 仅在 APP 请求失败时兜底；作品评论筛选显示为“精华 / 最新”。

### v21：作品书评回复

线上探针确认作品书评话题同样可调用 `Comment/getTopicCommentList`，读取回复时 `target_type=3`，可复用已经实机验证的章评回复解密、签名与分页链路。

- 书评回复源码提交：`6fb8cb8cb9870378269fe9631014bbe90f35372f`。
- versionCode 21：`d257f257a880cd0386f814fb1be808e0a2a808a7`。
- Trusted Check：`33819293409`，Debug / Release / lint / MX ABI-R8 全部通过。
- 测试商店：`33819432629`，固定签名 Release、APK 证书校验、staging 索引校验全部通过。
- 测试商店提升：`33819542426`，通过。
- 公开测试版本：`1.4.21 / 104021`。

### v22：正文替换入口能力

MX 的正文替换引擎位于宿主 APP；漫画扩展只需要显式实现 `ChapterContentReplacementSource` 才暴露章节列表与阅读器中的替换入口。该设计与哔哩哔哩漫画扩展一致，普通未实现该接口的源继续隐藏入口。

腾讯动漫已：

- 加入 `ChapterContentReplacementSource` ABI。
- 在 `TencentComics` 显式实现该能力，默认 `showInChapterList=true`、`showInReader=true`。
- 能力提交：`d0a0139e418fb1a739c87f6fc13091ccffce3d3c`。
- versionCode 22：`f8c7323faff2466f3e0ad2b4bcef40b09cbe6de5`。
- 修复后 Trusted Check `33819918897`：Debug / Release / lint / MX ABI-R8 通过。
- 测试商店 `33820031284`：固定签名 Release、APK 证书校验、staging 生成和索引验证通过。
- 测试商店提升 `33820146007`：通过。
- 公开 `mx-repo/repo/test/index.json` 已确认腾讯动漫 `1.4.22 / 104022`。

## v23：章评排序、书评精华连续浏览与表情显示

### 用户实机反馈

v22 Android 实机截图确认三个问题：

1. 章评“默认 / 最新”实际显示顺序与 v19 静态推断的 UI 标签语义相反；“最新”页反而集中出现高赞、高回复旧评论。
2. 作品书评“精华”只能看到很少几条，无法像“最新”一样继续下拉。
3. 评论正文中的腾讯内置表情显示成 `[:002:]`、`[:000:]` 等数字代码。

按项目规则，实机反馈优先于旧静态推断。

### 书评精华分页根因实测

针对实机截图中的《我的徒弟都是大反派》（comic_id `650445`）运行匿名 APP API 深分页探针：

- `tag_id=95127`
- `topic_count=11324`
- `recommend_topic` 首屏只有 `4` 条
- `recommend_topic.next_page_id=-1`
- 因此官方精选流本身就只有 4 条，原扩展并非漏掉了一个可用的精选游标。
- 同一作品的 `new_publish_topic` 连续探测 30 页，每页 10 条且游标持续变化，累计 300 条唯一话题，证明通用游标分页链路正常。

v23 因此采用组合策略：

- “精华”第一页先放官方 `recommend_topic` 精选；
- 随后接 `new_topic` 活跃讨论，并以它的游标继续分页；
- 对 topic_id 去重；
- “最新”继续独立使用 `new_publish_topic`，保持按发布时间查看。

这样既保留腾讯官方精选置顶，又不会在精选只有几条时直接结束整个页面。

深分页探针 run：`33822179135`，成功。

### 章评排序修正

v23 按用户实机列表效果交换 UI 对应关系：

- `默认 -> type=1`
- `最新 -> type=2`

这项定义以 v23 后续 Android 实机复测为最终真值；如腾讯服务端继续调整排序策略，仍以实机表现优先。

### 腾讯内置表情

官方 APK `assets/chatbuildinemojis/` 内含 `emoji_0.png` 到 `emoji_61.png` 共 62 个内置表情，对应评论中的 `[:000:]`～`[:061:]` 代码。

当前 MX `Comment` ABI 的正文是纯 `String`，扩展层没有“正文内联图片/Span”字段，无法仅靠漫画扩展把腾讯原 PNG 原样嵌入评论卡片。v23 在扩展层做兼容转换：

- 已知 `[:NNN:]` 映射为接近含义的 Unicode emoji 或文本符号；
- 未知代码保留原文，避免静默丢失内容；
- APP 章评、APP 书评、回复，以及 PC 书评兜底路径统一经过该转换。

如果后续要求 1:1 显示腾讯原始 PNG 表情，需要先扩展 MX 宿主评论 ABI/富文本渲染能力，再由插件传递内联表情资源。

### v23 提交、构建与发布

- 评论优化源码：`826819852f06c179f6e10d94cb77b59b58f8dc72`
- versionCode 23：`672316eb8359ddbfc0bb43ad2253e2c7504062b4`
- 合并到 `mx-dev/main`：`e654250889a1bf3545273ae151d2d8cf1b71c8a9`
- 分支预检 Trusted Check：`33822553856`，Debug / Release / lint / MX ABI-R8 全部通过。
- main 最终 Trusted Check：`33822733012`，Debug / Release / lint / MX ABI-R8 核心校验通过。
- 固定签名测试商店构建：`33822806805`，Release、固定签名身份、APK 证书、staging 生成与 staging 索引校验通过。
- staging 索引：`1.4.23 / 104023`。
- 首次提升 run `33822926040` 已成功提交 `repo` 分支，但最后一步命中 raw.githubusercontent 缓存，短暂读取到旧 `1.4.22` 因而标红；这不是构建或提交失败。
- `mx-repo/repo/test/index.json` GitHub 分支内容已直接确认 `1.4.23 / 104023`。
- 缓存刷新后的重新验证提升 run `33823017521`：成功。

v23 当前状态：**源码、构建、固定签名、测试仓库发布已验证；章评排序语义、精华连续浏览和表情替换效果待用户 Android 实机确认。**

## v24：评论图片显示

### 官方数据字段确认

继续以用户提供的腾讯动漫 Android 12.19.9 APK 为事实来源复核评论模型：

- `com.qq.ac.android.bean.Topic` 明确包含 `attach: ArrayList`。
- `com.qq.ac.android.bean.Topic$Attach` 明确包含 `picUrl`、`width`、`height`。
- 因此 APP 作品书评与 APP 章节话题中的图片不应从正文文本猜测，而应读取 `topic.attach[].picUrl`。
- `CommentInfo` 回复模型当前没有对应 `attach` 字段；本版不为回复臆造不存在的图片字段。若回复正文自身包含可识别图片直链，MX 宿主仍可按通用富媒体规则显示。

### v24 实现

- APP 作品书评：`communityTopicToComment()` 保留标题/正文，同时提取 `attach[].picUrl`。
- APP 章节评论：`topicToComment()` 同样提取 `attach[].picUrl`。
- 图片 URL 继续经过现有 `normalizeUrl()` 统一处理协议相对地址和 HTTP -> HTTPS。
- PC 作品评论兜底路径额外读取 `.comment-content-detail img`，避免 APP 路由不可用时图片被静默丢失。
- 允许“纯图片评论”：即正文为空但官方 `attach` 有图片时仍构造 Comment。
- MX 客户端负责把正文中的图片直链原生渲染为图片；扩展不新增 ABI，普通 Mihon / Komikku 行为不变。
- 本版只扩展只读评论数据，不改变 v23 已有章评排序、书评分页、表情转换、回复读取、签名和解密链路。

### 验证边界

- 腾讯图片字段：已通过官方 APK 12.19.9 DEX 模型静态确认。
- 源码编译 / Spotless / Release-R8 / lint：由本次补丁工作流执行后再记录结果。
- 评论图片最终显示效果：仍需 Android 实机使用匹配的 MX 富媒体评论版本验证。

## v25：评论图片实机修复

### 用户实机反馈

v24 已能从官方 `Topic.attach[].picUrl` 取到真实图片地址，但 Android 实机仍显示为纯文本 URL。例如：

`https://manhua.acimg.cn/manhua/...jpg/0?tp=sharp`

该地址本身是腾讯图片 CDN 的有效图片，但路径在 `.jpg` 后仍带 `/0?tp=sharp`，MX 的通用直链启发式识别不会把它当作传统“以 .jpg 结尾”的图片。

### v25 修复

- 不再把 `attach[].picUrl` 作为裸 URL 拼到评论正文；统一输出显式 Markdown 图片 `![](https://...)`。
- APP 作品书评、APP 章节评论、PC 作品评论兜底路径全部复用同一个富媒体正文拼装器，因此该修复统一生效。
- 回复模型历史版本没有 `attach` 字段，但 v25 对 `CommentInfo` 也做同一兼容读取：如果腾讯后端在回复对象中返回 `attach`，会直接显示；没有该字段时行为与 v24 一致。
- v23 已有 `[:NNN:]` 腾讯内置表情兼容转换继续保留；本版不回退。
- 仍允许纯图片评论；不改变章评排序、书评分页、APP 解密/签名链路。

验证边界：构建/签名通过不等于实机图片已验证，最终以 Android 实机复测为准。
