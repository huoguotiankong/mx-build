from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "dev")
source = root / "src/zh/copymangaplus/src/eu/kanade/tachiyomi/extension/zh/copymangaplus/CopyMangaPlus.kt"
docs = root / "docs/sources/copymangaplus.md"

text = source.read_text("utf-8")
old = '''    private fun requestSearchJson(page: Int, query: String): JsonObject {
        val offset = (page.coerceAtLeast(1) - 1) * PAGE_SIZE
        val encodedQuery = enc(query)
        var last: Throwable? = null
        apiCandidates(includeLast = true).distinctBy { it.serialized }.forEach { route ->
            val paths = if (route.kind == RouteKind.COPY) {
                listOf(
                    "/api/v3/search/comic?limit=$PAGE_SIZE&offset=$offset&q=$encodedQuery&q_type=",
                    "$COPY_WEB_SEARCH_PATH?limit=$PAGE_SIZE&offset=$offset&q=$encodedQuery&q_type=",
                )
            } else {
                listOf(
                    "/api/v3/search/comic?platform=3&q=$encodedQuery&limit=$PAGE_SIZE&offset=$offset&_update=true",
                )
            }
            paths.forEach { path ->
                try {
                    val request = Request.Builder()
                        .url("https://${route.host}$path")
                        .headers(apiHeaders(route.kind, includeToken = true))
                        .get()
                        .build()
                    client.newCall(request).execute().use { response ->
                        val root = parseResponse(response.code, response.body.string())
                        if (root.results()?.array("list") == null) {
                            throw IOException("当前节点没有返回搜索列表")
                        }
                        preferences.edit().putString(PREF_LAST_HOST, route.serialized).apply()
                        return root
                    }
                } catch (e: Throwable) {
                    last = e
                }
            }
        }
        throw IOException(last?.message ?: "所有搜索线路均不可用", last)
    }
'''
new = '''    private fun requestSearchJson(page: Int, query: String): JsonObject {
        val offset = (page.coerceAtLeast(1) - 1) * PAGE_SIZE
        val encodedQuery = enc(query)
        var firstEmpty: Pair<ApiRoute, JsonObject>? = null
        var last: Throwable? = null
        apiCandidates(includeLast = true).distinctBy { it.serialized }.forEach { route ->
            val paths = if (route.kind == RouteKind.COPY) {
                listOf(
                    "/api/v3/search/comic?limit=$PAGE_SIZE&offset=$offset&q=$encodedQuery&q_type=",
                    "$COPY_WEB_SEARCH_PATH?limit=$PAGE_SIZE&offset=$offset&q=$encodedQuery&q_type=",
                )
            } else {
                listOf(
                    "/api/v3/search/comic?platform=3&q=$encodedQuery&limit=$PAGE_SIZE&offset=$offset&_update=true",
                )
            }
            paths.forEach { path ->
                try {
                    val request = Request.Builder()
                        .url("https://${route.host}$path")
                        .headers(apiHeaders(route.kind, includeToken = true))
                        .get()
                        .build()
                    client.newCall(request).execute().use { response ->
                        val root = parseResponse(response.code, response.body.string())
                        val list = root.results()?.array("list")
                            ?: throw IOException("当前节点没有返回搜索列表")
                        if (list.isNotEmpty()) {
                            preferences.edit().putString(PREF_LAST_HOST, route.serialized).apply()
                            return root
                        }
                        if (firstEmpty == null) firstEmpty = route to root
                    }
                } catch (e: Throwable) {
                    last = e
                }
            }
        }
        firstEmpty?.let { (route, root) ->
            preferences.edit().putString(PREF_LAST_HOST, route.serialized).apply()
            return root
        }
        throw IOException(last?.message ?: "所有搜索线路均不可用", last)
    }
'''

if old in text:
    text = text.replace(old, new, 1)
elif "var firstEmpty: Pair<ApiRoute, JsonObject>? = null" not in text:
    raise SystemExit("requestSearchJson does not match expected v6 source")

if 'private const val COPY_VERSION = "3.0.6"' not in text or 'private const val COPY_UA = "COPY/3.0.6"' not in text:
    raise SystemExit("Copy 3.0.6 request contract missing")

source.write_text(text, "utf-8")

d = docs.read_text("utf-8")
d = d.replace('- 源码 `versionCode = 5`', '- 源码 `versionCode = 6`', 1)
d = d.replace('- Android APK versionCode：`106005`', '- Android APK versionCode：`106006`', 1)
d = d.replace('- APK versionName：`1.6.5`', '- APK versionName：`1.6.6`', 1)
d = d.replace('`https://api.2026copy.com/api/v3/system/network2?platform=3`', '`https://api.copy-manga.com/api/v3/system/network2?platform=3`', 1)
marker = "## v6 搜索与 Copy 线路修复（2026-09-12）"
if marker not in d:
    d += '''\n\n## v6 搜索与 Copy 线路修复（2026-09-12）\n\n用户实机反馈优先：v5 搜索不可用；Copy 漫画线路持续不可用，只有热辣漫画线路可用。\n\nv6 定向修复：\n\n1. Copy 普通列表、搜索、详情、章节请求统一到当前 `COPY/3.0.6` 完整移动请求契约，包含稳定的设备标识、时间戳和 `x-auth-*` 签名；同时修正了此前签名计算协议参数的一处字符错误。\n2. Copy 动态发现入口改为 `api.copy-manga.com/api/v3/system/network2?platform=3`，保留现有固定候选；`ROUTE_SCHEMA` 升到 5，升级后清除旧动态节点和最近成功节点缓存。\n3. 搜索补齐 `q_type=`，并加入双接口/多线路回退：Copy `/api/v3/search/comic` 返回合法空列表时不再立即结束，而是继续尝试 `/api/kb/web/searchb/comics`、其它 Copy 节点及热辣候选；只有全部候选均无结果时才返回合法空结果。\n4. 列表、章节和详情增加真实业务数据结构校验，避免 HTTP/API code 200 但缺少目标数据的节点被错误记为成功线路。\n5. 热辣线路协议保持原样，避免破坏用户已实机确认可用的路径。\n6. 正式版本为源码 `versionCode=6` / Android `106006` / `1.6.6`。\n\n构建、签名和测试商店成功只能证明源码与分发链路正常；Copy 线路和搜索最终仍以 Android 实机验证为准。\n'''
docs.write_text(d, "utf-8")
