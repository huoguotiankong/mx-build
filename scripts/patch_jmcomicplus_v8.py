from pathlib import Path

ROOT = Path("source")
KT = ROOT / "src/zh/jmcomicplus/src/eu/kanade/tachiyomi/extension/zh/jmcomicplus/JmComicPlus.kt"
INTERCEPTOR = ROOT / "src/zh/jmcomicplus/src/eu/kanade/tachiyomi/extension/zh/jmcomicplus/ScrambledImageInterceptor.kt"
GRADLE = ROOT / "src/zh/jmcomicplus/build.gradle.kts"
DOC = ROOT / "docs/sources/jmcomicplus.md"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


g = GRADLE.read_text("utf-8")
g = replace_once(g, "versionCode = 7", "versionCode = 8", "versionCode")
GRADLE.write_text(g, "utf-8")

s = KT.read_text("utf-8")
s = replace_once(s, ".addNetworkInterceptor(ScrambledImageInterceptor())", ".addInterceptor(ScrambledImageInterceptor())", "image interceptor")

start = s.index("    override suspend fun getSearchMangaList")
end = s.index("    private fun mangaFromApi", start)
s = s[:start] + '''    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.filterIsInstance<JmSortFilter>().firstOrNull()?.value() ?: "mr"
        val category = filters.filterIsInstance<JmCategoryFilter>().firstOrNull()?.value() ?: "all"
        val rawQuery = query.trim()
        val metadataSearch = rawQuery.startsWith(META_AUTHOR_PREFIX) || rawQuery.startsWith(META_TAG_PREFIX) || rawQuery.startsWith(META_WORK_PREFIX)
        val q = rawQuery
            .removePrefix(META_AUTHOR_PREFIX)
            .removePrefix(META_TAG_PREFIX)
            .removePrefix(META_WORK_PREFIX)
            .trim()
        return dual(
            app = {
                val result = if (q.isNotBlank()) {
                    appList("search?main_tag=0&search_query=${enc(q)}&page=$page&o=${enc(sort)}&t=a", page)
                } else if (category != "all") {
                    appList("categories/filter?page=$page&order=&c=${enc(category)}&o=${enc(sort)}", page)
                } else {
                    appList("search?main_tag=0&search_query=&page=$page&o=${enc(sort)}&t=a", page)
                }
                if (metadataSearch) result.semanticDedupe() else result
            },
            web = {
                val path = if (q.isNotBlank()) {
                    "search/photos?search_query=${enc(q)}&search-type=photos&main_tag=0&o=${enc(sort)}&page=$page"
                } else if (category != "all") {
                    "albums/${enc(category)}?o=${enc(sort)}&page=$page"
                } else {
                    "search/photos?search_query=&search-type=photos&main_tag=0&o=${enc(sort)}&page=$page"
                }
                val result = webList(path, page)
                if (metadataSearch) result.semanticDedupe() else result
            },
        )
    }

    private fun MangasPage.semanticDedupe(): MangasPage {
        val clean = mangas.distinctBy { manga ->
            manga.title.lowercase(Locale.ROOT)
                .replace(Regex("[\\\\s\\\\p{Punct}·・]+"), "")
        }
        return MangasPage(clean, hasNextPage)
    }

    private fun appList(path: String, page: Int): MangasPage {
        val data = apiRequest(path)
        val list = data.asArrayLike("content", "list", "data", "items")
        val mangas = list.mapNotNull { it as? JsonObject }.mapNotNull(::mangaFromApi).distinctBy { it.url }
        return MangasPage(mangas, mangas.size >= API_PAGE_HINT || (page == 1 && mangas.size >= 30))
    }

''' + s[end:]

s = replace_once(
    s,
    '''        val authors = a.stringList("author")
        val tags = a.stringList("tags")
        val category = a.obj("category")?.string("title", "name") ?: a.string("category")
''',
    '''        val apiAuthors = a.stringList("author")
        val apiTags = a.stringList("tags")
        val canonical = runCatching { webMetadata(id) }.getOrNull()
        val authors = canonical?.authors?.takeIf { it.isNotEmpty() } ?: apiAuthors
        val tags = canonical?.tags?.takeIf { it.isNotEmpty() } ?: apiTags
        val category = a.obj("category")?.string("title", "name") ?: a.string("category")
''',
    "app detail metadata",
)

s = replace_once(
    s,
    '''        return DetailBundle(manga, chapters, a, "APP/API")
    }

    private fun webDetail(id: String): DetailBundle {
''',
    '''        val meta = JsonObject(
            a.toMutableMap().apply {
                put("author", JsonArray(authors.map(::JsonPrimitive)))
                put("tags", JsonArray(tags.map(::JsonPrimitive)))
            },
        )
        return DetailBundle(manga, chapters, meta, "APP/API")
    }

    private fun webMetadata(id: String): WebMetadata {
        val html = webRequest("album/$id")
        val doc = parseWebDocument(html, cachedWebBase())
        return webMetadata(doc)
    }

    private fun webMetadata(doc: Document): WebMetadata {
        val authors = doc.select("div.panel-body div.tag-block:eq(3) .btn-primary")
            .ifEmpty { doc.select("a.web-author-tag") }
            .map { it.text().trim() }
            .filter(String::isNotBlank)
            .distinct()
        val tags = doc.select("#intro-block [data-type=tags] a,span[itemprop=genre] a")
            .map { it.text().trim() }
            .filter(String::isNotBlank)
            .filterNot { it in STATUS_LABELS }
            .distinct()
        return WebMetadata(authors, tags)
    }

    private fun webDetail(id: String): DetailBundle {
''',
    "web metadata helper",
)

s = replace_once(
    s,
    '''        val authors = (doc.select("a.web-author-tag").ifEmpty { doc.select("div.panel-body div.tag-block:eq(3) .btn-primary") }).map { it.text().trim() }.filter(String::isNotBlank).distinct()
        val tags = doc.select("#intro-block [data-type=tags] a,[data-type=tags] a,.tag a,span[itemprop=genre] a").map { it.text().trim() }.filter(String::isNotBlank).filterNot { it in setOf("連載中", "连载中", "完結", "完结") }.distinct()
''',
    '''        val metadata = webMetadata(doc)
        val authors = metadata.authors
        val tags = metadata.tags
''',
    "web detail selectors",
)

s = replace_once(
    s,
    '''        val fields = buildList {
            addClickable("作者", authors, MangaDetailActionType.SOURCE_SEARCH)
            addClickable("作品", works, MangaDetailActionType.SOURCE_SEARCH)
            addClickable("登场人物", actors, MangaDetailActionType.SOURCE_SEARCH)
            addClickable("分类", listOfNotNull(category), MangaDetailActionType.SOURCE_GENRE)
            addClickable("标签", tags, MangaDetailActionType.SOURCE_GENRE)
''',
    '''        val fields = buildList {
            addClickable("作者", authors, META_AUTHOR_PREFIX)
            addClickable("作品", works, META_WORK_PREFIX)
            addClickable("登场人物", actors, META_TAG_PREFIX)
            addClickable("分类", listOfNotNull(category), META_TAG_PREFIX)
            addClickable("标签", tags, META_TAG_PREFIX)
''',
    "detail actions",
)

s = replace_once(
    s,
    '''    private fun MutableList<MangaDetailField>.addClickable(label: String, values: List<String>, action: MangaDetailActionType) {
        val clean = values.map(String::trim).filter(String::isNotBlank).distinct()
        if (clean.isEmpty()) return
        add(MangaDetailField(label, clean.map { MangaDetailValue(it, MangaDetailAction(action, it)) }))
    }
''',
    '''    private fun MutableList<MangaDetailField>.addClickable(label: String, values: List<String>, queryPrefix: String) {
        val clean = values.map(String::trim).filter(String::isNotBlank).distinct()
        if (clean.isEmpty()) return
        add(
            MangaDetailField(
                label,
                clean.map { value ->
                    MangaDetailValue(
                        value,
                        MangaDetailAction(MangaDetailActionType.SOURCE_SEARCH, queryPrefix + value),
                    )
                },
            ),
        )
    }
''',
    "clickable fields",
)

s = replace_once(
    s,
    '''        return data.images.distinct().mapIndexed { index, image ->
            val file = image.substringAfterLast('/').substringBefore('?')
            Page(index, getChapterUrl(chapter), image.toHttpUrlWithMarkers(pid, data.scrambleId, file))
        }
''',
    '''        return data.images.distinct().mapIndexed { index, image ->
            Page(index, getChapterUrl(chapter), fixImageUrl(image).substringBefore('?'))
        }
''',
    "page urls",
)
s = replace_once(s, "return PageBundle(images, scrambleId(pid))", "return PageBundle(images)", "app page bundle")
s = replace_once(
    s,
    '''        val scramble = runCatching { scrambleId(pid) }.getOrDefault(DEFAULT_SCRAMBLE_ID)
        return PageBundle(images.distinct(), scramble)
''',
    '''        return PageBundle(images.distinct())
''',
    "web page bundle",
)

s = replace_once(
    s,
    "    override val commentCapabilities = CommentCapabilities(true, true, true, true, false, true)\n",
    '''    override val commentCapabilities: CommentCapabilities
        get() {
            val loggedIn = storedAvs().isNotBlank() || !preferences.getString(PREF_WEB_USER, "").isNullOrBlank()
            return CommentCapabilities(
                supportsMangaComments = true,
                supportsChapterComments = true,
                canPost = loggedIn,
                canReply = loggedIn,
                canLike = false,
                requiresLoginToPost = false,
            )
        }
''',
    "comment capabilities",
)

s = replace_once(
    s,
    '''        val path = buildString {
            append("forum?aid=${enc(scope.albumId)}&mode=all&page=$page")
            scope.chapterId?.let { append("&ncid=${enc(it)}") }
        }
''',
    '''        val commentAid = scope.chapterId ?: scope.albumId
        val mode = if (scope.chapterId == null) "all" else "manhua"
        val path = "forum?aid=${enc(commentAid)}&mode=$mode&page=$page"
''',
    "chapter comment endpoint",
)

s = replace_once(
    s,
    '''            val form = linkedMapOf(
                "aid" to scope.albumId,
                "comment" to text,
                "comment_id" to (parent?.id ?: "0"),
            )
            scope.chapterId?.let { form["ncid"] = it }
''',
    '''            val form = linkedMapOf(
                "aid" to (scope.chapterId ?: scope.albumId),
                "comment" to text,
                "comment_id" to (parent?.id ?: "0"),
            )
''',
    "chapter comment posting",
)

start = s.index("    private fun scrambleId(pid: String): Int {")
end = s.index("    private fun imageShunt()", start)
s = s[:start] + '''    private fun fixImageUrl(url: String): String {
        val secondHttps = url.indexOf("https://", startIndex = 8)
        return if (secondHttps > 0) url.substring(secondHttps) else url
    }

''' + s[end:]

start = s.index("    private fun String.toHttpUrlWithMarkers")
end = s.index("    private fun readStringList", start)
s = s[:start] + s[end:]

s = replace_once(s, "private data class PageBundle(val images: List<String>, val scrambleId: Int)", "private data class PageBundle(val images: List<String>)", "page bundle data class")
s = replace_once(
    s,
    '''    private data class CachedDetail(val time: Long, val value: DetailBundle)
''',
    '''    private data class CachedDetail(val time: Long, val value: DetailBundle)
    private data class WebMetadata(val authors: List<String>, val tags: List<String>)
''',
    "web metadata data class",
)

s = replace_once(
    s,
    '''            }.flatMap { it.split(Regex("[,/|]")) }.map(String::trim).filter(String::isNotBlank).distinct()
            is JsonPrimitive -> return e.contentOrNull.orEmpty().split(Regex("[,/|]")).map(String::trim).filter(String::isNotBlank).distinct()
''',
    '''            }.flatMap { it.split(Regex("[\\\\n\\\\r,，、]+|\\\\s{2,}")) }.map(String::trim).filter(String::isNotBlank).distinct()
            is JsonPrimitive -> return e.contentOrNull.orEmpty().split(Regex("[\\\\n\\\\r,，、]+|\\\\s{2,}")).map(String::trim).filter(String::isNotBlank).distinct()
''',
    "metadata list splitting",
)

s = replace_once(
    s,
    '''    companion object {
        private const val UA_WEB =''',
    '''    companion object {
        private const val META_AUTHOR_PREFIX = "__jm_author__:"
        private const val META_TAG_PREFIX = "__jm_tag__:"
        private const val META_WORK_PREFIX = "__jm_work__:"
        private val STATUS_LABELS = setOf("連載中", "连载中", "完結", "完结", "已完结", "連載")
        private const val UA_WEB =''',
    "metadata constants",
)
KT.write_text(s, "utf-8")

INTERCEPTOR.write_text(r'''package eu.kanade.tachiyomi.extension.zh.jmcomicplus

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

internal class ScrambledImageInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) return response

        val path = response.request.url.encodedPath
        if (!path.contains("/media/photos/")) return response
        val parts = path.split('/').filter(String::isNotBlank)
        val mediaIndex = parts.indexOf("media")
        if (mediaIndex < 0 || parts.getOrNull(mediaIndex + 1) != "photos") return response
        val aid = parts.getOrNull(mediaIndex + 2)?.toIntOrNull() ?: return response
        val filename = parts.getOrNull(mediaIndex + 3).orEmpty()
        if (filename.isBlank()) return response

        val num = segmentation(aid, filename)
        if (num == 0) return response
        val body = response.body
        val sourceBytes = runCatching {
            if (response.header("Content-Encoding").equals("gzip", ignoreCase = true)) {
                GZIPInputStream(body.byteStream()).use { it.readBytes() }
            } else {
                body.bytes()
            }
        }.getOrElse { return response }

        val decoded = runCatching { descramble(sourceBytes, num) }.getOrNull() ?: return response
        return response.newBuilder()
            .removeHeader("Content-Encoding")
            .removeHeader("Content-Length")
            .body(decoded.toResponseBody(JPEG))
            .build()
    }

    private fun segmentation(aid: Int, filename: String): Int {
        if (aid < SCRAMBLE_ID) return 0
        if (aid < SCRAMBLE_268850) return 10
        val modulus = if (aid < SCRAMBLE_421926) 10 else 8
        val imageIndex = filename.substringBefore('.').substringBefore('?')
        val digest = MessageDigest.getInstance("MD5")
            .digest("$aid$imageIndex".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return ((digest.last().code % modulus) * 2) + 2
    }

    private fun descramble(bytes: ByteArray, num: Int): ByteArray {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val dst = Bitmap.createBitmap(src.width, src.height, src.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dst)
        val remainder = src.height % num
        for (i in 0 until num) {
            var sliceHeight = src.height / num
            val srcY = src.height - sliceHeight * (i + 1) - remainder
            var dstY = sliceHeight * i
            if (i == 0) sliceHeight += remainder else dstY += remainder
            if (sliceHeight <= 0 || srcY < 0 || srcY + sliceHeight > src.height) continue
            canvas.drawBitmap(
                src,
                Rect(0, srcY, src.width, srcY + sliceHeight),
                Rect(0, dstY, src.width, dstY + sliceHeight),
                null,
            )
        }
        val output = ByteArrayOutputStream()
        dst.compress(Bitmap.CompressFormat.JPEG, 100, output)
        src.recycle()
        dst.recycle()
        return output.toByteArray()
    }

    companion object {
        private const val SCRAMBLE_ID = 220980
        private const val SCRAMBLE_268850 = 268850
        private const val SCRAMBLE_421926 = 421926
        private val JPEG = "image/jpeg".toMediaType()
    }
}
''', "utf-8")

with DOC.open("a", encoding="utf-8") as f:
    f.write('''\n\n## v8 实机修复候选\n\n- 版本：1.6.8 / Android 106008。\n- 按 v7 实机反馈修复评论页登录设置提示：未登录时改为只读评论能力，不再要求宿主显示“漫画源设置”提示；登录后恢复发评论/回复。\n- 修正章评目标：JM 当前移动端实现使用 `forum?aid=<当前章节/photo id>&mode=manhua`，不再把本子评论 `aid=<album>&mode=all` 加无效 `ncid` 冒充章评。\n- 作者/标签解析收紧：作者优先使用当前网页 `div.panel-body div.tag-block:eq(3) .btn-primary`，标签只取正式标签/genre 区；列表字段不再按 `/` 拆分，避免标签碎片混入作者栏。\n- 作者/标签/作品点击统一走精确元数据搜索入口；此类搜索额外按规范化标题去重，减少同作品重复投稿刷屏。\n- 正文解混淆切换到当前 Keiyoushi 已验证算法：应用层拦截、固定阈值 220980、按章节 ID + 图片序号计算切片，并补齐 gzip 响应解压；同时修复部分图片 URL 被重复拼接的问题。\n- v8 的编译/签名状态与功能实机结果分别记录，不以 CI 通过代替实机验证。\n''')

print("JMComic Plus v8 patch applied")
