#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "dev")
source = root / "src/zh/copymangaplus/src/eu/kanade/tachiyomi/extension/zh/copymangaplus/CopyMangaPlus.kt"
docs = root / "docs/sources/copymangaplus.md"

text = source.read_text(encoding="utf-8")
start = text.index("    private fun requestSearchJson(page: Int, query: String): JsonObject {")
end = text.index("    private fun requestDetailJson(pathWord: String): JsonObject {", start)
new_block = '''    private fun requestSearchJson(page: Int, query: String): JsonObject {
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
if "var firstEmpty: Pair<ApiRoute, JsonObject>? = null" not in text[start:end]:
    text = text[:start] + new_block + text[end:]
source.write_text(text, encoding="utf-8")

d = docs.read_text(encoding="utf-8")
d = d.replace('- 源码 `versionCode = 5`', '- 源码 `versionCode = 6`', 1)
d = d.replace('- Android APK versionCode：`106005`', '- Android APK versionCode：`106006`', 1)
d = d.replace('- APK versionName：`1.6.5`', '- APK versionName：`1.6.6`', 1)
d = d.replace('`https://api.2026copy.com/api/v3/system/network2?platform=3`', '`https://api.copy-manga.com/api/v3/system/network2?platform=3`', 1)
marker = "## v6 搜索与 Copy 线路修复（2026-09-12）"
if marker not in d:
    d += '''\n\n## v6 搜索与 Copy 线路修复（2026-09-12）\n\n用户实机反馈优先：v5 搜索不可用；Copy 漫画线路持续不可用，只有热辣漫画线路可用。\n\nv6 定向修复：\n\n- Copy 普通内容请求统一切换为 `COPY/3.0.6` 完整移动签名契约，使用 `region=0`，并带稳定 `deviceinfo / device / pseudoid`、`dt / umstring / x-auth-*`。\n- Copy 动态发现入口改为 `api.copy-manga.com/api/v3/system/network2?platform=3`；路由 schema 升到 5，升级后清理旧 Copy 动态节点与最近成功节点缓存。\n- 搜索采用 `/api/v3/search/comic` → `/api/kb/web/searchb/comics` → 其它候选节点的回退链。接口报错、缺少 `results.list` 或返回合法空列表时继续尝试；只有全部候选都无命中时才返回第一个合法空结果。\n- 普通路由增加业务 payload 校验，防止 HTTP/API code 200 但无真实列表、详情或章节数据的节点被缓存为成功线路。\n- 热辣线路请求协议保持不变，避免破坏已由用户实机确认可用的线路。\n- 本轮版本：源码 `versionCode=6`，Android versionCode `106006`，versionName `1.6.6`。\n\nCI、签名和测试商店成功只证明源码、构建、签名与分发链路；Copy 线路和搜索最终仍以 Android 实机测试为准。\n'''
docs.write_text(d, encoding="utf-8")
