from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
source = root / "src/zh/copymangaplus/src/eu/kanade/tachiyomi/extension/zh/copymangaplus/CopyMangaPlus.kt"
build = root / "src/zh/copymangaplus/build.gradle.kts"
doc = root / "docs/sources/copymangaplus.md"

s = source.read_text("utf-8")

old_chapters = '''            val root = requestJson(
                "/api/v3/comic/${enc(pathWord)}/group/${enc(groupWord)}/chapters?limit=$CHAPTER_PAGE&offset=$offset&platform=3",
            )
'''
new_chapters = '''            val root = requestChapterListJson(pathWord, groupWord, CHAPTER_PAGE, offset)
'''
if old_chapters not in s and new_chapters not in s:
    raise SystemExit("chapter list request anchor missing")
s = s.replace(old_chapters, new_chapters)

old_pages = '''        val result = runCatching {
            requestJson("/api/v3/comic/${enc(comic)}/chapter/${enc(chapterId)}?platform=3&_update=true")
        }.getOrElse {
            requestJson("/api/v3/comic/${enc(comic)}/chapter2/${enc(chapterId)}?platform=3&_update=true")
        }.results()?.obj("chapter") ?: throw IOException("章节正文为空")
'''
new_pages = '''        val result = requestPageJson(comic, chapterId).results()?.obj("chapter")
            ?: throw IOException("章节正文为空")
'''
if old_pages not in s and new_pages not in s:
    raise SystemExit("page request anchor missing")
s = s.replace(old_pages, new_pages)

helper_anchor = '''    private fun requestDetailJson(pathWord: String): JsonObject {
'''
if "private fun requestChapterListJson(" not in s:
    helpers = '''    private fun requestChapterListJson(
        pathWord: String,
        groupWord: String,
        limit: Int,
        offset: Int,
    ): JsonObject {
        var firstEmpty: Pair<ApiRoute, JsonObject>? = null
        var last: Throwable? = null
        apiCandidates(includeLast = true).distinctBy { it.serialized }.forEach { route ->
            val query = if (route.kind == RouteKind.COPY) {
                "limit=$limit&offset=$offset&in_mainland=true&request_id="
            } else {
                "limit=$limit&offset=$offset&platform=3"
            }
            val path = "/api/v3/comic/${enc(pathWord)}/group/${enc(groupWord)}/chapters?$query"
            try {
                val request = Request.Builder()
                    .url("https://${route.host}$path")
                    .headers(apiHeaders(route.kind, includeToken = true))
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val root = parseResponse(response.code, response.body.string())
                    val list = root.results()?.array("list")
                        ?: throw IOException("当前节点没有返回章节列表")
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
        firstEmpty?.let { (route, root) ->
            preferences.edit().putString(PREF_LAST_HOST, route.serialized).apply()
            return root
        }
        throw IOException(last?.message ?: "所有章节线路均不可用", last)
    }

    private fun requestPageJson(comic: String, chapterId: String): JsonObject {
        var last: Throwable? = null
        apiCandidates(includeLast = true).distinctBy { it.serialized }.forEach { route ->
            val paths = if (route.kind == RouteKind.COPY) {
                listOf(
                    "/api/v3/comic/${enc(comic)}/chapter2/${enc(chapterId)}?in_mainland=true&request_id=",
                )
            } else {
                listOf(
                    "/api/v3/comic/${enc(comic)}/chapter/${enc(chapterId)}?platform=3&_update=true",
                    "/api/v3/comic/${enc(comic)}/chapter2/${enc(chapterId)}?platform=3&_update=true",
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
                        val chapter = root.results()?.obj("chapter")
                            ?: throw IOException("当前节点没有返回章节正文")
                        if (chapter.array("contents").isNullOrEmpty()) {
                            throw IOException("当前节点没有返回正文图片")
                        }
                        preferences.edit().putString(PREF_LAST_HOST, route.serialized).apply()
                        return root
                    }
                } catch (e: Throwable) {
                    last = e
                }
            }
        }
        throw IOException(last?.message ?: "所有正文线路均不可用", last)
    }

'''
    if helper_anchor not in s:
        raise SystemExit("requestDetailJson helper anchor missing")
    s = s.replace(helper_anchor, helpers + helper_anchor)

route_entry_old = '                "拷贝漫画·api.copy2000.online",\n                "拷贝漫画·api.copy-manga.com",\n'
route_entry_new = '                "拷贝漫画·api.copy2000.online",\n                "拷贝漫画·api.copy202601.com",\n                "拷贝漫画·api.copy-manga.com",\n'
if "拷贝漫画·api.copy202601.com" not in s:
    if route_entry_old not in s:
        raise SystemExit("route entry anchor missing")
    s = s.replace(route_entry_old, route_entry_new, 1)

route_value_old = '                "copy:api.copy2000.online",\n                "copy:api.copy-manga.com",\n'
route_value_new = '                "copy:api.copy2000.online",\n                "copy:api.copy202601.com",\n                "copy:api.copy-manga.com",\n'
if '"copy:api.copy202601.com"' not in s:
    if route_value_old not in s:
        raise SystemExit("route value anchor missing")
    s = s.replace(route_value_old, route_value_new, 1)

host_old = '        private val COPY_HOSTS = listOf(\n            "api.copy2000.online",\n            "api.copy-manga.com",\n'
host_new = '        private val COPY_HOSTS = listOf(\n            "api.copy2000.online",\n            "api.copy202601.com",\n            "api.copy-manga.com",\n'
copy_hosts_start = s.index("        private val COPY_HOSTS = listOf(")
copy_hosts_end = s.index("        private val HOT_HOSTS = listOf(")
if '"api.copy202601.com"' not in s[copy_hosts_start:copy_hosts_end]:
    if host_old not in s:
        raise SystemExit("COPY_HOSTS anchor missing")
    s = s.replace(host_old, host_new, 1)

if 'private const val CHAPTER_PAGE = 500' in s:
    s = s.replace('private const val CHAPTER_PAGE = 500', 'private const val CHAPTER_PAGE = 100')
elif 'private const val CHAPTER_PAGE = 100' not in s:
    raise SystemExit("chapter page size anchor missing")

if 'private const val ROUTE_SCHEMA = 5' in s:
    s = s.replace('private const val ROUTE_SCHEMA = 5', 'private const val ROUTE_SCHEMA = 6')
elif 'private const val ROUTE_SCHEMA = 6' not in s:
    raise SystemExit("route schema anchor missing")

source.write_text(s, "utf-8")

b = build.read_text("utf-8")
if 'versionCode = 6' in b:
    b = b.replace('versionCode = 6', 'versionCode = 7')
elif 'versionCode = 7' not in b:
    raise SystemExit("versionCode anchor missing")
build.write_text(b, "utf-8")

d = doc.read_text("utf-8")
d = d.replace('- 源码 `versionCode = 6`', '- 源码 `versionCode = 7`')
d = d.replace('- Android APK versionCode：`106006`', '- Android APK versionCode：`106007`')
d = d.replace('- APK versionName：`1.6.6`', '- APK versionName：`1.6.7`')
section = '''

## v7 Copy 完整阅读链路修复（2026-09-12）

v6 已完成列表、搜索、详情协议修复，并已发布 `1.6.6 / 106006` 测试包。后续在线全链路探针确认：v6 章节列表与正文仍沿用旧 `platform=3` 查询参数，导致 Copy 线路无法稳定完成阅读链路。

当前在线协议验证通过的 Copy 阅读链：

- 章节列表：`/api/v3/comic/<path_word>/group/<group>/chapters?limit=100&offset=<n>&in_mainland=true&request_id=`
- 正文：`/api/v3/comic/<path_word>/chapter2/<uuid>?in_mainland=true&request_id=`
- `COPY/3.0.6` 完整签名请求头保持不变。

在线探针使用《魔都精兵的奴隶》样本时，七个既有固定 Copy 节点均通过列表、搜索、详情、章节和正文五段链路；章节接口单页返回 100 条，正文返回 61 页图片。动态发现同时返回当前 API 节点 `api.copy202601.com`，v7 将它加入固定兜底候选。该结果是网络协议验证，不等同于 Android 实机验证。

v7 将 Copy 章节和正文改为上述当前协议，章节分页按当前服务端单页 100 条读取；空章节结果会继续尝试其它候选节点。热辣线路继续保留原 `platform=3` 请求契约，避免 Copy 修复造成已实机正常的热辣回归。正文节点必须实际返回非空 `contents` 才会被记为成功节点。路由 schema 从 5 升到 6，以清除 v6 旧动态节点和最近成功节点缓存。
'''
if '## v7 Copy 完整阅读链路修复' not in d:
    d += section
doc.write_text(d, "utf-8")
