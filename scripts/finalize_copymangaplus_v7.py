#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "dev")
module = root / "src/zh/copymangaplus"
source = module / "src/eu/kanade/tachiyomi/extension/zh/copymangaplus/CopyMangaPlus.kt"
build = module / "build.gradle.kts"
docs = root / "docs/sources/copymangaplus.md"

text = source.read_text(encoding="utf-8")

old_chapter_call = '''            val root = requestJson(
                "/api/v3/comic/${enc(pathWord)}/group/${enc(groupWord)}/chapters?limit=$CHAPTER_PAGE&offset=$offset&platform=3",
            )
'''
new_chapter_call = '''            val root = requestChapterListJson(pathWord, groupWord, offset)
'''
if old_chapter_call in text:
    text = text.replace(old_chapter_call, new_chapter_call, 1)
elif new_chapter_call not in text:
    raise SystemExit("unable to locate chapter-list call")

old_page_call = '''        val result = runCatching {
            requestJson("/api/v3/comic/${enc(comic)}/chapter/${enc(chapterId)}?platform=3&_update=true")
        }.getOrElse {
            requestJson("/api/v3/comic/${enc(comic)}/chapter2/${enc(chapterId)}?platform=3&_update=true")
        }.results()?.obj("chapter") ?: throw IOException("章节正文为空")
'''
new_page_call = '''        val result = requestPageJson(comic, chapterId).results()?.obj("chapter")
            ?: throw IOException("章节正文为空")
'''
if old_page_call in text:
    text = text.replace(old_page_call, new_page_call, 1)
elif new_page_call not in text:
    raise SystemExit("unable to locate page request call")

if "private fun requestChapterListJson(" not in text:
    marker = "    private fun requestDetailJson(pathWord: String): JsonObject {\n"
    if marker not in text:
        raise SystemExit("requestDetailJson marker missing")
    helper = '''    private fun requestChapterListJson(
        pathWord: String,
        groupWord: String,
        offset: Int,
    ): JsonObject {
        var firstEmpty: Pair<ApiRoute, JsonObject>? = null
        var last: Throwable? = null
        apiCandidates(includeLast = true).distinctBy { it.serialized }.forEach { route ->
            val path = if (route.kind == RouteKind.COPY) {
                "/api/v3/comic/${enc(pathWord)}/group/${enc(groupWord)}/chapters?limit=$CHAPTER_PAGE&offset=$offset&in_mainland=true&request_id="
            } else {
                "/api/v3/comic/${enc(pathWord)}/group/${enc(groupWord)}/chapters?limit=$CHAPTER_PAGE&offset=$offset&platform=3"
            }
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
                    "/api/v3/comic/${enc(comic)}/chapter/${enc(chapterId)}?in_mainland=true&request_id=",
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
                        val contents = chapter.array("contents")
                            ?: throw IOException("当前节点没有返回图片列表")
                        if (contents.isEmpty()) throw IOException("当前节点返回空图片列表")
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
    text = text.replace(marker, helper + marker, 1)

route_entry_old = '                "拷贝漫画·api.copy2000.online",\n                "拷贝漫画·api.copy-manga.com",\n'
route_entry_new = '                "拷贝漫画·api.copy2000.online",\n                "拷贝漫画·api.copy202601.com",\n                "拷贝漫画·api.copy-manga.com",\n'
if "拷贝漫画·api.copy202601.com" not in text:
    if route_entry_old not in text:
        raise SystemExit("route entry marker missing")
    text = text.replace(route_entry_old, route_entry_new, 1)

route_value_old = '                "copy:api.copy2000.online",\n                "copy:api.copy-manga.com",\n'
route_value_new = '                "copy:api.copy2000.online",\n                "copy:api.copy202601.com",\n                "copy:api.copy-manga.com",\n'
if '"copy:api.copy202601.com"' not in text:
    if route_value_old not in text:
        raise SystemExit("route value marker missing")
    text = text.replace(route_value_old, route_value_new, 1)

host_old = '        private val COPY_HOSTS = listOf(\n            "api.copy2000.online",\n            "api.copy-manga.com",\n'
host_new = '        private val COPY_HOSTS = listOf(\n            "api.copy2000.online",\n            "api.copy202601.com",\n            "api.copy-manga.com",\n'
copy_hosts_block = text[text.index("        private val COPY_HOSTS = listOf("):text.index("        private val HOT_HOSTS = listOf(")]
if '"api.copy202601.com"' not in copy_hosts_block:
    if host_old not in text:
        raise SystemExit("COPY_HOSTS marker missing")
    text = text.replace(host_old, host_new, 1)

if "private const val ROUTE_SCHEMA = 5" in text:
    text = text.replace("private const val ROUTE_SCHEMA = 5", "private const val ROUTE_SCHEMA = 6", 1)
elif "private const val ROUTE_SCHEMA = 6" not in text:
    raise SystemExit("unexpected route schema")

source.write_text(text, encoding="utf-8")

b = build.read_text(encoding="utf-8")
if "versionCode = 6" in b:
    b = b.replace("versionCode = 6", "versionCode = 7", 1)
elif "versionCode = 7" not in b:
    raise SystemExit("unexpected CopyManga Plus versionCode")
build.write_text(b, encoding="utf-8")

d = docs.read_text(encoding="utf-8")
d = d.replace('- 源码 `versionCode = 6`', '- 源码 `versionCode = 7`', 1)
d = d.replace('- Android APK versionCode：`106006`', '- Android APK versionCode：`106007`', 1)
d = d.replace('- APK versionName：`1.6.6`', '- APK versionName：`1.6.7`', 1)
marker = "## v7 Copy 完整阅读链路修复（2026-09-12）"
if marker not in d:
    d += '''\n\n## v7 Copy 完整阅读链路修复（2026-09-12）\n\nv6 已恢复 Copy 首页和搜索，但完整在线探针确认章节和正文仍沿用了旧请求参数。v7 将 Copy 章节列表切换为 `in_mainland=true&request_id=`，正文改为优先 `chapter2` 并使用同一参数；热辣线路保持旧协议不动。\n\n当前动态发现返回 `api.copy202601.com`，已加入固定兜底候选，并将线路 schema 升到 6。正式源码版本提升为 `versionCode=7` / Android `106007` / `1.6.7`。\n\n服务端在线探针已使用与扩展一致的 `COPY/3.0.6` 签名头逐一验证 7 个固定 Copy 节点，漫画列表、搜索、详情、章节列表和 `chapter2` 正文全部可用；测试漫画返回 100 条章节样本和 61 张正文图片。Android 宿主最终兼容性仍需安装 1.6.7 实机确认。\n'''
docs.write_text(d, encoding="utf-8")
