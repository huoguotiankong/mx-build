from pathlib import Path

root = Path('.github/handoff/copymangaplus-v4-opencc')
kt = root / 'CopyMangaPlus.kt'
doc = root / 'copymangaplus.md'
s = kt.read_text('utf-8')

# Route all three detail consumers through a dedicated current CopyManga detail contract.
s = s.replace(
    'val root = requestJson("/api/v3/comic2/${enc(pathWord)}?platform=3&_update=true")',
    'val root = requestDetailJson(pathWord)',
)
s = s.replace(
    'val results = requestJson("/api/v3/comic2/${enc(path)}?platform=3&_update=true").results()',
    'val results = requestDetailJson(path).results()',
)
s = s.replace(
    'val comic = requestJson("/api/v3/comic2/${enc(path)}?platform=3&_update=true").results()?.obj("comic")',
    'val comic = requestDetailJson(path).results()?.obj("comic")',
)

# Finish display conversion gaps left by the first OpenCC pass.
s = s.replace(
    'comic.string("alias")?.takeIf(String::isNotBlank)?.let {\n                add(MangaDetailField("别名", listOf(MangaDetailValue(it))))\n            }',
    'simplify(comic.string("alias"))?.takeIf(String::isNotBlank)?.let {\n                add(MangaDetailField("别名", listOf(MangaDetailValue(it))))\n            }',
)
s = s.replace(
    'val authorName = o.string("user_name") ?: user?.string("nickname") ?: user?.string("username") ?: "拷贝用户"\n        val avatar = o.string("user_avatar") ?: user?.string("avatar")',
    'val authorName = simplify(o.string("user_name") ?: user?.string("nickname") ?: user?.string("username") ?: "拷贝用户").orEmpty()\n        val avatar = o.string("user_avatar") ?: user?.string("avatar")',
)

anchor = '''    private fun requestJson(
        path: String,
        method: String = "GET",
        body: FormBody? = null,
        includeToken: Boolean = true,
    ): JsonObject = requestWithCandidates(path, method, body, includeToken, commentOnly = false)

'''
if 'private fun requestDetailJson(pathWord: String)' not in s:
    if anchor not in s:
        raise SystemExit('requestJson anchor missing')
    helper = '''    private fun requestDetailJson(pathWord: String): JsonObject {
        var last: Throwable? = null
        apiCandidates(includeLast = true).distinctBy { it.serialized }.forEach { route ->
            try {
                val query = if (route.kind == RouteKind.COPY) {
                    "in_mainland=true&request_id=&platform=3"
                } else {
                    "in_mainland=true&platform=3"
                }
                val request = Request.Builder()
                    .url("https://${route.host}/api/v3/comic2/${enc(pathWord)}?$query")
                    .headers(
                        if (route.kind == RouteKind.COPY) {
                            copyDetailHeaders(includeToken = true)
                        } else {
                            apiHeaders(RouteKind.HOT, includeToken = true)
                        },
                    )
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val root = parseResponse(response.code, response.body.string())
                    if (root.results()?.obj("comic") == null) {
                        throw IOException("当前节点没有返回漫画详情")
                    }
                    preferences.edit().putString(PREF_LAST_HOST, route.serialized).apply()
                    return root
                }
            } catch (e: Throwable) {
                last = e
            }
        }
        throw IOException(last?.message ?: "所有拷贝/热辣详情线路均不可用", last)
    }

    private fun copyDetailHeaders(includeToken: Boolean): Headers {
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        return Headers.Builder()
            .set("User-Agent", "COPY/$COPY_DETAIL_VERSION")
            .set("source", "copyApp")
            .set("deviceinfo", copyDeviceInfo())
            .set("dt", SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date()))
            .set("platform", "3")
            .set("referer", "com.copymanga.app-$COPY_DETAIL_VERSION")
            .set("version", COPY_DETAIL_VERSION)
            .set("device", copyDevice())
            .set("pseudoid", copyPseudoId())
            .set("Accept", "application/json")
            .set("region", "0")
            .set(
                "Authorization",
                if (includeToken && token().isNotBlank()) "Token ${token()}" else "Token",
            )
            .set("umstring", COPY_UMSTRING)
            .set("x-auth-timestamp", timestamp)
            .set("x-auth-signature", copySignature(timestamp))
            .build()
    }

'''
    s = s.replace(anchor, anchor + helper, 1)

if 'private const val COPY_DETAIL_VERSION = "3.0.6"' not in s:
    const_anchor = '        private const val COPY_REFERER_VERSION = "3.0.0"\n'
    if const_anchor not in s:
        raise SystemExit('detail version constant anchor missing')
    s = s.replace(
        const_anchor,
        const_anchor + '        private const val COPY_DETAIL_VERSION = "3.0.6"\n',
        1,
    )

kt.write_text(s, 'utf-8')

d = doc.read_text('utf-8')
d = d.replace('- 源码 `versionCode = 3`', '- 源码 `versionCode = 4`')
d = d.replace('- Android APK versionCode：`106003`', '- Android APK versionCode：`106004`')
d = d.replace('- APK versionName：`1.6.3`', '- APK versionName：`1.6.4`')
if '## v4 详情页与繁简转换' not in d:
    d += '''

## v4 详情页与繁简转换

2026-09-04 用户实机确认：v3 的 Copy 线路首页/列表已经可以加载，但作品详情仍提示“拷贝漫画详情为空”；热辣线路基本正常，章评已经可以显示。因此 v4 不重写已验证可用的列表、书评和章评流程，只为详情请求增加独立契约。

独立探针对七个 Copy 节点复现了当前现象：`/api/v3/comic2/<path_word>` 会返回 HTTP 200 / code 200，但 `results.comic` 为空。当前 Venera CopyManga 配置使用 `COPY/3.0.6`、`region=0`，详情请求为 `/api/v3/comic2/<id>?in_mainland=true&request_id=&platform=3`（region=0 时 request_id 为空）。v4 因此仅让详情请求使用这一 3.0.6 / region=0 契约；普通列表、章节、书评和章评继续保留此前已验证的请求头，降低回归风险。详情仍严格要求 `results.comic` 非空，否则继续尝试下一候选节点。

v4 同时增加默认开启、可关闭的繁体转简体功能。使用 `io.github.laisuk:openccjava:1.4.2` 的 `t2s` 转换，覆盖漫画标题、作者、标签、简介、别名、章节分组、章节名、详情字段、书评/章评昵称和正文。作者与标签同时缓存源站原名和简体显示名到同一 `path_word`，保证点击跳转不因转换失效。URL、UUID、评论 ID、登录参数、图片地址和用户发送前的评论原文不改写。

图标继续使用用户确认的蓝底白色原子状 CopyManga 图标资源。评论页长期显示的“请先登录 / 漫画源设置”底栏属于 MX App 宿主，已经在 APP 侧单独处理，不通过伪造扩展登录能力隐藏。

上述代码需要通过当前 Keiyoushi Spotless / Debug 编译 / Release lint 后写入 `mx-dev`；详情、图标和繁简显示最终仍以 Android 实机反馈为准。
'''
doc.write_text(d, 'utf-8')
