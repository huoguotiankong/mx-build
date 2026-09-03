package eu.kanade.tachiyomi.extension.zh.jmcomicplus

import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.mx.AccountSource
import eu.kanade.tachiyomi.source.mx.Comment
import eu.kanade.tachiyomi.source.mx.CommentAuthor
import eu.kanade.tachiyomi.source.mx.CommentCapabilities
import eu.kanade.tachiyomi.source.mx.CommentPage
import eu.kanade.tachiyomi.source.mx.CommentSource
import eu.kanade.tachiyomi.source.mx.CommentTarget
import eu.kanade.tachiyomi.source.mx.CommentTargetKind
import eu.kanade.tachiyomi.source.mx.MangaDetailAction
import eu.kanade.tachiyomi.source.mx.MangaDetailActionType
import eu.kanade.tachiyomi.source.mx.MangaDetailField
import eu.kanade.tachiyomi.source.mx.MangaDetailInfo
import eu.kanade.tachiyomi.source.mx.MangaDetailSource
import eu.kanade.tachiyomi.source.mx.MangaDetailValue
import eu.kanade.tachiyomi.source.mx.SourceAccount
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@Source
abstract class JmComicPlus :
    KeiSource(),
    ConfigurableSource,
    CommentSource,
    AccountSource,
    MangaDetailSource {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val preferences = getPreferences()
    private val replyCache = ConcurrentHashMap<String, List<Comment>>()
    private val detailCache = ConcurrentHashMap<String, CachedDetail>()

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor(ScrambledImageInterceptor)
        retryOnConnectionFailure(true)
    }

    override fun Headers.Builder.configureHeaders() = apply {
        set("User-Agent", WEB_UA)
        set("Accept-Language", "zh-CN,zh;q=0.9")
        set("Accept", "*/*")
    }

    override fun getHomeUrl(): String = webBase()

    override fun getFilterList(data: JsonElement?) = FilterList(
        CategoryFilter(),
        SortFilter(),
        TimeFilter(),
        SearchScopeFilter(),
    )

    override suspend fun getPopularManga(page: Int): MangasPage =
        listWithFallback(page, "", "mv", "a", "0", "all")

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        listWithFallback(page, "", "mr", "a", "0", "all")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.value() ?: "all"
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.value() ?: "mr"
        val time = filters.filterIsInstance<TimeFilter>().firstOrNull()?.value() ?: "a"
        val scope = filters.filterIsInstance<SearchScopeFilter>().firstOrNull()?.value() ?: "0"
        return listWithFallback(page, query.trim(), sort, time, scope, category)
    }

    private fun listWithFallback(
        page: Int,
        query: String,
        sort: String,
        time: String,
        scope: String,
        category: String,
    ): MangasPage {
        var last: Throwable? = null
        for (route in routeOrder()) {
            try {
                val result = when (route) {
                    Route.APP -> appMangaList(page, query, sort, time, scope, category)
                    Route.WEB -> webMangaList(page, query, sort, time, scope, category)
                }
                saveLastRoute(route)
                return result
            } catch (e: Throwable) {
                last = e
            }
        }
        throw IOException("禁漫天堂 Plus 双线路列表请求失败：${last?.message ?: "未知错误"}", last)
    }

    private fun appMangaList(
        page: Int,
        query: String,
        sort: String,
        time: String,
        scope: String,
        category: String,
    ): MangasPage {
        val path = if (query.isBlank() && category != "all") {
            "categories/filter?page=$page&order=&c=${enc(category)}&o=${enc(sort)}"
        } else {
            buildString {
                append("search?main_tag=").append(scope)
                append("&search_query=").append(enc(query))
                append("&page=").append(page)
                append("&o=").append(enc(sort))
                append("&t=").append(enc(time))
            }
        }
        val data = apiRequest(path)
        val items = findArray(data)
        val mangas = items.mapNotNull { (it as? JsonObject)?.let(::mangaFromApi) }
        return MangasPage(mangas, hasNextFromApi(data, page, mangas.size))
    }

    private fun webMangaList(
        page: Int,
        query: String,
        sort: String,
        time: String,
        scope: String,
        category: String,
    ): MangasPage {
        val path = if (query.isBlank() && category != "all") {
            "albums/$category?o=${enc(sort)}&t=${enc(time)}&page=$page"
        } else if (query.isBlank()) {
            "albums?o=${enc(sort)}&t=${enc(time)}&page=$page"
        } else {
            "search/photos?search_query=${enc(query)}&search-type=photos&main_tag=${enc(scope)}&o=${enc(sort)}&t=${enc(time)}&page=$page"
        }
        val response = webRequest(path)
        val base = response.request.url.newBuilder().encodedPath("/").build().toString().trimEnd('/')
        val document = response.use { Jsoup.parse(it.body.string(), base) }
        val seen = hashSetOf<String>()
        val mangas = document.select("div.list-col > div.p-b-15:not([data-group]), a[href*=/album/]")
            .mapNotNull { element ->
                val link = if (element.tagName() == "a") element else element.selectFirst("a[href*=/album/]")
                val href = link?.attr("href").orEmpty()
                val id = albumId(href) ?: return@mapNotNull null
                if (!seen.add(id)) return@mapNotNull null
                val root = if (element.tagName() == "a") element.parent() ?: element else element
                val img = root.selectFirst("img")
                val title = root.selectFirst(".video-title,.title-truncate,.image-item-text")?.text()
                    ?.takeIf(String::isNotBlank)
                    ?: link?.attr("title")?.takeIf(String::isNotBlank)
                    ?: link?.text()?.takeIf(String::isNotBlank)
                    ?: img?.attr("alt").orEmpty()
                if (title.isBlank()) return@mapNotNull null
                SManga.create().apply {
                    url = "/album/$id"
                    this.title = title
                    thumbnail_url = normalizeWebUrl(
                        base,
                        img?.attr("data-original").orEmpty()
                            .ifBlank { img?.attr("data-src").orEmpty() }
                            .ifBlank { img?.attr("src").orEmpty() },
                    )
                    author = root.select(".author a,.tag-block a").map { it.text() }.filter(String::isNotBlank).distinct().joinToString(", ").ifBlank { null }
                    genre = root.select("span[itemprop=genre] a,.label a").map { it.text() }.filter(String::isNotBlank).distinct().joinToString(", ").ifBlank { null }
                    initialized = false
                }
            }
        val hasNext = document.select("a.prevnext[href],a[href*=\"page=${page + 1}\"]").isNotEmpty()
        return MangasPage(mangas, hasNext)
    }

    private fun mangaFromApi(o: JsonObject): SManga? {
        val id = o.string("id") ?: o.string("aid") ?: o.string("album_id") ?: return null
        val tags = o.stringList("tags")
        val authorText = o.stringList("author").ifEmpty { o.stringList("authors") }.joinToString(" / ")
            .ifBlank { o.string("author").orEmpty() }
        return SManga.create().apply {
            url = "/album/$id"
            title = o.string("name") ?: o.string("title") ?: "JM$id"
            author = authorText.takeIf(String::isNotBlank)
            thumbnail_url = coverUrl(o, id)
            genre = tags.joinToString(", ").ifBlank { null }
            description = o.string("description") ?: o.string("intro")
            status = statusFrom(tags)
            initialized = false
        }
    }

    override fun getMangaUrl(manga: SManga): String =
        "${webBase()}/album/${mangaId(manga)}"

    override fun getChapterUrl(chapter: SChapter): String =
        "${webBase()}/photo/${chapterId(chapter)}/"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val id = albumId(url.toString()) ?: return null
        return detail(id).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)
        val fresh = detail(mangaId(manga))
        return SMangaUpdate(fresh.manga, fresh.chapters)
    }

    override val supportsRelatedMangas: Boolean = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> =
        detail(mangaId(manga)).related

    private fun detail(id: String): DetailBundle {
        detailCache[id]?.takeIf { System.currentTimeMillis() - it.at < DETAIL_CACHE_TTL }?.let { return it.value }
        var last: Throwable? = null
        for (route in routeOrder()) {
            try {
                val value = when (route) {
                    Route.APP -> appDetail(id)
                    Route.WEB -> webDetail(id)
                }
                detailCache[id] = CachedDetail(System.currentTimeMillis(), value)
                saveLastRoute(route)
                return value
            } catch (e: Throwable) {
                last = e
            }
        }
        throw IOException("禁漫天堂 Plus 详情双线路失败：${last?.message ?: "未知错误"}", last)
    }

    private fun appDetail(id: String): DetailBundle {
        val raw = apiRequest("album?id=${enc(id)}")
        val album = raw.asObjectDeep() ?: throw IOException("APP/API 详情为空")
        val tags = album.stringList("tags")
        val authors = album.stringList("author").ifEmpty {
            album.string("author")?.let(::listOf).orEmpty()
        }
        val works = album.stringList("works")
        val actors = album.stringList("actors")
        val categories = buildList {
            album.obj("category")?.string("title")?.takeIf(String::isNotBlank)?.let(::add)
            album.obj("category_sub")?.string("title")?.takeIf(String::isNotBlank)?.let(::add)
        }.distinct()
        val title = album.string("name") ?: album.string("title") ?: "JM$id"
        val manga = SManga.create().apply {
            url = "/album/$id"
            this.title = title
            thumbnail_url = coverUrl(album, id)
            author = authors.joinToString(" / ").ifBlank { null }
            genre = (categories + tags).distinct().joinToString(", ").ifBlank { null }
            description = album.string("description").orEmpty()
            status = statusFrom(tags)
            initialized = true
        }
        val series = album.array("series") ?: JsonArray(emptyList())
        val chapters = if (series.isNotEmpty()) {
            series.mapIndexedNotNull { index, item ->
                val obj = item as? JsonObject ?: return@mapIndexedNotNull null
                val pid = obj.string("id") ?: obj.string("photo_id") ?: obj.string("PID") ?: return@mapIndexedNotNull null
                val order = obj.int("sort") ?: obj.int("order") ?: index + 1
                SChapter.create().apply {
                    url = "/photo/$pid?album=$id"
                    name = obj.string("name") ?: obj.string("title") ?: "第 $order 话"
                    chapter_number = order.toFloat()
                    date_upload = parseDate(obj.string("addtime") ?: obj.string("update_at"))
                }
            }.sortedByDescending { it.chapter_number }
        } else {
            listOf(
                SChapter.create().apply {
                    url = "/photo/$id?album=$id"
                    name = "单章节"
                    chapter_number = 1f
                    date_upload = parseDate(album.string("update_at") ?: album.string("addtime"))
                },
            )
        }
        val related = (album.array("related_list") ?: JsonArray(emptyList()))
            .mapNotNull { (it as? JsonObject)?.let(::mangaFromApi) }
        val meta = DetailMeta(
            authors = authors,
            works = works,
            actors = actors,
            categories = categories,
            tags = tags,
            views = album.long("total_views") ?: album.long("views") ?: 0,
            likes = album.long("likes") ?: 0,
            comments = album.long("comment_total") ?: album.long("commentCount") ?: 0,
            favorite = album.bool("is_favorite"),
            route = "APP/API",
            updated = album.string("update_at") ?: album.string("addtime"),
        )
        return DetailBundle(manga, chapters, related, meta)
    }

    private fun webDetail(id: String): DetailBundle {
        val response = webRequest("album/$id")
        val finalBase = response.request.url.newBuilder().encodedPath("/").build().toString().trimEnd('/')
        val html = response.use { it.body.string() }
        val document = resolveDetailDocument(html, finalBase)
        val title = document.selectFirst("h1")?.text().orEmpty().ifBlank { "JM$id" }
        val image = document.selectFirst(".thumb-overlay img,img[itemprop=image]")
        val cover = normalizeWebUrl(
            finalBase,
            image?.attr("data-original").orEmpty()
                .ifBlank { image?.attr("data-src").orEmpty() }
                .ifBlank { image?.attr("src").orEmpty() },
        )
        val authors = document.select("[data-type=author] a,a.web-author-tag").map { it.text() }.filter(String::isNotBlank).distinct()
        val works = document.select("[data-type=works] a").map { it.text() }.filter(String::isNotBlank).distinct()
        val actors = document.select("[data-type=actor] a").map { it.text() }.filter(String::isNotBlank).distinct()
        val tags = document.select("[data-type=tags] a,span[itemprop=genre][data-type=tags] a")
            .map { it.text() }
            .filter(String::isNotBlank)
            .distinct()
        val categories = document.select("[data-type=category] a,.category a").map { it.text() }.filter(String::isNotBlank).distinct()
        val description = document.selectFirst(".intro-collapse-content,#intro-block .p-t-5.p-b-5")
            ?.text()
            ?.substringAfter("敘述：", document.selectFirst(".intro-collapse-content,#intro-block .p-t-5.p-b-5")?.text().orEmpty())
            .orEmpty()
        val manga = SManga.create().apply {
            url = "/album/$id"
            this.title = title
            thumbnail_url = cover
            author = authors.joinToString(" / ").ifBlank { null }
            genre = (categories + tags).distinct().joinToString(", ").ifBlank { null }
            this.description = description
            status = statusFrom(tags + document.select("span[itemprop=genre] a").map { it.text() })
            initialized = true
        }

        val chapterElements = document.select("#episode-block a[href*=/photo/],.btn-toolbar a[href*=/photo/],a.reading[href*=/photo/]")
        val seen = hashSetOf<String>()
        val chapters = chapterElements.mapIndexedNotNull { index, e ->
            val pid = photoId(e.attr("href")) ?: return@mapIndexedNotNull null
            if (!seen.add(pid)) return@mapIndexedNotNull null
            val number = extractChapterNumber(e.text()) ?: (index + 1).toFloat()
            SChapter.create().apply {
                url = "/photo/$pid?album=$id"
                name = e.selectFirst("h3")?.ownText()?.ifBlank { null } ?: e.text().trim().ifBlank { "第 ${index + 1} 话" }
                chapter_number = number
                date_upload = parseDate(e.selectFirst(".hidden-xs,span")?.text())
            }
        }.sortedByDescending { it.chapter_number }.ifEmpty {
            val pid = document.selectFirst("#album_photo_cover a[href*=/photo/],a[href*=/photo/]")?.attr("href")?.let(::photoId) ?: id
            listOf(
                SChapter.create().apply {
                    url = "/photo/$pid?album=$id"
                    name = "单章节"
                    chapter_number = 1f
                },
            )
        }
        val comments = document.selectFirst("#total_video_comments")?.text()?.filter(Char::isDigit)?.toLongOrNull() ?: 0
        val meta = DetailMeta(
            authors = authors,
            works = works,
            actors = actors,
            categories = categories,
            tags = tags,
            comments = comments,
            route = "网页",
        )
        return DetailBundle(manga, chapters, emptyList(), meta)
    }

    override suspend fun getMangaDetailInfo(manga: SManga): MangaDetailInfo {
        val id = mangaId(manga)
        val detail = detail(id)
        val meta = detail.meta
        val fields = buildList {
            if (meta.authors.isNotEmpty()) add(clickableField("作者", meta.authors, "✍️ "))
            if (meta.works.isNotEmpty()) add(clickableField("原作", meta.works, "📖 "))
            if (meta.actors.isNotEmpty()) add(clickableField("登场人物", meta.actors, "👤 "))
            if (meta.categories.isNotEmpty()) add(clickableField("分类", meta.categories, "📚 "))
            if (meta.tags.isNotEmpty()) add(clickableField("标签", meta.tags, "# "))
            add(MangaDetailField("JM ID", listOf(MangaDetailValue(id))))
            add(MangaDetailField("章节", listOf(MangaDetailValue(detail.chapters.size.toString()))))
            add(MangaDetailField("浏览", listOf(MangaDetailValue(meta.views.toString()))))
            add(MangaDetailField("点赞", listOf(MangaDetailValue(meta.likes.toString()))))
            add(MangaDetailField("评论", listOf(MangaDetailValue(meta.comments.toString()))))
            meta.favorite?.let {
                add(MangaDetailField("源站收藏", listOf(MangaDetailValue(if (it) "已收藏" else "未收藏"))))
            }
            add(MangaDetailField("当前线路", listOf(MangaDetailValue(meta.route))))
            meta.updated?.takeIf(String::isNotBlank)?.let {
                add(MangaDetailField("更新时间", listOf(MangaDetailValue(it.take(16)))))
            }
        }
        return MangaDetailInfo(fields = fields, replaceDefaultFields = true)
    }

    private fun clickableField(label: String, values: List<String>, prefix: String): MangaDetailField =
        MangaDetailField(
            label = label,
            values = values.distinct().map { value ->
                MangaDetailValue(
                    text = prefix + value,
                    action = MangaDetailAction(MangaDetailActionType.SOURCE_SEARCH, value),
                )
            },
        )

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pid = chapterId(chapter)
        var last: Throwable? = null
        for (route in routeOrder()) {
            try {
                val urls = when (route) {
                    Route.APP -> appPages(pid)
                    Route.WEB -> webPages(pid)
                }
                if (urls.isNotEmpty()) {
                    saveLastRoute(route)
                    return urls.distinct().mapIndexed { index, image ->
                        Page(index, getChapterUrl(chapter), image)
                    }
                }
            } catch (e: Throwable) {
                last = e
            }
        }
        throw IOException("禁漫天堂 Plus 正文双线路失败：${last?.message ?: "没有返回图片"}", last)
    }

    private fun appPages(pid: String): List<String> {
        val data = apiRequest("chapter?id=${enc(pid)}")
        val obj = data.asObjectDeep() ?: throw IOException("APP/API 章节数据为空")
        val images = obj.array("images") ?: throw IOException("APP/API 章节没有 images")
        val host = imageHost()
        return images.mapNotNull { item ->
            val raw = when (item) {
                is JsonPrimitive -> item.contentOrNull
                is JsonObject -> item.string("image") ?: item.string("name") ?: item.string("filename") ?: item.string("url")
                else -> null
            }?.trim().orEmpty()
            if (raw.isBlank()) return@mapNotNull null
            when {
                raw.startsWith("http://") -> "https://" + raw.removePrefix("http://")
                raw.startsWith("https://") -> raw
                raw.contains("/media/photos/") -> "https://$host/${raw.trimStart('/')}"
                else -> "https://$host/media/photos/$pid/${raw.trimStart('/')}"
            }
        }
    }

    private fun webPages(pid: String): List<String> {
        val shunt = preferences.getString(PREF_SHUNT, "1") ?: "1"
        var nextPath: String? = "photo/$pid/?shunt=${enc(shunt)}"
        val pages = mutableListOf<String>()
        val visited = hashSetOf<String>()
        var guard = 0
        while (!nextPath.isNullOrBlank() && guard++ < 80) {
            val response = if (nextPath.startsWith("http")) absoluteWebRequest(nextPath) else webRequest(nextPath)
            val current = response.request.url.toString()
            if (!visited.add(current)) {
                response.close()
                break
            }
            val base = response.request.url.newBuilder().encodedPath("/").build().toString().trimEnd('/')
            val document = response.use { Jsoup.parse(it.body.string(), base) }
            val images = document.select(
                ".row.thumb-overlay-albums img[data-original],.thumb-overlay-albums img[data-original]," +
                    ".scramble-page img[data-original],.scramble-page img[data-src],.scramble-page img[src]," +
                    "img[data-original*=/media/photos/]",
            )
            images.forEach { img ->
                val raw = img.attr("data-original").ifBlank { img.attr("data-src") }.ifBlank { img.attr("src") }
                val url = normalizeWebUrl(base, raw).substringBefore("?")
                if (url.contains("/media/photos/") && url !in pages) pages += url
            }
            val next = document.select("a.prevnext[href],a[href*=\"page=\"]")
                .firstOrNull { e ->
                    val href = e.attr("href")
                    val abs = normalizeWebUrl(base, href)
                    abs.isNotBlank() && abs !in visited && (
                        e.text().contains("下一", true) ||
                            e.attr("rel").contains("next", true) ||
                            href.contains("page=${guard + 1}")
                        )
                }
            nextPath = next?.attr("abs:href")?.ifBlank { normalizeWebUrl(base, next.attr("href")) }
        }
        if (pages.isEmpty()) throw IOException("网页章节没有找到正文图片")
        return pages
    }

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(requireNotNull(page.imageUrl))
        .headers(
            headers.newBuilder()
                .set("Referer", "${webBase()}/")
                .set("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                .build(),
        )
        .get()
        .build()

    // ---------- MX comments: manga review + chapter review ----------

    override val commentCapabilities = CommentCapabilities(
        supportsMangaComments = true,
        supportsChapterComments = true,
        canPost = true,
        canReply = true,
        canLike = false,
        requiresLoginToPost = true,
    )

    override suspend fun getMangaCommentTarget(manga: SManga): CommentTarget {
        val id = mangaId(manga)
        return CommentTarget(id, "${webBase()}/album/$id", CommentTargetKind.MANGA)
    }

    override suspend fun getChapterCommentTarget(manga: SManga, chapter: SChapter): CommentTarget {
        val id = chapterId(chapter)
        return CommentTarget(id, "${webBase()}/photo/$id/", CommentTargetKind.CHAPTER)
    }

    override suspend fun getComments(target: CommentTarget, page: Int): CommentPage {
        val pageNo = page.coerceAtLeast(1)
        return runCatching { apiComments(target, pageNo) }
            .getOrElse { webComments(target, pageNo) }
    }

    private fun apiComments(target: CommentTarget, page: Int): CommentPage {
        val data = apiRequest("forum?aid=${enc(target.id)}&mode=all&page=$page")
        val obj = data as? JsonObject
        val list = findArray(data)
        val comments = list.mapNotNull { item ->
            (item as? JsonObject)?.let { parseApiComment(target, it, null) }
        }
        val total = obj?.long("total") ?: obj?.long("count") ?: obj?.long("total_count") ?: comments.size.toLong()
        val hasNext = total > page.toLong() * COMMENT_PAGE_SIZE || comments.size >= COMMENT_PAGE_SIZE
        return CommentPage(comments, hasNext, total)
    }

    private fun parseApiComment(target: CommentTarget, o: JsonObject, parentId: String?): Comment? {
        val id = o.string("CID") ?: o.string("id") ?: o.string("cid") ?: o.string("comment_id") ?: return null
        val user = o.obj("user") ?: o.obj("member")
        val uid = o.string("UID") ?: o.string("uid") ?: o.string("user_id") ?: user?.string("id") ?: user?.string("uid")
        val name = o.string("nickname") ?: o.string("username") ?: o.string("name")
            ?: user?.string("nickname") ?: user?.string("username") ?: user?.string("name") ?: "JM用户"
        val avatarRaw = o.string("photo") ?: o.string("avatar") ?: user?.string("photo") ?: user?.string("avatar")
        val contentRaw = o.string("content") ?: o.string("comment") ?: o.string("message").orEmpty()
        val content = Jsoup.parseBodyFragment(contentRaw).text().ifBlank { contentRaw }
        val timeText = o.string("addtime") ?: o.string("created_at") ?: o.string("time")
        val created = parseDate(timeText)
        val repliesRaw = o.array("replys") ?: o.array("replies") ?: o.array("children") ?: JsonArray(emptyList())
        val replies = repliesRaw.mapNotNull { child ->
            (child as? JsonObject)?.let { parseApiComment(target, it, id) }
        }
        replyCache[replyKey(target, id)] = replies
        return Comment(
            id = id,
            author = CommentAuthor(
                id = uid,
                name = name,
                avatarUrl = avatarUrl(avatarRaw),
                profileUrl = uid?.let { "${webBase()}/user/$it" },
            ),
            content = content,
            createdAt = created,
            displayTime = if (created == 0L) timeText else null,
            likeCount = o.long("likes") ?: o.long("like") ?: 0,
            replyCount = o.long("reply_count") ?: o.long("replyCount") ?: replies.size.toLong(),
            likedByMe = false,
            parentId = parentId ?: o.string("parent_CID")?.takeIf { it != "0" },
        )
    }

    override suspend fun getCommentReplies(target: CommentTarget, comment: Comment, page: Int): CommentPage {
        if (page > 1) return CommentPage(emptyList(), false, 0)
        replyCache[replyKey(target, comment.id)]?.let {
            return CommentPage(it, false, it.size.toLong())
        }
        runCatching { apiComments(target, 1) }
        val replies = replyCache[replyKey(target, comment.id)].orEmpty()
        return CommentPage(replies, false, replies.size.toLong())
    }

    private fun webComments(target: CommentTarget, page: Int): CommentPage {
        val response = webRequest(
            "ajax/album_pagination",
            method = "POST",
            body = formBody(
                "video_id" to target.id,
                "page" to page.toString(),
                "series" to "1",
                "with_ad_wcm" to "1",
            ),
        )
        val base = response.request.url.newBuilder().encodedPath("/").build().toString().trimEnd('/')
        val text = response.use { it.body.string() }
        val root = parseJsonObject(text)
        val html = root?.firstHtmlString() ?: text
        val doc = Jsoup.parseBodyFragment(html, base)
        val seen = hashSetOf<String>()
        val comments = doc.select("[data-cid],[id^=comment_],.forum-comment,.comment-list .media")
            .mapNotNull { e ->
                val id = e.attr("data-cid").ifBlank { e.id().filter(Char::isDigit) }
                if (id.isBlank() || !seen.add(id)) return@mapNotNull null
                val name = e.selectFirst(".nickname,.username,.author,.media-heading,strong")?.text().orEmpty().ifBlank { "JM用户" }
                val content = e.selectFirst(".comment-content,.content,.forum-content,.media-body p")?.text()
                    ?.takeIf(String::isNotBlank)
                    ?: e.ownText().trim()
                if (content.isBlank()) return@mapNotNull null
                Comment(
                    id = id,
                    author = CommentAuthor(name = name),
                    content = content,
                    createdAt = 0,
                    displayTime = e.selectFirst(".time,.date,.text-muted")?.text(),
                )
            }
        val hasNext = Regex("""p_album_comments_\d+_${page + 1}""").containsMatchIn(html)
        return CommentPage(comments, hasNext, comments.size.toLong())
    }

    override suspend fun postComment(target: CommentTarget, content: String): Comment =
        postWebComment(target, null, content)

    override suspend fun postCommentReply(target: CommentTarget, parent: Comment, content: String): Comment =
        postWebComment(target, parent, content)

    private fun postWebComment(target: CommentTarget, parent: Comment?, content: String): Comment {
        val text = content.trim()
        if (text.isBlank()) throw IOException("评论内容不能为空")
        ensureWebLogin()
        val body = FormBody.Builder()
            .add("video_id", target.id)
            .add("comment", text)
            .add("originator", "")
            .apply {
                if (parent == null) {
                    add("status", "true")
                } else {
                    add("comment_id", parent.id)
                    add("is_reply", "1")
                    add("forum_subject", "1")
                }
            }
            .build()
        val response = webRequest("ajax/album_comment", "POST", body)
        val raw = response.use { it.body.string() }
        val root = parseJsonObject(raw)
        if (root?.bool("err") == true || root?.int("status") == 0) {
            throw IOException(root.string("msg") ?: root.string("error") ?: "发表评论失败")
        }
        val cid = root?.string("cid") ?: root?.obj("data")?.string("cid") ?: "pending-${System.currentTimeMillis()}"
        return Comment(
            id = cid,
            author = currentAuthor(),
            content = text,
            createdAt = System.currentTimeMillis(),
            parentId = parent?.id,
        )
    }

    // ---------- account ----------

    override suspend fun getSourceAccount(): SourceAccount? {
        val profile = profileObject()
        if (profile != null) return sourceAccountFromProfile(profile)
        if (!hasCredentials() && preferences.getString(PREF_WEB_USER, "").isNullOrBlank()) return null
        if (hasCredentials()) {
            runCatching { performLogin() }
            profileObject()?.let { return sourceAccountFromProfile(it) }
        }
        val webUser = preferences.getString(PREF_WEB_USER, "").orEmpty()
        return webUser.takeIf(String::isNotBlank)?.let { SourceAccount(name = it) }
    }

    private fun sourceAccountFromProfile(profile: JsonObject): SourceAccount {
        val uid = profile.string("uid") ?: profile.string("id") ?: profile.string("UID")
        val name = profile.string("username") ?: profile.string("nickname") ?: profile.string("name")
            ?: preferences.getString(PREF_USERNAME, "").orEmpty().ifBlank { "JM账号" }
        val avatar = avatarUrl(profile.string("photo") ?: profile.string("avatar"))
        return SourceAccount(
            id = uid,
            name = name,
            avatarUrl = avatar,
            profileUrl = uid?.let { "${webBase()}/user/$it" },
        )
    }

    private fun currentAuthor(): CommentAuthor {
        val profile = profileObject()
        if (profile != null) {
            val account = sourceAccountFromProfile(profile)
            return CommentAuthor(account.id, account.name, account.avatarUrl, account.profileUrl)
        }
        return CommentAuthor(name = preferences.getString(PREF_USERNAME, "").orEmpty().ifBlank { "我" })
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_ROUTE
            title = "线路模式"
            entries = arrayOf("自动（APP优先）", "APP/API", "网页")
            entryValues = arrayOf("auto", "app", "web")
            setDefaultValue("auto")
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SHUNT
            title = "网页图片分流"
            entries = arrayOf("分流 1", "分流 2", "分流 3", "分流 4")
            entryValues = arrayOf("1", "2", "3", "4")
            setDefaultValue("1")
            summary = "%s"
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_MANUAL_WEB_BASE
            title = "手动网页域名（可选）"
            summary = "留空使用自动更新；填写完整域名或网址后优先使用"
        }.also(screen::addPreference)

        Preference(screen.context).apply {
            title = "立即刷新 APP/API + Web 域名"
            summary = "重新拉取禁漫动态节点并更新本地缓存"
            setOnPreferenceClickListener {
                client.dispatcher.executorService.execute {
                    val result = runCatching {
                        val api = refreshApiDomains(true)
                        val web = refreshWebDomains(true)
                        "API ${api.firstOrNull() ?: "无"}\nWeb ${web.firstOrNull() ?: "无"}"
                    }
                    Handler(Looper.getMainLooper()).post {
                        result.onSuccess { screen.context.toast("域名刷新完成\n$it") }
                            .onFailure { screen.context.toast("域名刷新失败：${it.message}") }
                    }
                }
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_USERNAME
            title = "禁漫账号"
            summary = "用户名 / 邮箱"
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_PASSWORD
            title = "禁漫密码"
            summary = "仅保存在本机，用于 APP/API 与网页自动登录"
            setOnBindEditTextListener { it.inputType = 0x00000081 }
        }.also(screen::addPreference)

        Preference(screen.context).apply {
            title = "账号登录 / 刷新登录"
            summary = "同时尝试 APP/API AVS 登录与网页 Session 登录"
            setOnPreferenceClickListener {
                client.dispatcher.executorService.execute {
                    val result = runCatching { performLogin() }
                    Handler(Looper.getMainLooper()).post {
                        result.onSuccess { screen.context.toast(it) }
                            .onFailure { screen.context.toast("登录失败：${it.message}") }
                    }
                }
                true
            }
        }.also(screen::addPreference)

        Preference(screen.context).apply {
            title = "网页登录"
            summary = "打开当前可用禁漫网页登录页，适合验证码 / 手工登录"
            setOnPreferenceClickListener {
                val context = screen.context
                val intent = Intent().apply {
                    component = ComponentName(context, "eu.kanade.tachiyomi.ui.webview.WebViewActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("url_key", "${webBase()}/login")
                    putExtra("source_key", id)
                    putExtra("title_key", "禁漫天堂 Plus 网页登录")
                }
                context.startActivity(intent)
                preferences.edit().putString(PREF_WEB_USER, preferences.getString(PREF_USERNAME, "").orEmpty().ifBlank { "网页登录" }).apply()
                true
            }
        }.also(screen::addPreference)

        Preference(screen.context).apply {
            title = "登录状态"
            setOnPreferenceClickListener {
                client.dispatcher.executorService.execute {
                    val account = runCatching { getSourceAccount() }.getOrNull()
                    val msg = buildString {
                        append("账号：").append(account?.name ?: "未登录")
                        account?.id?.let { append("\nUID：").append(it) }
                        append("\nAPP/API：").append(if (avs().isNotBlank()) "已保存 AVS" else "未保存 AVS")
                        append("\nWeb：").append(if (preferences.getString(PREF_WEB_USER, "").orEmpty().isNotBlank()) "已记录登录" else "未记录")
                        append("\n当前 Web：").append(webBase())
                        append("\n当前 API：").append(preferences.getString(PREF_API_HOST, "").orEmpty().ifBlank { "自动" })
                    }
                    Handler(Looper.getMainLooper()).post { screen.context.toast(msg) }
                }
                true
            }
        }.also(screen::addPreference)

        Preference(screen.context).apply {
            title = "清除登录状态"
            summary = "清除 AVS 与账号缓存；不会删除你填写的账号密码"
            setOnPreferenceClickListener {
                preferences.edit()
                    .remove(PREF_AVS)
                    .remove(PREF_PROFILE)
                    .remove(PREF_WEB_USER)
                    .remove(PREF_API_LOGIN_VERSION)
                    .apply()
                screen.context.toast("已清除禁漫登录状态")
                true
            }
        }.also(screen::addPreference)

        Preference(screen.context).apply {
            title = "线路诊断"
            summary = "分别检测 APP/API 与网页线路"
            setOnPreferenceClickListener {
                client.dispatcher.executorService.execute {
                    val lines = mutableListOf<String>()
                    runCatching { apiRequest("setting") }
                        .onSuccess { lines += "APP/API：正常 ${preferences.getString(PREF_API_HOST, "").orEmpty()}" }
                        .onFailure { lines += "APP/API：失败 ${it.message}" }
                    runCatching { webRequest("").use { } }
                        .onSuccess { lines += "网页：正常 ${webBase()}" }
                        .onFailure { lines += "网页：失败 ${it.message}" }
                    Handler(Looper.getMainLooper()).post { screen.context.toast(lines.joinToString("\n")) }
                }
                true
            }
        }.also(screen::addPreference)
    }

    private fun performLogin(): String {
        if (!hasCredentials()) throw IOException("请先填写账号和密码")
        val results = mutableListOf<String>()
        var profile: JsonObject? = null
        runCatching { apiLogin() }
            .onSuccess {
                profile = it
                results += "APP/API：成功"
            }
            .onFailure { results += "APP/API：失败 ${it.message}" }
        runCatching { webLogin() }
            .onSuccess { results += "网页：成功" }
            .onFailure { results += "网页：失败 ${it.message}" }
        if (results.none { it.contains("成功") }) throw IOException(results.joinToString("；"))
        profile?.let { preferences.edit().putString(PREF_PROFILE, it.toString()).apply() }
        return "登录完成\n" + results.joinToString("\n")
    }

    private fun apiLogin(): JsonObject {
        val username = preferences.getString(PREF_USERNAME, "").orEmpty().trim()
        val password = preferences.getString(PREF_PASSWORD, "").orEmpty()
        var last: Throwable? = null
        for (version in APP_VERSIONS) {
            try {
                val data = apiRequest(
                    "login",
                    method = "POST",
                    form = mapOf("username" to username, "password" to password),
                    version = version,
                    noAvs = true,
                )
                val profile = data.asObjectDeep() ?: throw IOException("登录返回为空")
                val s = profile.string("s")
                if (!s.isNullOrBlank()) preferences.edit().putString(PREF_AVS, s).apply()
                preferences.edit()
                    .putString(PREF_PROFILE, profile.toString())
                    .putString(PREF_API_LOGIN_VERSION, version)
                    .apply()
                return profile
            } catch (e: Throwable) {
                last = e
            }
        }
        throw IOException("APP/API 登录失败：${last?.message ?: "未知错误"}", last)
    }

    private fun webLogin() {
        val username = preferences.getString(PREF_USERNAME, "").orEmpty().trim()
        val password = preferences.getString(PREF_PASSWORD, "").orEmpty()
        val response = webRequest(
            "login",
            method = "POST",
            body = formBody(
                "username" to username,
                "password" to password,
                "id_remember" to "on",
                "login_remember" to "on",
                "submit_login" to "",
            ),
        )
        val body = response.use { it.body.string() }
        if (Regex("""帳號.*密碼|账号.*密码|登入失敗|登录失败|incorrect|invalid password|alert-danger""", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            throw IOException("网页端账号或密码错误")
        }
        preferences.edit().putString(PREF_WEB_USER, username).apply()
    }

    private fun ensureWebLogin() {
        if (preferences.getString(PREF_WEB_USER, "").orEmpty().isNotBlank()) return
        if (!hasCredentials()) throw IOException("发表评论需要先登录禁漫账号")
        webLogin()
    }

    private fun hasCredentials(): Boolean =
        preferences.getString(PREF_USERNAME, "").orEmpty().isNotBlank() &&
            preferences.getString(PREF_PASSWORD, "").orEmpty().isNotBlank()

    private fun profileObject(): JsonObject? =
        preferences.getString(PREF_PROFILE, null)?.let(::parseJsonObject)

    // ---------- APP/API transport ----------

    private fun apiRequest(
        path: String,
        method: String = "GET",
        form: Map<String, String> = emptyMap(),
        version: String = APP_VERSION,
        noAvs: Boolean = false,
        allowRefresh: Boolean = true,
    ): JsonElement {
        val domains = apiDomains()
        val preferred = preferences.getString(PREF_API_HOST, "").orEmpty()
        val hosts = (listOf(preferred) + domains).filter(String::isNotBlank).distinct()
        val errors = mutableListOf<String>()
        for (host in hosts) {
            for (tokenSecret in TOKEN_SECRETS) {
                try {
                    val ts = (System.currentTimeMillis() / 1000L).toString()
                    val url = "https://${normalizeHost(host)}/${path.trimStart('/')}"
                    val requestHeaders = apiHeaders(ts, tokenSecret, version, noAvs)
                    val builder = Request.Builder().url(url).headers(requestHeaders)
                    when (method.uppercase()) {
                        "POST" -> builder.post(formBody(*form.entries.map { it.key to it.value }.toTypedArray()))
                        else -> builder.get()
                    }
                    val response = client.newCall(builder.build()).execute()
                    val text = response.use {
                        if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                        it.body.string()
                    }
                    val result = decodeEnvelope(text, ts)
                    preferences.edit()
                        .putString(PREF_API_HOST, normalizeHost(host))
                        .putString(PREF_LAST_ROUTE, Route.APP.key)
                        .apply()
                    return result
                } catch (e: Throwable) {
                    errors += "${normalizeHost(host)}:${e.message}"
                }
            }
        }
        if (allowRefresh) {
            refreshApiDomains(true)
            return apiRequest(path, method, form, version, noAvs, allowRefresh = false)
        }
        throw IOException("APP/API 请求失败：${errors.takeLast(5).joinToString(" | ")}")
    }

    private fun apiHeaders(ts: String, tokenSecret: String, version: String, noAvs: Boolean): Headers =
        Headers.Builder().apply {
            set("User-Agent", APP_UA)
            set("Accept-Encoding", "gzip, deflate")
            set("token", md5(ts + tokenSecret))
            set("tokenparam", "$ts,$version")
            set("Accept", "application/json")
            if (!noAvs && avs().isNotBlank()) set("Cookie", "AVS=${avs()}")
        }.build()

    private fun decodeEnvelope(raw: String, ts: String): JsonElement {
        val root = parseJsonObject(raw) ?: throw IOException("API 返回不是 JSON")
        val code = root.int("code") ?: 200
        if (code != 200) {
            throw IOException("API code=$code ${root.string("errorMsg") ?: root.string("msg").orEmpty()}")
        }
        val data = root["data"] ?: JsonNull
        return when (data) {
            is JsonPrimitive -> {
                val encrypted = data.contentOrNull.orEmpty()
                if (encrypted.isBlank()) JsonNull else {
                    val decoded = aesDecrypt(encrypted, ts, DATA_SECRET)
                    runCatching { json.parseToJsonElement(decoded) }.getOrElse { JsonPrimitive(decoded) }
                }
            }
            else -> data
        }
    }

    private fun apiDomains(): List<String> {
        val cached = preferences.getString(PREF_API_DOMAINS, "").orEmpty().split("|").filter(String::isNotBlank)
        val at = preferences.getLong(PREF_API_DOMAINS_AT, 0L)
        if (cached.isNotEmpty() && System.currentTimeMillis() - at < API_DOMAIN_TTL) return cached
        return refreshApiDomains(false)
    }

    @Synchronized
    private fun refreshApiDomains(force: Boolean): List<String> {
        if (!force) {
            val cached = preferences.getString(PREF_API_DOMAINS, "").orEmpty().split("|").filter(String::isNotBlank)
            val at = preferences.getLong(PREF_API_DOMAINS_AT, 0L)
            if (cached.isNotEmpty() && System.currentTimeMillis() - at < API_DOMAIN_TTL) return cached
        }
        for (server in API_DOMAIN_SERVERS) {
            val list = runCatching {
                val request = Request.Builder().url(server).header("User-Agent", WEB_UA).get().build()
                val raw = client.newCall(request).execute().use {
                    if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                    it.body.string().trim()
                }
                val decrypted = aesDecrypt(raw, "", DOMAIN_SECRET)
                val obj = parseJsonObject(decrypted) ?: throw IOException("域名配置解析失败")
                val element = obj["Server"] ?: obj["server"] ?: obj["data"]
                when (element) {
                    is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    is JsonPrimitive -> element.contentOrNull.orEmpty().split(Regex("""[\s,]+"""))
                    else -> emptyList()
                }.map(::normalizeHost).filter(String::isNotBlank).distinct()
            }.getOrNull()
            if (!list.isNullOrEmpty()) {
                preferences.edit()
                    .putString(PREF_API_DOMAINS, list.joinToString("|"))
                    .putLong(PREF_API_DOMAINS_AT, System.currentTimeMillis())
                    .apply()
                return list
            }
        }
        val old = preferences.getString(PREF_API_DOMAINS, "").orEmpty().split("|").filter(String::isNotBlank)
        return (old + API_FALLBACK).distinct()
    }

    private fun imageHost(): String {
        preferences.getString(PREF_IMAGE_HOST, "").orEmpty().takeIf(String::isNotBlank)?.let { return it }
        val setting = runCatching { apiRequest("setting").asObjectDeep() }.getOrNull()
        val host = setting?.string("img_host")?.let(::normalizeHost)
        if (!host.isNullOrBlank()) {
            preferences.edit().putString(PREF_IMAGE_HOST, host).apply()
            return host
        }
        return IMAGE_FALLBACK.first()
    }

    // ---------- Web transport + automatic domain refresh ----------

    private fun webBase(): String {
        val manual = preferences.getString(PREF_MANUAL_WEB_BASE, "").orEmpty().trim()
        if (manual.isNotBlank()) return normalizeBase(manual)
        val current = preferences.getString(PREF_WEB_BASE, "").orEmpty()
        if (current.isNotBlank()) return normalizeBase(current)
        return webDomains().firstOrNull()?.let(::normalizeBase) ?: BASE_WEB_FALLBACK
    }

    private fun webDomains(): List<String> {
        val cached = preferences.getString(PREF_WEB_DOMAINS, "").orEmpty().split("|").filter(String::isNotBlank)
        val at = preferences.getLong(PREF_WEB_DOMAINS_AT, 0L)
        if (cached.isNotEmpty() && System.currentTimeMillis() - at < WEB_DOMAIN_TTL) return cached
        return refreshWebDomains(false)
    }

    @Synchronized
    private fun refreshWebDomains(force: Boolean): List<String> {
        if (!force) {
            val cached = preferences.getString(PREF_WEB_DOMAINS, "").orEmpty().split("|").filter(String::isNotBlank)
            val at = preferences.getLong(PREF_WEB_DOMAINS_AT, 0L)
            if (cached.isNotEmpty() && System.currentTimeMillis() - at < WEB_DOMAIN_TTL) return cached
        }
        val found = mutableListOf<String>()

        runCatching {
            val req = Request.Builder().url(UPSTREAM_DOMAIN_LIST).header("User-Agent", WEB_UA).get().build()
            val text = client.newCall(req).execute().use {
                if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                it.body.string()
            }
            text.split(Regex("""[\s,|]+"""))
                .map(::normalizeHost)
                .filter(::looksLikeJmHost)
                .forEach(found::add)
        }

        runCatching {
            val req = Request.Builder().url(OFFICIAL_REDIRECT).header("User-Agent", WEB_UA).get().build()
            val final = client.newCall(req).execute().use {
                if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                it.request.url.host
            }
            if (looksLikeJmHost(final)) found += final
        }

        for (pub in PUBLICATION_PAGES) {
            runCatching {
                val req = Request.Builder().url(pub).header("User-Agent", WEB_UA).get().build()
                val html = client.newCall(req).execute().use {
                    if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                    it.body.string()
                }
                Regex("""https?://([A-Za-z0-9.-]+\.[A-Za-z]{2,})(?:[/\"'<>\s]|$)""")
                    .findAll(html)
                    .map { it.groupValues[1] }
                    .filter(::looksLikeJmHost)
                    .forEach(found::add)
            }
        }

        val all = (found + WEB_FALLBACK_HOSTS).map(::normalizeHost).filter(String::isNotBlank).distinct()
        preferences.edit()
            .putString(PREF_WEB_DOMAINS, all.joinToString("|"))
            .putLong(PREF_WEB_DOMAINS_AT, System.currentTimeMillis())
            .apply()
        if (preferences.getString(PREF_WEB_BASE, "").orEmpty().isBlank() && all.isNotEmpty()) {
            preferences.edit().putString(PREF_WEB_BASE, "https://${all.first()}").apply()
        }
        return all
    }

    private fun webRequest(
        path: String,
        method: String = "GET",
        body: RequestBody? = null,
        allowRefresh: Boolean = true,
    ): okhttp3.Response {
        val manual = preferences.getString(PREF_MANUAL_WEB_BASE, "").orEmpty().trim()
        val current = webBase()
        val candidates = if (manual.isNotBlank()) {
            listOf(current)
        } else {
            (listOf(current) + webDomains().map { "https://${normalizeHost(it)}" }).distinct()
        }
        val errors = mutableListOf<String>()
        for (base in candidates) {
            try {
                val url = if (path.startsWith("http")) path else "${base.trimEnd('/')}/${path.trimStart('/')}"
                val builder = Request.Builder().url(url).headers(webHeaders(base))
                when (method.uppercase()) {
                    "POST" -> builder.post(body ?: EMPTY_FORM)
                    else -> builder.get()
                }
                val response = client.newCall(builder.build()).execute()
                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    throw IOException("HTTP $code")
                }
                preferences.edit()
                    .putString(PREF_WEB_BASE, response.request.url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/'))
                    .putString(PREF_LAST_ROUTE, Route.WEB.key)
                    .apply()
                return response
            } catch (e: Throwable) {
                errors += "${normalizeHost(base)}:${e.message}"
            }
        }
        if (allowRefresh && manual.isBlank()) {
            refreshWebDomains(true)
            return webRequest(path, method, body, allowRefresh = false)
        }
        throw IOException("网页线路失败：${errors.takeLast(4).joinToString(" | ")}")
    }

    private fun absoluteWebRequest(url: String): okhttp3.Response {
        val base = runCatching { url.toHttpUrl().newBuilder().encodedPath("/").build().toString().trimEnd('/') }.getOrDefault(webBase())
        val request = Request.Builder().url(url).headers(webHeaders(base)).get().build()
        return client.newCall(request).execute().also {
            if (!it.isSuccessful) {
                val code = it.code
                it.close()
                throw IOException("网页分页 HTTP $code")
            }
        }
    }

    private fun webHeaders(base: String): Headers = Headers.Builder()
        .set("User-Agent", WEB_UA)
        .set("Referer", "${base.trimEnd('/')}/")
        .set("Accept-Language", "zh-CN,zh;q=0.9")
        .set("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
        .build()

    // ---------- helpers ----------

    private fun routeOrder(): List<Route> = when (preferences.getString(PREF_ROUTE, "auto") ?: "auto") {
        "app" -> listOf(Route.APP)
        "web" -> listOf(Route.WEB)
        else -> {
            val last = preferences.getString(PREF_LAST_ROUTE, Route.APP.key) ?: Route.APP.key
            if (last == Route.WEB.key) listOf(Route.WEB, Route.APP) else listOf(Route.APP, Route.WEB)
        }
    }

    private fun saveLastRoute(route: Route) {
        preferences.edit().putString(PREF_LAST_ROUTE, route.key).apply()
    }

    private fun mangaId(manga: SManga): String =
        albumId(manga.url) ?: throw IOException("无法解析禁漫作品 ID")

    private fun chapterId(chapter: SChapter): String =
        photoId(chapter.url) ?: throw IOException("无法解析禁漫章节 ID")

    private fun albumId(value: String): String? =
        Regex("""/album/(\d+)""").find(value)?.groupValues?.getOrNull(1)
            ?: Regex("""[?&](?:id|aid|jm_album)=(\d+)""").find(value)?.groupValues?.getOrNull(1)

    private fun photoId(value: String): String? =
        Regex("""/photo/(\d+)""").find(value)?.groupValues?.getOrNull(1)
            ?: Regex("""[?&](?:pid|photo)=(\d+)""").find(value)?.groupValues?.getOrNull(1)

    private fun coverUrl(o: JsonObject, id: String): String {
        val raw = o.string("image") ?: o.string("pic_s") ?: o.string("cover") ?: o.string("thumb")
        if (!raw.isNullOrBlank()) {
            return when {
                raw.startsWith("https://") -> raw
                raw.startsWith("http://") -> "https://" + raw.removePrefix("http://")
                raw.contains("/media/") -> "https://${imageHost()}/${raw.trimStart('/')}"
                else -> "https://${imageHost()}/media/albums/${raw.trimStart('/')}"
            }
        }
        return "https://${imageHost()}/media/albums/${id}_3x4.jpg"
    }

    private fun avatarUrl(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        return when {
            value.startsWith("https://") -> value
            value.startsWith("http://") -> "https://" + value.removePrefix("http://")
            value.startsWith("media/users/") -> "https://${preferences.getString(PREF_IMAGE_HOST, IMAGE_FALLBACK.first())}/${value.trimStart('/')}"
            else -> "https://${preferences.getString(PREF_IMAGE_HOST, IMAGE_FALLBACK.first())}/media/users/${value.trimStart('/')}"
        }
    }

    private fun statusFrom(tags: List<String>): Int {
        val text = tags.joinToString(" ").lowercase()
        return when {
            "完結" in text || "完结" in text || "completed" in text -> SManga.COMPLETED
            "連載" in text || "连载" in text || "ongoing" in text -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    private fun extractChapterNumber(value: String): Float? =
        Regex("""(?:第\s*)?(\d+(?:\.\d+)?)""").find(value)?.groupValues?.getOrNull(1)?.toFloatOrNull()

    private fun resolveDetailDocument(html: String, base: String): Document {
        val document = Jsoup.parse(html, base)
        val pattern = Regex("""(?:const|let|var)\s+html\s*=\s*base64DecodeUtf8\(["']([^"']+)["']\)""")
        document.select("script").forEach { script ->
            pattern.findAll(script.data()).forEach { match ->
                runCatching {
                    val decoded = String(Base64.decode(match.groupValues[1], Base64.DEFAULT), Charsets.UTF_8)
                    document.body().append(decoded)
                }
            }
        }
        return document
    }

    private fun normalizeWebUrl(base: String, raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""
        return when {
            value.startsWith("https://") -> value
            value.startsWith("http://") -> "https://" + value.removePrefix("http://")
            value.startsWith("//") -> "https:$value"
            else -> "${base.trimEnd('/')}/${value.trimStart('/')}"
        }
    }

    private fun normalizeHost(value: String): String =
        value.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore("/")
            .trimEnd('.')

    private fun normalizeBase(value: String): String {
        val host = normalizeHost(value)
        return if (host.isBlank()) BASE_WEB_FALLBACK else "https://$host"
    }

    private fun looksLikeJmHost(host: String): Boolean {
        val h = normalizeHost(host).lowercase()
        return h.isNotBlank() && (
            h.contains("18comic") ||
                h.contains("jmcomic") ||
                h.contains("jm365") ||
                h.contains("comic-zzz") ||
                h.contains("hcomic")
            )
    }

    private fun hasNextFromApi(data: JsonElement, page: Int, count: Int): Boolean {
        val obj = data.asObjectDeep() ?: return count >= API_PAGE_SIZE
        val total = obj.long("total") ?: obj.long("count")
        val current = obj.int("page") ?: page
        val pages = obj.int("pages") ?: obj.int("page_count")
        return when {
            pages != null -> current < pages
            total != null -> current.toLong() * API_PAGE_SIZE < total
            else -> count >= API_PAGE_SIZE
        }
    }

    private fun findArray(element: JsonElement?): JsonArray {
        if (element == null || element is JsonNull) return JsonArray(emptyList())
        if (element is JsonArray) return element
        val obj = element as? JsonObject ?: return JsonArray(emptyList())
        for (key in listOf("content", "list", "items", "comics", "albums", "data", "docs")) {
            val value = obj[key]
            when (value) {
                is JsonArray -> return value
                is JsonObject -> {
                    val nested = findArray(value)
                    if (nested.isNotEmpty()) return nested
                }
                else -> Unit
            }
        }
        return JsonArray(emptyList())
    }

    private fun JsonElement.asObjectDeep(): JsonObject? {
        if (this is JsonObject) {
            for (key in listOf("data", "album", "comic", "chapter", "result")) {
                val nested = this[key]
                if (nested is JsonObject) return nested
            }
            return this
        }
        return null
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(key: String): Long? {
        val p = this[key] as? JsonPrimitive ?: return null
        return p.longOrNull ?: p.contentOrNull?.replace(",", "")?.toLongOrNull()
    }
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
        ?: (this[key] as? JsonPrimitive)?.contentOrNull?.let {
            when (it.lowercase()) {
                "1", "true", "yes" -> true
                "0", "false", "no" -> false
                else -> null
            }
        }

    private fun JsonObject.firstHtmlString(): String? {
        for (key in listOf("html", "content", "data", "result", "msg", "message")) {
            val value = this[key]
            if (value is JsonPrimitive) {
                val text = value.contentOrNull
                if (!text.isNullOrBlank() && '<' in text) return text
            }
        }
        return null
    }

    private fun JsonObject.stringList(key: String): List<String> {
        val value = this[key] ?: return emptyList()
        return when (value) {
            is JsonArray -> value.mapNotNull {
                when (it) {
                    is JsonPrimitive -> it.contentOrNull
                    is JsonObject -> it.string("name") ?: it.string("title")
                    else -> null
                }
            }.map(String::trim).filter(String::isNotBlank)
            is JsonPrimitive -> value.contentOrNull.orEmpty()
                .split(Regex("""[,，、\n\r]+"""))
                .map(String::trim)
                .filter(String::isNotBlank)
            else -> emptyList()
        }.distinct()
    }

    private fun parseJsonObject(raw: String): JsonObject? =
        runCatching { json.parseToJsonElement(raw.trim()).jsonObject }.getOrNull()

    private fun formBody(vararg pairs: Pair<String, String>): FormBody =
        FormBody.Builder().apply { pairs.forEach { (k, v) -> add(k, v) } }.build()

    private fun enc(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun aesDecrypt(encoded: String, timestamp: String, secret: String): String {
        val key = md5(timestamp + secret).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val bytes = Base64.decode(encoded.trim(), Base64.DEFAULT)
        return String(cipher.doFinal(bytes), Charsets.UTF_8)
    }

    private fun parseDate(value: String?): Long {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return 0L
        text.toLongOrNull()?.let { n ->
            return if (text.length <= 10) n * 1000L else n
        }
        runCatching { java.time.Instant.parse(text).toEpochMilli() }.getOrNull()?.let { return it }
        for (pattern in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")) {
            runCatching {
                val fmt = SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
                fmt.parse(text)?.time
            }.getOrNull()?.let { return it }
        }
        return 0L
    }

    private fun replyKey(target: CommentTarget, commentId: String) =
        "${target.kind}:${target.id}:$commentId"

    private fun avs(): String = preferences.getString(PREF_AVS, "").orEmpty()

    private data class CachedDetail(val at: Long, val value: DetailBundle)
    private data class DetailBundle(
        val manga: SManga,
        val chapters: List<SChapter>,
        val related: List<SManga>,
        val meta: DetailMeta,
    )
    private data class DetailMeta(
        val authors: List<String> = emptyList(),
        val works: List<String> = emptyList(),
        val actors: List<String> = emptyList(),
        val categories: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val views: Long = 0,
        val likes: Long = 0,
        val comments: Long = 0,
        val favorite: Boolean? = null,
        val route: String,
        val updated: String? = null,
    )

    private enum class Route(val key: String) {
        APP("app"),
        WEB("web"),
    }

    companion object {
        private const val PREF_ROUTE = "jmplus_route"
        private const val PREF_LAST_ROUTE = "jmplus_last_route"
        private const val PREF_SHUNT = "jmplus_shunt"
        private const val PREF_MANUAL_WEB_BASE = "jmplus_manual_web_base"
        private const val PREF_WEB_BASE = "jmplus_web_base"
        private const val PREF_WEB_DOMAINS = "jmplus_web_domains"
        private const val PREF_WEB_DOMAINS_AT = "jmplus_web_domains_at"
        private const val PREF_API_DOMAINS = "jmplus_api_domains"
        private const val PREF_API_DOMAINS_AT = "jmplus_api_domains_at"
        private const val PREF_API_HOST = "jmplus_api_host"
        private const val PREF_IMAGE_HOST = "jmplus_image_host"
        private const val PREF_USERNAME = "jmplus_username"
        private const val PREF_PASSWORD = "jmplus_password"
        private const val PREF_AVS = "jmplus_avs"
        private const val PREF_PROFILE = "jmplus_profile"
        private const val PREF_WEB_USER = "jmplus_web_user"
        private const val PREF_API_LOGIN_VERSION = "jmplus_api_login_version"

        private const val APP_VERSION = "2.1.2"
        private val APP_VERSIONS = listOf("2.1.2", "2.0.20")
        private const val DATA_SECRET = "185Hcomic3PAPP7R"
        private val TOKEN_SECRETS = listOf("18comicAPP", "185Hcomic3PAPP7R")
        private const val DOMAIN_SECRET = "diosfjckwpqpdfjkvnqQjsik"

        private val API_DOMAIN_SERVERS = listOf(
            "https://rup4a04-c01.tos-ap-southeast-1.bytepluses.com/newsvr-2025.txt",
            "https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt",
            "https://rup4a04-c03.tos-cn-beijing.bytepluses.com.cn/newsvr-2025.txt",
        )
        private val API_FALLBACK = listOf(
            "www.cdnhjk.net",
            "www.cdngwc.cc",
            "www.cdngwc.net",
            "www.cdngwc.club",
            "www.cdnaspa.vip",
            "www.cdnaspa.club",
            "www.cdnplaystation6.org",
            "www.cdnplaystation6.vip",
            "www.cdnplaystation6.cc",
        )
        private val IMAGE_FALLBACK = listOf(
            "cdn-msp.jmapiproxy1.cc",
            "cdn-msp.jmapiproxy2.cc",
            "cdn-msp2.jmapiproxy2.cc",
            "cdn-msp3.jmapiproxy2.cc",
            "cdn-msp.jmapinodeudzn.net",
            "cdn-msp3.jmapinodeudzn.net",
        )

        private const val UPSTREAM_DOMAIN_LIST = "https://stevenyomi.github.io/source-domains/jmcomic.txt"
        private const val OFFICIAL_REDIRECT = "https://jm365.work/3YeBdF"
        private val PUBLICATION_PAGES = listOf(
            "https://jmcomica.vip",
            "https://jmcomicgo.org",
            "https://jmcomicgo.me",
        )
        private val WEB_FALLBACK_HOSTS = listOf(
            "18comic.vip",
            "18comic.ink",
            "jmcomic-zzz.one",
            "jmcomic-zzz.org",
            "18comic-ive.club",
            "18comic-aspa.org",
            "18comic-wantgo.cc",
        )
        private const val BASE_WEB_FALLBACK = "https://18comic.vip"

        private const val WEB_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        private const val APP_UA =
            "Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.114 Safari/537.36"

        private const val API_PAGE_SIZE = 20
        private const val COMMENT_PAGE_SIZE = 10
        private const val DETAIL_CACHE_TTL = 2 * 60 * 1000L
        private const val API_DOMAIN_TTL = 6 * 60 * 60 * 1000L
        private const val WEB_DOMAIN_TTL = 3 * 60 * 60 * 1000L
        private val EMPTY_FORM = FormBody.Builder().build()
    }
}

private fun android.content.Context.toast(message: String) =
    android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
