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
                    if (root.results()?.array("list") == null) {
                        throw IOException("当前节点没有返回章节列表")
                    }
                    preferences.edit().putString(PREF_LAST_HOST, route.serialized).apply()
                    return root
                }
            } catch (e: Throwable) {
                last = e
            }
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

## v7 Copy 章节 / 正文请求契约（2026-09-12）

v6 已完成列表、搜索、详情协议修复，并已发布 `1.6.6 / 106006` 测试包。后续在线全链路探针确认：七个 Copy 固定节点的列表、搜索和详情均可返回真实数据，但 v6 章节列表与正文仍沿用旧 `platform=3` 查询参数，导致 Copy 线路进入作品后无法稳定完成阅读链路。

当前在线协议验证通过的 Copy 阅读链：

- 章节列表：`/api/v3/comic/<path_word>/group/<group>/chapters?limit=<n>&offset=<n>&in_mainland=true&request_id=`
- 正文：`/api/v3/comic/<path_word>/chapter2/<uuid>?in_mainland=true&request_id=`
- `COPY/3.0.6` 完整签名请求头保持不变。

在线探针使用《魔都精兵的奴隶》样本时，七个 Copy 固定节点均返回 100 条章节，`chapter2` 返回 61 页正文图片；列表、搜索、详情、章节、正文五段链路全部通过。该结果是网络协议验证，不等同于 Android 实机验证。

v7 将 Copy 章节和正文改为上述当前协议，同时保留热辣线路原有 `platform=3` 请求契约，避免修复 Copy 时造成已实机正常的热辣回归。正文节点必须实际返回非空 `contents` 才会被记为成功节点。路由 schema 从 5 升到 6，以清除 v6 旧 Copy 动态节点和最近成功节点缓存。
'''
if '## v7 Copy 章节 / 正文请求契约' not in d:
    d += section
doc.write_text(d, "utf-8")
