package eu.kanade.tachiyomi.extension.zh.kuaikanmanhua

import android.content.ComponentName
import android.content.Intent
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.mx.AccountSource
import eu.kanade.tachiyomi.source.mx.ChapterContentReplacementSource
import eu.kanade.tachiyomi.source.mx.Comment
import eu.kanade.tachiyomi.source.mx.CommentAuthor
import eu.kanade.tachiyomi.source.mx.CommentCapabilities
import eu.kanade.tachiyomi.source.mx.CommentPage
import eu.kanade.tachiyomi.source.mx.CommentSortOption
import eu.kanade.tachiyomi.source.mx.CommentSource
import eu.kanade.tachiyomi.source.mx.CommentTarget
import eu.kanade.tachiyomi.source.mx.CommentTargetKind
import eu.kanade.tachiyomi.source.mx.SortableCommentSource
import eu.kanade.tachiyomi.source.mx.SourceAccount
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

@Source
abstract class Kuaikanmanhua :
    KeiSource(),
    ConfigurableSource,
    CommentSource,
    SortableCommentSource,
    AccountSource,
    ChapterContentReplacementSource {
    private val apiUrl = "https://api.kkmh.com"

    // API calls intentionally use a no-cookie client. Account cookies are merged into
    // apiHeaders() explicitly because the official login lives on kuaikanmanhua.com while
    // APP endpoints live on api.kkmh.com; OkHttp's normal CookieJar may otherwise replace
    // that cross-domain Cookie header with only api.kkmh.com-scoped cookies.
    private val apiClient by lazy {
        client.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .build()
    }

    private val chapterCommentCursors = ConcurrentHashMap<String, ConcurrentHashMap<Int, Long>>()
    private val mangaCommentCursors = ConcurrentHashMap<String, ConcurrentHashMap<Int, Long>>()
    private val mangaReplyCursors = ConcurrentHashMap<String, ConcurrentHashMap<Int, Long>>()

    override fun Headers.Builder.configureHeaders() = apply {
        set("User-Agent", DESKTOP_USER_AGENT)
        set("Accept", "application/json, text/plain, */*")
        set("Accept-Language", "zh-CN,zh;q=0.9")
        set("Referer", "$baseUrl/")
    }

    override suspend fun getPopularManga(page: Int) = webList(page, 2)
    override suspend fun getLatestUpdates(page: Int) = webList(page, 3)

    private fun webList(page: Int, sort: Int, genre: String = "0", region: String = "1", pays: String = "0", state: String = "0"): MangasPage {
        val root = getNuxt(
            "$baseUrl/tag/$genre?region=$region&pays=$pays&state=$state&sort=$sort&page=$page",
            "分类",
        )
        val list = findArray(root, "dataList")
        val mangas = list.mapNotNull { e -> e as? JsonObject }.mapNotNull(::mangaFromObject)
        return MangasPage(mangas, mangas.size >= 20)
    }

    override fun getFilterList(data: JsonElement?) = FilterList(GenreFilter(), RegionFilter(), PaysFilter(), StatusFilter(), SortFilter())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val root = getJson("$apiUrl/v1/search/topic?q=$q&since=${(page - 1) * 10}&size=10")
            val data = root.obj("data")
            val list = data?.array("hit") ?: JsonArray(emptyList())
            return MangasPage(list.mapNotNull { it as? JsonObject }.mapNotNull(::mangaFromObject), (data?.long("since") ?: -1L) >= 0L)
        }
        return webList(
            page,
            filters.filterIsInstance<SortFilter>().firstOrNull()?.value()?.toIntOrNull() ?: 1,
            filters.filterIsInstance<GenreFilter>().firstOrNull()?.value() ?: "0",
            filters.filterIsInstance<RegionFilter>().firstOrNull()?.value() ?: "1",
            filters.filterIsInstance<PaysFilter>().firstOrNull()?.value() ?: "0",
            filters.filterIsInstance<StatusFilter>().firstOrNull()?.value() ?: "0",
        )
    }

    private fun mangaFromObject(obj: JsonObject): SManga? {
        val id = obj.long("id") ?: obj.long("topic_id") ?: return null
        val name = obj.string("title") ?: return null
        return SManga.create().apply {
            url = "/web/topic/$id"
            title = name
            thumbnail_url = normalizeUrl(obj.string("vertical_image_url") ?: obj.string("cover_image_url"))
            author = obj.obj("user")?.string("nickname")
            description = obj.string("description")
        }
    }

    override fun getMangaUrl(manga: SManga) = if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? = TOPIC_ID.find(url.encodedPath)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { detail(it).first }

    override suspend fun fetchMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)
        val (details, fresh) = detail(topicId(manga))
        return SMangaUpdate(if (fetchDetails) details else manga, if (fetchChapters) fresh else chapters)
    }

    private fun detail(topicId: Long): Pair<SManga, List<SChapter>> {
        val root = getNuxt("$baseUrl/web/topic/$topicId", "详情")
        val pageData = root.array("data")?.firstOrNull() as? JsonObject
            ?: throw IOException("快看漫画详情为空")
        val info = pageData.obj("topicInfo")
            ?: throw IOException("快看漫画详情缺少 topicInfo")

        val manga = SManga.create().apply {
            url = "/web/topic/$topicId"
            title = info.string("title").orEmpty()
            thumbnail_url = normalizeUrl(info.string("vertical_image_url") ?: info.string("cover_image_url"))
            author = info.obj("user")?.string("nickname")
            description = buildString {
                if (isSignedIn()) {
                    append("👤 已检测到快看登录会话；已购章节会按当前账号权限尝试读取。\n\n")
                }
                append(info.string("description").orEmpty())
            }
            status = when (info.string("update_status")) {
                "连载中" -> SManga.ONGOING
                "已完结" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            initialized = true
        }

        // The PC topic payload uses "comics", while the mobile-oriented payload uses
        // "comicList". Keep both because the source WebView now intentionally uses a
        // desktop User-Agent for the PC login flow.
        val chapterItems = pageData.array("comicList")
            ?: pageData.array("comics")
            ?: info.array("comics")
            ?: JsonArray(emptyList())

        val chapters = chapterItems
            .mapNotNull { it as? JsonObject }
            .mapNotNull { item ->
                val id = item.long("id") ?: return@mapNotNull null
                val title = item.string("title").orEmpty().ifBlank { "第 " + id + " 话" }
                val isFree = item.boolish("is_free")
                val hasPay = item.boolish("has_pay") == true ||
                    item.boolish("need_pay") == true ||
                    item.boolish("is_pay") == true ||
                    isFree == false ||
                    (item.long("pay_type") ?: 0L) > 0L ||
                    (item.long("price") ?: 0L) > 0L
                val canView = item.boolish("can_view")
                    ?: item.boolish("is_bought")
                    ?: item.boolish("has_bought")
                    ?: item.boolish("purchased")
                    ?: item.boolish("is_purchased")
                    ?: !hasPay
                val locked = hasPay && !canView

                SChapter.create().apply {
                    url = buildString {
                        append("/web/comic/").append(id)
                        append("?topic_id=").append(topicId)
                        if (hasPay) append("&paid=1")
                        if (locked) append("&locked=1")
                    }
                    name = when {
                        hasPay && canView -> "✅ " + title
                        locked -> "🔒 " + title
                        else -> title
                    }
                    date_upload = normalizeEpoch(item.long("created_at") ?: item.long("updated_at") ?: 0L)
                }
            }
            .reversed()

        return manga to chapters
    }

    override fun getChapterUrl(chapter: SChapter) = if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url.substringBefore('?')

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapterId(chapter) ?: throw IOException("无法解析快看漫画章节 ID")
        if (chapter.url.contains("locked=1") && !isSignedIn()) {
            throw IOException("🔒 该章节为付费内容。若账号已经购买，请先在本源设置中登录快看账号，再刷新章节目录")
        }

        val data = runCatching {
            getJson(
                "$apiUrl/v2/comic/" + id + "?is_preview=" + if (isSignedIn()) "0" else "1",
            ).obj("data")
        }.getOrNull()

        val apiImages = data?.array("images") ?: JsonArray(emptyList())
        val imageUrls = apiImages.mapNotNull { item ->
            val raw = when (item) {
                is JsonObject -> item.string("url") ?: item.string("image_url")
                is JsonPrimitive -> item.contentOrNull
                else -> null
            }
            normalizeUrl(raw)
        }.distinct()

        if (imageUrls.isNotEmpty()) {
            return imageUrls.mapIndexed { index, image ->
                Page(index, url = getChapterUrl(chapter), imageUrl = image)
            }
        }

        val root = getNuxt("$baseUrl/webs/comic-next/" + id, "正文网页")
        val webImages = root.array("data")
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.obj("res")
            ?.obj("data")
            ?.obj("comicInfo")
            ?.array("comicImages")
            ?: JsonArray(emptyList())

        val fallbackUrls = webImages.mapNotNull { item ->
            normalizeUrl((item as? JsonObject)?.string("url"))
        }.distinct()

        if (fallbackUrls.isEmpty()) {
            if (chapter.url.contains("paid=1")) {
                throw IOException("🔒 快看未返回该付费章节正文；请确认当前账号已购买/解锁，并重新登录后刷新章节目录")
            }
            throw IOException("快看漫画正文图片为空")
        }

        return fallbackUrls.mapIndexed { index, image ->
            Page(index, url = getChapterUrl(chapter), imageUrl = image)
        }
    }

    override val commentCapabilities = CommentCapabilities(
        supportsMangaComments = true,
        supportsChapterComments = true,
        canPost = false,
        canReply = false,
        canLike = false,
        requiresLoginToPost = false,
    )
    override suspend fun getMangaCommentTarget(manga: SManga) = CommentTarget(topicId(manga).toString(), getMangaUrl(manga), CommentTargetKind.MANGA)
    override suspend fun getChapterCommentTarget(manga: SManga, chapter: SChapter): CommentTarget {
        val id = COMIC_ID.find(chapter.url)?.groupValues?.getOrNull(1) ?: throw IOException("无法解析快看漫画章节评论 ID")
        return CommentTarget(id, getChapterUrl(chapter), CommentTargetKind.CHAPTER)
    }

    override val commentSortOptions = listOf(
        CommentSortOption(SORT_DEFAULT, "默认"),
        CommentSortOption(SORT_HOT, "最热"),
        CommentSortOption(SORT_LATEST, "最新"),
    )

    override val defaultCommentSortId = SORT_DEFAULT

    override suspend fun getComments(target: CommentTarget, page: Int): CommentPage = getComments(target, page, SORT_DEFAULT)

    override suspend fun getComments(
        target: CommentTarget,
        page: Int,
        sortId: String,
    ): CommentPage {
        runCatching { getAppComments(target, page, sortId) }.getOrNull()?.let { return it }
        return when (sortId) {
            SORT_HOT -> getHotComments(target, page)
            SORT_LATEST -> getLatestComments(target, page)
            else -> getDefaultComments(target, page)
        }
    }

    private fun getAppComments(target: CommentTarget, page: Int, sortId: String): CommentPage? = when (target.kind) {
        CommentTargetKind.MANGA -> getMangaAppComments(
            target.id,
            page.coerceAtLeast(1),
            if (sortId == SORT_LATEST) APP_REVIEW_LATEST_PAGE_ID else APP_REVIEW_HOT_PAGE_ID,
        )
        CommentTargetKind.CHAPTER -> getChapterAppComments(
            target.id,
            page.coerceAtLeast(1),
            sortId,
            if (sortId == SORT_LATEST) "time" else "score",
        )
    }

    private fun getMangaAppComments(topicId: String, page: Int, pageId: Int): CommentPage? {
        val topic = topicId.toLongOrNull() ?: return null
        val key = "$topicId:$pageId"
        val cursors = mangaCommentCursors.getOrPut(key) { ConcurrentHashMap() }
        val cursor = if (page <= 1) {
            cursors.clear()
            cursors[1] = 0L
            0L
        } else {
            cursors[page] ?: ((page - 1L) * COMMENT_PAGE_SIZE)
        }
        val body = """{"feedType":$APP_REVIEW_FEED_TYPE,"since":$cursor,"limit":$COMMENT_PAGE_SIZE,"topicId":$topic,"pageId":$pageId}"""
        val root = postJson("$apiUrl/v1/graph/unified_feed", body)
        if (root.long("code") != 200L) return null
        val data = root.obj("data") ?: return null
        val comments = (data.array("universalModels") ?: JsonArray(emptyList()))
            .mapNotNull { it as? JsonObject }
            .mapNotNull { it.obj("post") }
            .mapNotNull(::appPostToComment)
            .distinctBy(Comment::id)
        if (comments.isEmpty()) return if (page > 1 || data.long("since") == -1L) CommentPage(emptyList(), false, null) else null
        val next = data.long("since")?.takeIf { it >= 0L && it != cursor }
        val hasNext = comments.size >= COMMENT_PAGE_SIZE && next != null
        if (hasNext) cursors[page + 1] = next!! else cursors.keys.filter { it > page }.forEach(cursors::remove)
        return CommentPage(comments, hasNext, null)
    }

    private fun getChapterAppComments(targetId: String, page: Int, sortId: String, order: String): CommentPage? {
        val cursor = chapterCommentCursor(targetId, sortId, page)
        val url = "$apiUrl/v2/comments/cruel/floor_list".toHttpUrl().newBuilder()
            .addQueryParameter("target_type", "comic")
            .addQueryParameter("target_id", targetId)
            .addQueryParameter("order", order)
            .addQueryParameter("offset", cursor.toString())
            .addQueryParameter("limit", COMMENT_PAGE_SIZE.toString())
            .addQueryParameter("total", "false")
            .addQueryParameter("source", "0")
            .build()
            .toString()
        val root = getJson(url)
        if (root.long("code") != 200L) return null
        val data = root.obj("data") ?: return null
        val floors = data.array("comment_floors") ?: JsonArray(emptyList())
        val comments = floors.mapNotNull { it as? JsonObject }.mapNotNull(::appFloorToComment).distinctBy(Comment::id)
        if (comments.isEmpty()) return if (data.long("since") == -1L || page > 1) CommentPage(emptyList(), false, data.long("normal_total")) else null
        return chapterCommentPage(targetId, sortId, root, comments, page, cursor, data.long("normal_total"))
    }

    private fun appPostToComment(post: JsonObject): Comment? {
        val id = post.long("id") ?: return null
        val user = post.obj("user") ?: return null
        val title = post.string("title")?.trim().orEmpty()
        val body = contentText(post.array("content")).ifBlank { post.string("summary")?.trim().orEmpty() }
        val text = listOf(title, body).filter(String::isNotBlank).distinct().joinToString("\n")
        if (text.isBlank()) return null
        return Comment(
            id = id.toString(),
            author = CommentAuthor(
                id = user.long("id")?.toString() ?: user.string("id"),
                name = user.string("nickname") ?: user.string("name") ?: "快看用户",
                avatarUrl = normalizeUrl(user.string("avatar_url") ?: user.string("avatar")),
            ),
            content = text,
            createdAt = normalizeEpoch(post.long("createTime") ?: 0L),
            displayTime = post.string("createTimeStr"),
            likeCount = post.long("likeCount") ?: 0L,
            replyCount = post.long("commentCount") ?: 0L,
            likedByMe = post.boolish("isLiked") == true,
        )
    }

    private fun appFloorToComment(floor: JsonObject): Comment? {
        val root = floor.obj("root") ?: return null
        return appReplyObjectToComment(root, null, floor.long("children_total") ?: 0L)
    }

    private fun appReplyObjectToComment(obj: JsonObject, parentId: String?, replyCount: Long = 0L): Comment? {
        val id = obj.long("id") ?: return null
        val user = obj.obj("user") ?: return null
        val text = contentText(obj.array("contents")).ifBlank {
            contentText(obj.array("content_info")).ifBlank { obj.string("content") ?: obj.string("text") ?: "" }
        }
        if (text.isBlank()) return null
        return Comment(
            id = id.toString(),
            author = CommentAuthor(
                id = user.long("id")?.toString() ?: user.string("id"),
                name = user.string("nickname") ?: user.string("name") ?: "快看用户",
                avatarUrl = normalizeUrl(user.string("avatar_url") ?: user.string("avatar")),
            ),
            content = text,
            createdAt = normalizeEpoch(obj.long("created_at") ?: obj.long("createTime") ?: 0L),
            displayTime = obj.string("created_at_info") ?: obj.string("createTimeStr"),
            likeCount = obj.long("likes_count") ?: obj.long("likeCount") ?: 0L,
            replyCount = replyCount,
            likedByMe = obj.boolish("is_liked") == true || obj.boolish("isLiked") == true,
            parentId = parentId,
        )
    }

    private fun contentText(items: JsonArray?): String {
        val parts = mutableListOf<String>()
        (items ?: JsonArray(emptyList())).forEach { item ->
            when (item) {
                is JsonObject -> {
                    item.string("content")
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?.let(parts::add)
                    item.string("text")
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?.let(parts::add)
                }
                is JsonPrimitive ->
                    item.contentOrNull
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?.let(parts::add)
                else -> Unit
            }
            parts += commentImageUrls(item)
        }
        return parts.filter(String::isNotBlank).distinct().joinToString("\n")
    }

    private fun commentObjectContent(obj: JsonObject): String {
        val parts = mutableListOf<String>()
        val contentObj = obj.obj("content")
        sequenceOf(
            obj.string("content"),
            obj.string("text"),
            contentObj?.string("text"),
            contentObj?.string("content"),
        )
            .filterNotNull()
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach(parts::add)

        obj.array("contents")?.let { contentText(it).takeIf(String::isNotBlank)?.let(parts::add) }
        obj.array("content_info")?.let { contentText(it).takeIf(String::isNotBlank)?.let(parts::add) }
        contentObj?.let { parts += commentImageUrls(it) }
        listOf("image", "images", "image_url", "imageUrl", "pic", "pics", "picture", "pictures")
            .mapNotNull(obj::get)
            .forEach { parts += commentImageUrls(it) }

        return parts.filter(String::isNotBlank).distinct().joinToString("\n")
    }

    private fun commentImageUrls(element: JsonElement): List<String> {
        val urls = mutableListOf<String>()

        fun collect(node: JsonElement, depth: Int) {
            if (depth > 7) return
            when (node) {
                is JsonPrimitive ->
                    node.contentOrNull
                        ?.trim()
                        ?.let(::normalizeUrl)
                        ?.takeIf(::isCommentImageUrl)
                        ?.let(urls::add)
                is JsonObject -> node.values.forEach { collect(it, depth + 1) }
                is JsonArray -> node.forEach { collect(it, depth + 1) }
            }
        }

        collect(element, 0)
        return urls.distinct()
    }

    private fun isCommentImageUrl(url: String): Boolean {
        val normalized = url.lowercase()
        if (!COMMENT_IMAGE_HOST_REGEX.containsMatchIn(normalized)) return false
        return "/comment/image/" in normalized ||
            "/social/" in normalized ||
            "-watermark" in normalized ||
            COMMENT_IMAGE_FILE_REGEX.containsMatchIn(normalized) ||
            normalized.contains(".v3mh.com/")
    }

    private fun getDefaultComments(target: CommentTarget, page: Int): CommentPage {
        val pageNo = page.coerceAtLeast(1)
        val linearOffset = (pageNo - 1) * COMMENT_PAGE_SIZE
        val chapterCursor = if (target.kind == CommentTargetKind.CHAPTER) {
            chapterCommentCursor(target.id, SORT_DEFAULT, pageNo)
        } else {
            0L
        }
        val type = if (target.kind == CommentTargetKind.CHAPTER) "comic" else "topic"
        var chapterWebTotal: Long? = null

        if (target.kind == CommentTargetKind.MANGA) {
            val webComments = runCatching { getMangaWebComments(target.id) }.getOrDefault(emptyList())
            if (webComments.isNotEmpty()) {
                val slice = webComments.drop(linearOffset).take(COMMENT_PAGE_SIZE)
                return CommentPage(
                    comments = slice,
                    hasNextPage = linearOffset + slice.size < webComments.size,
                    totalCount = webComments.size.toLong(),
                )
            }
        }

        // PC comment pages are always requested with a desktop User-Agent. Do not use the
        // mobile H5 comment page here: it commonly redirects users toward the native APP.
        // If the PC reader embeds a sufficiently complete comment payload, use it directly.
        // Otherwise fall back to the JSON comment feed, which does not trigger an APP jump.
        if (target.kind == CommentTargetKind.CHAPTER) {
            val desktopRoot = runCatching {
                getNuxt(
                    "$baseUrl/webs/comic-next/" + target.id,
                    "章节评论 PC 网页",
                    desktopHeaders(),
                )
            }.getOrNull()
            val desktopComments = desktopRoot
                ?.let(::extractComments)
                .orEmpty()
                .filter { it.parentId == null }
            val desktopTotal = desktopRoot?.let(::commentTotal)
                ?: runCatching { getChapterWebCommentCount(target.id) }.getOrNull()
            chapterWebTotal = desktopTotal
            val desktopPayloadLooksComplete = desktopComments.size >= COMMENT_PAGE_SIZE ||
                (desktopTotal != null && desktopTotal <= desktopComments.size)
            if (desktopComments.isNotEmpty() && desktopPayloadLooksComplete) {
                val slice = desktopComments.drop(linearOffset).take(COMMENT_PAGE_SIZE)
                return CommentPage(
                    comments = slice,
                    hasNextPage = linearOffset + slice.size < desktopComments.size,
                    totalCount = desktopTotal ?: desktopComments.size.toLong(),
                )
            }
        }

        // The direct JSON feed is a fallback for a PC page that exposes only a small hot
        // comment subset. It is not a mobile web page and therefore does not cause APP jumps.
        if (target.kind == CommentTargetKind.CHAPTER) {
            val chapterUrls = buildList {
                add("$apiUrl/v1/comics/" + target.id + "/comments/" + chapterCursor + "?order=score")
                if (pageNo == 1) add("$apiUrl/v1/comics/" + target.id + "/hot_comments")
            }
            for (url in chapterUrls) {
                val root = runCatching { getJson(url) }.getOrNull() ?: continue
                val comments = extractComments(root).filter { it.parentId == null }
                if (url.contains("/comments/")) {
                    if (comments.isNotEmpty()) {
                        return chapterCommentPage(
                            target.id,
                            SORT_DEFAULT,
                            root,
                            comments,
                            pageNo,
                            chapterCursor,
                            chapterWebTotal,
                        )
                    }
                    if (findLong(root, setOf("since"))?.let { it < 0L } == true) {
                        return CommentPage(emptyList(), false, null)
                    }
                } else if (comments.isNotEmpty()) {
                    return commentPage(root, comments, pageNo)
                }
            }
        }

        // APP-first routing above is the primary path. These PC pages and historical JSON
        // endpoints remain only as fallbacks when the native feed is unavailable.
        val webCommentPages = when (target.kind) {
            CommentTargetKind.MANGA -> listOf(
                "$baseUrl/web/topic/" + target.id to "漫画评论网页",
            )
            CommentTargetKind.CHAPTER -> listOf(
                "$baseUrl/webs/comic-next/" + target.id to "章节评论阅读页",
                "$baseUrl/web/comic/" + target.id to "章节评论兼容页",
            )
        }
        for ((url, stage) in webCommentPages) {
            val webRoot = runCatching { getNuxt(url, stage, desktopHeaders()) }.getOrNull() ?: continue
            val webComments = extractComments(webRoot)
            if (webComments.isNotEmpty()) {
                val slice = webComments.drop(linearOffset).take(COMMENT_PAGE_SIZE)
                if (slice.isNotEmpty() || pageNo == 1) {
                    return CommentPage(
                        slice,
                        linearOffset + slice.size < webComments.size,
                        webComments.size.toLong(),
                    )
                }
            }
        }

        val urls = mutableListOf<String>()

        urls += "$apiUrl/v2/comments/hot_floor_list?target_type=" + type + "&target_id=" + target.id +
            "&since=" + linearOffset + "&count=" + COMMENT_PAGE_SIZE
        urls += "$apiUrl/v1/comments/floor_list?target_type=" + type + "&target_id=" + target.id +
            "&since=" + linearOffset + "&count=" + COMMENT_PAGE_SIZE

        if (target.kind == CommentTargetKind.MANGA) {
            urls += "$apiUrl/v1/comments/feed/" + target.id + "/order/time?offset=" + linearOffset +
                "&limit=" + COMMENT_PAGE_SIZE
        }

        for (version in 1..4) {
            val builder = "$apiUrl/v" + version + "/comments/cruel/hot_floor_list"
            val url = builder.toHttpUrl().newBuilder()
                .addQueryParameter("target_type", type)
                .addQueryParameter("target_id", target.id)
                .addQueryParameter("offset", linearOffset.toString())
                .addQueryParameter("limit", COMMENT_PAGE_SIZE.toString())
                .apply {
                    if (target.kind == CommentTargetKind.CHAPTER) {
                        addQueryParameter("comic_id", target.id)
                    }
                }
                .build()
                .toString()
            urls += url
        }

        var sawValidResponse = false
        var emptyRoot: JsonObject? = null
        for (url in urls.distinct()) {
            val root = runCatching { getJson(url) }.getOrNull() ?: continue
            sawValidResponse = true
            val comments = extractComments(root)
            if (comments.isNotEmpty()) {
                if (target.kind == CommentTargetKind.CHAPTER &&
                    url.contains("/v1/comics/") &&
                    url.contains("/comments/")
                ) {
                    return chapterCommentPage(
                        target.id,
                        SORT_DEFAULT,
                        root,
                        comments,
                        pageNo,
                        linearOffset.toLong(),
                        chapterWebTotal,
                    )
                }
                return commentPage(root, comments, pageNo)
            }
            if (emptyRoot == null) emptyRoot = root
        }

        if (sawValidResponse) {
            return CommentPage(emptyList(), false, emptyRoot?.let(::commentTotal))
        }

        throw IOException(
            "快看漫画评论暂不可用：target=" + type + "/" + target.id +
                "；APP 评论、网页评论和历史回退接口均未返回可解析数据",
        )
    }

    private fun getHotComments(target: CommentTarget, page: Int): CommentPage {
        val pageNo = page.coerceAtLeast(1)
        val linearOffset = (pageNo - 1) * COMMENT_PAGE_SIZE
        val type = if (target.kind == CommentTargetKind.CHAPTER) "comic" else "topic"

        if (target.kind == CommentTargetKind.MANGA) {
            val webComments = runCatching { getMangaWebComments(target.id) }.getOrDefault(emptyList())
            if (webComments.isNotEmpty()) {
                val slice = webComments.drop(linearOffset).take(COMMENT_PAGE_SIZE)
                return CommentPage(
                    comments = slice,
                    hasNextPage = linearOffset + slice.size < webComments.size,
                    totalCount = webComments.size.toLong(),
                )
            }
        }

        if (target.kind == CommentTargetKind.CHAPTER) {
            val cursor = chapterCommentCursor(target.id, SORT_HOT, pageNo)
            val root = runCatching {
                getJson("$apiUrl/v1/comics/" + target.id + "/comments/" + cursor + "?order=score")
            }.getOrNull()
            if (root != null) {
                val comments = extractComments(root).filter { it.parentId == null }
                if (comments.isNotEmpty()) {
                    return chapterCommentPage(
                        target.id,
                        SORT_HOT,
                        root,
                        comments,
                        pageNo,
                        cursor,
                        runCatching { getChapterWebCommentCount(target.id) }.getOrNull(),
                    )
                }
                if (findLong(root, setOf("since"))?.let { it < 0L } == true) {
                    return CommentPage(emptyList(), false, commentTotal(root))
                }
            }

            if (pageNo == 1) {
                val hotRoot = runCatching {
                    getJson("$apiUrl/v1/comics/" + target.id + "/hot_comments")
                }.getOrNull()
                val hotComments = hotRoot?.let(::extractComments).orEmpty().filter { it.parentId == null }
                if (hotRoot != null && hotComments.isNotEmpty()) {
                    return commentPage(hotRoot, hotComments, pageNo)
                }
            }
        }

        val urls = buildList {
            add(
                "$apiUrl/v2/comments/hot_floor_list?target_type=" + type + "&target_id=" + target.id +
                    "&since=" + linearOffset + "&count=" + COMMENT_PAGE_SIZE,
            )
            for (version in 1..4) {
                add(
                    "$apiUrl/v" + version + "/comments/cruel/hot_floor_list"
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("target_type", type)
                        .addQueryParameter("target_id", target.id)
                        .addQueryParameter("offset", linearOffset.toString())
                        .addQueryParameter("limit", COMMENT_PAGE_SIZE.toString())
                        .apply {
                            if (target.kind == CommentTargetKind.CHAPTER) {
                                addQueryParameter("comic_id", target.id)
                            }
                        }
                        .build()
                        .toString(),
                )
            }
        }

        for (url in urls.distinct()) {
            val root = runCatching { getJson(url) }.getOrNull() ?: continue
            val comments = extractComments(root).filter { it.parentId == null }
            if (comments.isNotEmpty()) return commentPage(root, comments, pageNo)
        }

        throw IOException("快看漫画最热评论暂不可用：target=$type/" + target.id)
    }

    private fun getLatestComments(target: CommentTarget, page: Int): CommentPage {
        val pageNo = page.coerceAtLeast(1)
        val linearOffset = (pageNo - 1) * COMMENT_PAGE_SIZE

        return when (target.kind) {
            CommentTargetKind.MANGA -> {
                val url = "$apiUrl/v1/comments/feed/" + target.id + "/order/time?offset=" +
                    linearOffset + "&limit=" + COMMENT_PAGE_SIZE
                val root = runCatching { getJson(url) }.getOrElse {
                    throw IOException("快看漫画最新评论请求失败：作品 " + target.id, it)
                }
                val comments = extractComments(root).filter { it.parentId == null }
                if (comments.isEmpty() && pageNo == 1) {
                    throw IOException("快看漫画最新评论接口未返回可解析数据：作品 " + target.id)
                }
                commentPage(root, comments, pageNo)
            }

            CommentTargetKind.CHAPTER -> {
                val cursor = chapterCommentCursor(target.id, SORT_LATEST, pageNo)
                val url = "$apiUrl/v1/comics/" + target.id + "/comments/" + cursor + "?order=time"
                val root = runCatching { getJson(url) }.getOrElse {
                    throw IOException("快看漫画章节最新评论请求失败：章节 " + target.id, it)
                }
                val comments = extractComments(root).filter { it.parentId == null }
                if (comments.isEmpty()) {
                    if (findLong(root, setOf("since"))?.let { it < 0L } == true || pageNo > 1) {
                        return CommentPage(emptyList(), false, commentTotal(root))
                    }
                    throw IOException("快看漫画章节最新评论接口未返回可解析数据：章节 " + target.id)
                }
                chapterCommentPage(
                    target.id,
                    SORT_LATEST,
                    root,
                    comments,
                    pageNo,
                    cursor,
                    runCatching { getChapterWebCommentCount(target.id) }.getOrNull(),
                )
            }
        }
    }

    private fun getMangaWebComments(topicId: String): List<Comment> {
        val url = "$baseUrl/web/topic/$topicId/"
        return client.newCall(GET(url, desktopHeaders())).execute().use { response ->
            if (!response.isSuccessful) throw IOException("快看漫画作品评论网页请求失败：HTTP " + response.code)
            val document = org.jsoup.Jsoup.parse(response.body.string(), url)
            document.select("div.TopicComment div.topic-comment div.comment-list div.comment-item, div.topic-comment div.comment-list div.comment-item")
                .mapIndexedNotNull { index, item ->
                    val contentElement = item.selectFirst("div.content")
                    val content = buildList {
                        contentElement?.text()?.trim()?.takeIf(String::isNotBlank)?.let(::add)
                        contentElement?.select("img")?.forEach { image ->
                            sequenceOf(image.attr("src"), image.attr("data-src"), image.attr("data-original"))
                                .mapNotNull(::normalizeUrl)
                                .firstOrNull(::isCommentImageUrl)
                                ?.let(::add)
                        }
                        contentElement?.select("a[href]")
                            ?.mapNotNull { normalizeUrl(it.attr("href")) }
                            ?.filter(::isCommentImageUrl)
                            ?.forEach(::add)
                    }.distinct().joinToString("\n")
                    val author = item.selectFirst("div.user-name")?.text()?.trim().orEmpty()
                    if (content.isBlank() || author.isBlank()) return@mapIndexedNotNull null
                    val date = item.selectFirst("div.date")?.text()?.trim()?.takeIf(String::isNotBlank)
                    val likes = item.selectFirst("span.like-count")?.text()
                        ?.filter(Char::isDigit)
                        ?.toLongOrNull()
                        ?: 0L
                    val avatar = item.selectFirst("img.avatar")?.let { image ->
                        normalizeUrl(
                            image.attr("src").takeIf(String::isNotBlank)
                                ?: image.attr("data-src").takeIf(String::isNotBlank),
                        )
                    }
                    Comment(
                        id = "web-topic:$topicId:$index:" + author.hashCode() + ":" + content.hashCode(),
                        author = CommentAuthor(
                            name = author,
                            avatarUrl = avatar,
                        ),
                        content = content,
                        createdAt = 0L,
                        displayTime = date,
                        likeCount = likes,
                    )
                }
                .distinctBy(Comment::id)
        }
    }

    private fun desktopHeaders(): Headers = headers.newBuilder()
        .set("User-Agent", DESKTOP_USER_AGENT)
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .build()

    override suspend fun getCommentReplies(
        target: CommentTarget,
        comment: Comment,
        page: Int,
    ): CommentPage {
        if (comment.replyCount <= 0L) return CommentPage(emptyList(), false, 0)
        if (target.kind == CommentTargetKind.MANGA && comment.parentId == null && comment.id.toLongOrNull() != null) {
            runCatching { getMangaAppReplies(comment, page.coerceAtLeast(1)) }.getOrNull()?.let { return it }
        }
        val offset = (page.coerceAtLeast(1) - 1) * COMMENT_PAGE_SIZE
        val urls = listOf(
            "$apiUrl/v1/comments/feed/" + comment.id + "/order/time?offset=" + offset + "&limit=" + COMMENT_PAGE_SIZE,
            "$apiUrl/v1/comments/feed/" + comment.id + "/order/score?offset=" + offset + "&limit=" + COMMENT_PAGE_SIZE,
        )
        for (url in urls) {
            val root = runCatching { getJson(url) }.getOrNull() ?: continue
            val replies = extractComments(root)
                .filter { it.id != comment.id }
                .map { if (it.parentId == null) it.copy(parentId = comment.id) else it }
            if (replies.isNotEmpty()) {
                return commentPage(root, replies, page.coerceAtLeast(1))
            }
        }
        return CommentPage(emptyList(), false, comment.replyCount)
    }

    private fun getMangaAppReplies(comment: Comment, page: Int): CommentPage? {
        val key = comment.id
        val cursors = mangaReplyCursors.getOrPut(key) { ConcurrentHashMap() }
        val cursor = if (page <= 1) {
            cursors.clear()
            cursors[1] = 0L
            0L
        } else {
            cursors[page] ?: ((page - 1L) * COMMENT_PAGE_SIZE)
        }
        val url = "$apiUrl/v1/graph/posts/${comment.id}/comments/v5".toHttpUrl().newBuilder()
            .addQueryParameter("since", cursor.toString())
            .addQueryParameter("limit", COMMENT_PAGE_SIZE.toString())
            .build()
            .toString()
        val root = getJson(url)
        if (root.long("code") != 200L) return null
        val data = root.obj("data") ?: return null
        val list = data.array("commentList") ?: JsonArray(emptyList())
        val replies = list.mapNotNull { it as? JsonObject }.mapNotNull { card ->
            val floor = card.obj("postReply") ?: return@mapNotNull null
            val reply = floor.obj("root") ?: return@mapNotNull null
            appReplyObjectToComment(reply, comment.id, floor.long("children_total") ?: 0L)
        }.distinctBy(Comment::id)
        if (replies.isEmpty()) return if (data.long("since") == -1L || page > 1) CommentPage(emptyList(), false, comment.replyCount) else null
        val next = data.long("since")?.takeIf { it >= 0L && it != cursor }
        val hasNext = replies.size >= COMMENT_PAGE_SIZE && next != null
        if (hasNext) cursors[page + 1] = next!! else cursors.keys.filter { it > page }.forEach(cursors::remove)
        return CommentPage(replies, hasNext, comment.replyCount)
    }

    private fun chapterCommentCursor(targetId: String, sortId: String, page: Int): Long {
        val cursors = chapterCommentCursors.getOrPut("$targetId:$sortId") { ConcurrentHashMap() }
        if (page <= 1) {
            cursors.clear()
            cursors[1] = 0L
            return 0L
        }
        return cursors[page] ?: ((page - 1L) * COMMENT_PAGE_SIZE)
    }

    private fun chapterCommentPage(
        targetId: String,
        sortId: String,
        root: JsonObject,
        comments: List<Comment>,
        page: Int,
        requestCursor: Long,
        knownTotal: Long? = null,
    ): CommentPage {
        val since = findLong(root, setOf("since"))
        val nextCursor = since?.takeIf { it >= 0L && it != requestCursor }
        val hasNext = comments.size >= COMMENT_PAGE_SIZE && nextCursor != null
        val cursors = chapterCommentCursors.getOrPut("$targetId:$sortId") { ConcurrentHashMap() }
        if (hasNext) {
            cursors[page + 1] = nextCursor!!
        } else {
            cursors.keys.filter { it > page }.forEach(cursors::remove)
        }
        val total = knownTotal ?: if (!hasNext && comments.isNotEmpty()) {
            ((page - 1L) * COMMENT_PAGE_SIZE) + comments.size
        } else {
            null
        }
        return CommentPage(comments, hasNext, total)
    }

    private fun commentPage(root: JsonObject, comments: List<Comment>, page: Int): CommentPage {
        val total = commentTotal(root)
        val hasMore = findBoolean(root, setOf("has_more", "hasMore", "has_next", "hasNext"))
        val hasNext = hasMore
            ?: total?.let { page.toLong() * COMMENT_PAGE_SIZE < it }
            ?: (comments.size >= COMMENT_PAGE_SIZE)
        return CommentPage(comments, hasNext, total)
    }

    private fun extractComments(root: JsonObject): List<Comment> {
        val focused = mutableListOf<Comment>()

        fun collect(element: JsonElement, depth: Int) {
            if (depth > 8) return
            when (element) {
                is JsonObject -> {
                    val floorRoot = element.obj("root")
                    val floorReplyCount = element.long("children_total")
                    if (floorRoot != null && floorReplyCount != null) {
                        commentFromObject(floorRoot)?.let { rootComment ->
                            focused += rootComment.copy(replyCount = maxOf(rootComment.replyCount, floorReplyCount))
                        }
                    }
                    commentFromObject(element)?.let(focused::add)
                    element.values.forEach { value ->
                        if (value is JsonObject || value is JsonArray) collect(value, depth + 1)
                    }
                }
                is JsonArray -> element.forEach { collect(it, depth + 1) }
                else -> Unit
            }
        }

        fun scan(element: JsonElement, depth: Int) {
            if (depth > 8) return
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    val normalized = key.lowercase()
                    val commentContainer =
                        "comment" in normalized ||
                            "floor" in normalized ||
                            normalized == "replies" ||
                            normalized == "reply_list"
                    if (commentContainer) {
                        collect(value, depth + 1)
                    } else if (value is JsonObject || value is JsonArray) {
                        scan(value, depth + 1)
                    }
                }
                is JsonArray -> element.forEach { scan(it, depth + 1) }
                else -> Unit
            }
        }

        scan(root, 0)
        val focusedResult = focused.distinctBy(Comment::id)
        if (focusedResult.isNotEmpty()) return focusedResult

        val fallback = mutableListOf<Comment>()
        fun walk(element: JsonElement, depth: Int) {
            if (depth > 8) return
            when (element) {
                is JsonObject -> {
                    commentFromObject(element)?.let(fallback::add)
                    element.values.forEach { value ->
                        if (value is JsonObject || value is JsonArray) walk(value, depth + 1)
                    }
                }
                is JsonArray -> element.forEach { walk(it, depth + 1) }
                else -> Unit
            }
        }
        walk(root, 0)
        return fallback.distinctBy(Comment::id)
    }

    private fun commentFromObject(obj: JsonObject): Comment? {
        val id = obj.long("id") ?: obj.long("comment_id") ?: obj.long("floor_id") ?: return null
        val text = commentObjectContent(obj)
        if (text.isBlank()) return null
        val user = obj.obj("user") ?: obj.obj("author") ?: return null
        val created = obj.long("created_at") ?: obj.long("create_time") ?: obj.long("ctime") ?: 0L
        val parentId = sequenceOf(
            obj.string("parent_id")?.takeIf { it != "0" },
            obj.long("parent_id")?.takeIf { it > 0L }?.toString(),
            obj.long("root_id")?.takeIf { it > 0L }?.toString(),
            obj.long("replied_comment_id")?.takeIf { it > 0L }?.toString(),
        ).filterNotNull().firstOrNull()
        return Comment(
            id = id.toString(),
            author = CommentAuthor(
                id = user.string("id") ?: user.long("id")?.toString() ?: user.long("user_id")?.toString(),
                name = user.string("nickname") ?: user.string("name") ?: "快看用户",
                avatarUrl = normalizeUrl(user.string("avatar_url") ?: user.string("avatar")),
                profileUrl = null,
            ),
            content = text,
            createdAt = normalizeEpoch(created),
            likeCount = obj.long("likes_count") ?: obj.long("like_count") ?: 0L,
            replyCount = obj.long("comments_count") ?: obj.long("reply_count") ?: obj.long("replies_count") ?: 0L,
            likedByMe = obj.boolish("is_liked") == true || obj.boolish("liked") == true,
            parentId = parentId,
        )
    }

    private fun commentTotal(root: JsonObject): Long? = root.long("comments_count")
        ?: root.long("comment_count")
        ?: root.long("total_count")
        ?: root.long("comment_total")
        ?: root.long("comments_total")
        ?: root.long("total")
        ?: findLong(
            root,
            setOf(
                "comments_count",
                "comment_count",
                "total_count",
                "comment_total",
                "comments_total",
                "commentCount",
                "commentsCount",
                "commentTotal",
                "commentsTotal",
                "totalComments",
            ),
        )

    private fun getChapterWebCommentCount(comicId: String): Long? {
        val url = "$baseUrl/webs/comic-next/$comicId"
        return client.newCall(GET(url, desktopHeaders())).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val document = org.jsoup.Jsoup.parse(response.body.string(), url)
            val label = document.getElementsMatchingOwnText(Regex("^\\s*评论\\s*$").toPattern()).firstOrNull()
                ?: return@use null
            sequenceOf(label.parent(), label.parent()?.parent(), label.parent()?.parent()?.parent())
                .filterNotNull()
                .mapNotNull { container ->
                    Regex("评论\\s*([0-9][0-9,]*)")
                        .find(container.text())
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.replace(",", "")
                        ?.toLongOrNull()
                }
                .firstOrNull()
        }
    }

    private fun findLong(element: JsonElement, keys: Set<String>, depth: Int = 0): Long? {
        if (depth > 5) return null
        return when (element) {
            is JsonObject -> {
                element.entries.firstNotNullOfOrNull { (key, value) ->
                    if (key in keys) (value as? JsonPrimitive)?.longOrNull else null
                } ?: element.values.firstNotNullOfOrNull { value ->
                    if (value is JsonObject || value is JsonArray) findLong(value, keys, depth + 1) else null
                }
            }
            is JsonArray -> element.firstNotNullOfOrNull { findLong(it, keys, depth + 1) }
            else -> null
        }
    }

    private fun findBoolean(element: JsonElement, keys: Set<String>, depth: Int = 0): Boolean? {
        if (depth > 5) return null
        return when (element) {
            is JsonObject -> {
                element.entries.firstNotNullOfOrNull { (key, value) ->
                    if (key !in keys) {
                        null
                    } else {
                        val primitive = value as? JsonPrimitive
                        primitive?.booleanOrNull ?: primitive?.intOrNull?.let { it != 0 }
                    }
                } ?: element.values.firstNotNullOfOrNull { value ->
                    if (value is JsonObject || value is JsonArray) findBoolean(value, keys, depth + 1) else null
                }
            }
            is JsonArray -> element.firstNotNullOfOrNull { findBoolean(it, keys, depth + 1) }
            else -> null
        }
    }

    override suspend fun getSourceAccount(): SourceAccount? {
        if (!isSignedIn()) return null
        val uid = accountCookie("uid")
        return runCatching {
            val data = getJson("$apiUrl/v1/passport/user").obj("data") ?: return@runCatching SourceAccount(id = uid, name = "快看漫画账号")
            SourceAccount(id = data.long("id")?.toString() ?: uid, name = data.string("nickname").orEmpty().ifBlank { "快看漫画账号" }, avatarUrl = data.string("avatar_url") ?: data.string("avatar"), profileUrl = null)
        }.getOrElse { SourceAccount(id = uid, name = "快看漫画账号") }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = "kuaikan_account_login"
            title = if (isSignedIn()) "快看漫画账号：已登录" else "登录快看漫画账号"
            summary = if (isSignedIn()) "已检测到快看漫画 PC 登录会话；账号权限会用于已购章节。" else "点击打开快看漫画 PC 登录页完成扫码/账号登录；登录后返回并刷新源。"
            setOnPreferenceClickListener {
                screen.context.startActivity(
                    Intent().apply {
                        component = ComponentName(screen.context, "eu.kanade.tachiyomi.ui.webview.WebViewActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("url_key", ACCOUNT_LOGIN_URL)
                        putExtra("source_key", id)
                        putExtra("title_key", "快看漫画账号登录")
                    },
                )
                true
            }
        }.also(screen::addPreference)
    }

    private fun isSignedIn() = accountCookie("passToken") != null || accountCookie("session") != null

    private fun accountCookie(name: String): String? = accountCookies().firstOrNull { it.name == name && it.value.isNotBlank() }?.value

    private fun accountCookies(): List<Cookie> {
        // Prefer the PC login domain first. The desktop login flow stores passToken on
        // .kuaikanmanhua.com; keep mobile/H5 cookies only as compatibility fallbacks.
        val urls = listOf(
            baseUrl.toHttpUrl(),
            apiUrl.toHttpUrl(),
            MOBILE_BASE_URL.toHttpUrl(),
            H5_BASE_URL.toHttpUrl(),
        )
        val cookiesByName = linkedMapOf<String, Cookie>()
        urls.forEach { url ->
            client.cookieJar.loadForRequest(url)
                .filter { it.value.isNotBlank() }
                .forEach { cookie -> cookiesByName.putIfAbsent(cookie.name, cookie) }
        }
        return cookiesByName.values.toList()
    }

    private fun apiHeaders(): Headers {
        val cookie = accountCookies().joinToString("; ") { it.name + "=" + it.value }
        return headers.newBuilder()
            .set("User-Agent", APP_USER_AGENT)
            .set("Accept", "application/json, text/plain, */*")
            .set("X-Device", "0")
            .apply {
                if (cookie.isNotBlank()) set("Cookie", cookie)
            }
            .build()
    }

    private fun topicId(manga: SManga) = TOPIC_ID.find(manga.url)?.groupValues?.getOrNull(1)?.toLongOrNull()
        ?: throw IOException("无法解析快看漫画作品 ID")

    private fun chapterId(chapter: SChapter): Long? = COMIC_ID.find(chapter.url)?.groupValues?.getOrNull(1)?.toLongOrNull()

    private fun getJson(url: String): JsonObject = apiClient.newCall(Request.Builder().url(url).headers(apiHeaders()).get().build()).execute().use {
        if (!it.isSuccessful) throw IOException("快看漫画请求失败：HTTP " + it.code)
        runCatching {
            JSON.parseToJsonElement(it.body.string()).jsonObject
        }.getOrElse { e ->
            throw IOException("快看漫画 JSON 解析失败", e)
        }
    }

    private fun postJson(url: String, body: String): JsonObject = apiClient.newCall(
        Request.Builder().url(url).headers(apiHeaders()).post(body.toRequestBody(JSON_MEDIA_TYPE)).build(),
    ).execute().use {
        if (!it.isSuccessful) throw IOException("快看漫画 APP 请求失败：HTTP " + it.code)
        runCatching { JSON.parseToJsonElement(it.body.string()).jsonObject }
            .getOrElse { e -> throw IOException("快看漫画 APP JSON 解析失败", e) }
    }

    private fun getNuxt(
        url: String,
        stage: String,
        requestHeaders: Headers = headers,
    ): JsonObject = client.newCall(GET(url, requestHeaders)).execute().use { response ->
        if (!response.isSuccessful) throw IOException("快看漫画" + stage + "请求失败：HTTP " + response.code)
        val html = response.body.string()
        val script = org.jsoup.Jsoup.parse(html)
            .selectFirst("script:containsData(__NUXT__)")
            ?.data()
            ?: throw IOException("快看漫画" + stage + "数据结构已变化：未找到 __NUXT__")
        val text = QuickJs.create().use { js ->
            js.evaluate("var window = {};")
            js.evaluate(script)
            js.evaluate("JSON.stringify(window.__NUXT__)") as String
        }
        runCatching { JSON.parseToJsonElement(text).jsonObject }
            .getOrElse { e -> throw IOException("快看漫画" + stage + "数据解析失败", e) }
    }

    private fun normalizeUrl(value: String?): String? = value?.takeIf(String::isNotBlank)?.let {
        when {
            it.startsWith("//") -> "https:" + it
            it.startsWith("http://") -> "https://" + it.removePrefix("http://")
            else -> it
        }
    }

    private fun normalizeEpoch(value: Long): Long = when {
        value <= 0L -> 0L
        value < 100_000_000_000L -> value * 1000L
        else -> value
    }

    private fun findArray(element: JsonElement, key: String): JsonArray {
        if (element is JsonObject) {
            (element[key] as? JsonArray)?.let { return it }
            element.values.forEach {
                val found = findArray(it, key)
                if (found.isNotEmpty()) return found
            }
        } else if (element is JsonArray) {
            element.forEach {
                val found = findArray(it, key)
                if (found.isNotEmpty()) return found
            }
        }
        return JsonArray(emptyList())
    }
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.array(key: String) = this[key] as? JsonArray

    private fun JsonObject.boolish(key: String): Boolean? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.booleanOrNull
            ?: primitive.intOrNull?.let { it != 0 }
            ?: when (primitive.contentOrNull?.lowercase()) {
                "true", "yes", "y" -> true
                "false", "no", "n" -> false
                else -> null
            }
    }

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        val TOPIC_ID = Regex("(?:/web/topic/|/topic/)(\\d+)")
        val COMIC_ID = Regex("/web/comic/(\\d+)")
        const val SORT_DEFAULT = "default"
        const val SORT_HOT = "hot"
        const val SORT_LATEST = "latest"
        const val COMMENT_PAGE_SIZE = 20
        const val APP_REVIEW_FEED_TYPE = 50
        const val APP_REVIEW_HOT_PAGE_ID = 6
        const val APP_REVIEW_LATEST_PAGE_ID = 7
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val ACCOUNT_LOGIN_URL = "https://www.kuaikanmanhua.com/webs/loginh?redirect=https%3A%2F%2Fwww.kuaikanmanhua.com%2F"
        const val MOBILE_BASE_URL = "https://m.kuaikanmanhua.com"
        const val H5_BASE_URL = "https://h5.kuaikanmanhua.com"
        val COMMENT_IMAGE_HOST_REGEX = Regex("^https://[^/]*(?:kkmh|v3mh)\\.com/", RegexOption.IGNORE_CASE)
        val COMMENT_IMAGE_FILE_REGEX = Regex("\\.(?:jpe?g|png|webp|gif|avif)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
        const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        // Kuaikan 8.22.0's bare UA needs additional device/signature context for cruel/floor_list.
        // This legacy official APP UA remains accepted anonymously by the same native API.
        const val APP_USER_AGENT = "Kuaikan/5.75.0/575000(iPhone;Scale/3.00) (iPhone; CPU)"
    }
}
