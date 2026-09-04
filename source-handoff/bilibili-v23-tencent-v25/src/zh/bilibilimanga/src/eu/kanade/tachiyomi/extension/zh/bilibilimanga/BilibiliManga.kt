package eu.kanade.tachiyomi.extension.zh.bilibilimanga

import android.content.ComponentName
import android.content.Intent
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
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
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Source
abstract class BilibiliManga :
    KeiSource(),
    ConfigurableSource,
    CommentSource,
    SortableCommentSource,
    AccountSource,
    ChapterContentReplacementSource {

    @Volatile
    private var fingerprintCookie: String? = null

    @Volatile
    private var fingerprintFetchedAt: Long = 0L

    @Volatile
    private var wbiMixinKey: String? = null

    @Volatile
    private var wbiMixinFetchedAt: Long = 0L

    private val commentPageOffsets = ConcurrentHashMap<String, ConcurrentHashMap<Int, String>>()

    private val anonymousCommentClient by lazy {
        client.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .build()
    }

    private val preferences by getPreferencesLazy()

    override fun Headers.Builder.configureHeaders() = apply {
        set("User-Agent", WEBVIEW_USER_AGENT)
        set("Accept", "application/json, text/plain, */*")
        set("Accept-Language", "zh-CN,zh;q=0.9")
        set("Origin", baseUrl)
        set("Referer", "$baseUrl/")
        set("Content-Type", "application/json;charset=UTF-8")
    }

    override suspend fun getPopularManga(page: Int): MangasPage = classPage(page, order = 0)

    override suspend fun getLatestUpdates(page: Int): MangasPage = classPage(page, order = 1)

    override val commentCapabilities = CommentCapabilities(
        supportsMangaComments = true,
        supportsChapterComments = true,
        canPost = false,
        canReply = false,
        canLike = false,
        requiresLoginToPost = false,
    )

    override suspend fun getMangaCommentTarget(manga: SManga): CommentTarget {
        val comicId = comicId(manga)
        return CommentTarget(
            id = comicId.toString(),
            url = "$baseUrl/detail/mc$comicId",
            kind = CommentTargetKind.MANGA,
        )
    }

    override suspend fun getChapterCommentTarget(manga: SManga, chapter: SChapter): CommentTarget {
        val match = CHAPTER_ID.find(chapter.url)
            ?: throw IOException("无法解析哔哩哔哩漫画章节评论 ID")
        val comicId = match.groupValues[1]
        val episodeId = match.groupValues[2]
        return CommentTarget(
            id = episodeId,
            url = "$baseUrl/mc$comicId/$episodeId",
            kind = CommentTargetKind.CHAPTER,
        )
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
        val pageNo = page.coerceAtLeast(1)
        val commentType = commentType(target)
        val sortMode = commentSortMode(sortId)
        val offset = resolveCommentOffset(target, pageNo, commentType, sortMode)
            ?: return CommentPage(emptyList(), false, null)

        val root = getWbiCommentPage(target, commentType, offset, sortMode)
        val data = root.obj("data") ?: return CommentPage(emptyList(), false, 0)
        val cursor = data.obj("cursor")
        val normalReplies = data["replies"] as? JsonArray ?: JsonArray(emptyList())
        val topReplies = if (pageNo == 1) {
            data["top_replies"] as? JsonArray ?: JsonArray(emptyList())
        } else {
            JsonArray(emptyList())
        }

        val comments = (topReplies + normalReplies)
            .mapNotNull { it as? JsonObject }
            .mapNotNull(::commentFromReply)
            .distinctBy(Comment::id)

        val nextOffset = cursor?.obj("pagination_reply")?.string("next_offset")
        if (!nextOffset.isNullOrBlank()) {
            commentOffsets(target, sortMode)[pageNo + 1] = nextOffset
        }

        return CommentPage(
            comments = comments,
            hasNextPage = !nextOffset.isNullOrBlank(),
            totalCount = cursor?.long("all_count"),
        )
    }

    override suspend fun getCommentReplies(
        target: CommentTarget,
        comment: Comment,
        page: Int,
    ): CommentPage {
        val pageNo = page.coerceAtLeast(1)
        val commentType = commentType(target)
        val targetId = target.id.toLongOrNull()
            ?: throw IOException("Bilibili 评论参数错误：kind=${target.kind}；oid=${target.id}；type=$commentType")
        val rootId = comment.id.toLongOrNull()
            ?: throw IOException("Bilibili 评论参数错误：root=${comment.id}；type=$commentType；oid=$targetId")
        val url = "$COMMENT_API_BASE/x/v2/reply/reply".toHttpUrl().newBuilder()
            .addQueryParameter("type", commentType.toString())
            .addQueryParameter("oid", targetId.toString())
            .addQueryParameter("root", rootId.toString())
            .addQueryParameter("pn", pageNo.toString())
            .addQueryParameter("ps", COMMENT_PAGE_SIZE.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .headers(anonymousCommentHeaders(target.url ?: "$baseUrl/"))
            .get()
            .build()
        val root = executeAnonymousCommentRequest(
            request = request,
            stage = "回复列表",
            context = "type=$commentType；oid=$targetId；root=$rootId；pn=$pageNo；ps=$COMMENT_PAGE_SIZE；Cookie=无",
        )
        val data = root.obj("data") ?: return CommentPage(emptyList(), false, 0)
        val replies = data["replies"] as? JsonArray ?: JsonArray(emptyList())
        val comments = replies
            .mapNotNull { it as? JsonObject }
            .mapNotNull(::commentFromReply)
        val totalCount = data.obj("page")?.long("count")

        return CommentPage(
            comments = comments,
            hasNextPage = totalCount?.let { pageNo.toLong() * COMMENT_PAGE_SIZE < it } == true,
            totalCount = totalCount,
        )
    }

    private fun commentType(target: CommentTarget): Int = when (target.kind) {
        CommentTargetKind.MANGA -> MANGA_COMMENT_TYPE
        CommentTargetKind.CHAPTER -> CHAPTER_COMMENT_TYPE
    }

    private fun commentOffsets(
        target: CommentTarget,
        sortMode: Int,
    ): ConcurrentHashMap<Int, String> = commentPageOffsets.getOrPut("${target.kind}:${target.id}:$sortMode") {
        ConcurrentHashMap<Int, String>().apply {
            put(1, "")
        }
    }

    private suspend fun resolveCommentOffset(
        target: CommentTarget,
        page: Int,
        commentType: Int,
        sortMode: Int,
    ): String? {
        val offsets = commentOffsets(target, sortMode)
        offsets[page]?.let { return it }

        var currentPage = 1
        while (currentPage < page) {
            val currentOffset = offsets[currentPage] ?: return null
            val root = getWbiCommentPage(target, commentType, currentOffset, sortMode)
            val nextOffset = root.obj("data")
                ?.obj("cursor")
                ?.obj("pagination_reply")
                ?.string("next_offset")
                ?.takeIf(String::isNotBlank)
                ?: return null
            offsets[currentPage + 1] = nextOffset
            currentPage++
        }
        return offsets[page]
    }

    private suspend fun getWbiCommentPage(
        target: CommentTarget,
        commentType: Int,
        offset: String,
        sortMode: Int,
    ): JsonObject {
        val targetId = target.id.toLongOrNull()
            ?: throw IOException("Bilibili 评论参数错误：kind=${target.kind}；oid=${target.id}；type=$commentType")
        val params = linkedMapOf(
            "oid" to targetId.toString(),
            "type" to commentType.toString(),
            "mode" to sortMode.toString(),
            "pagination_str" to """{"offset":${quote(offset)}}""",
            "plat" to "1",
            "seek_rpid" to "",
            "web_location" to COMMENT_WEB_LOCATION,
        )
        val url = "$COMMENT_API_BASE/x/v2/reply/wbi/main?${wbiSignedQuery(params)}"
        val request = Request.Builder()
            .url(url)
            .headers(anonymousCommentHeaders(target.url ?: "$baseUrl/"))
            .get()
            .build()
        val offsetState = if (offset.isBlank()) "首屏" else "分页(${offset.length})"
        return executeAnonymousCommentRequest(
            request = request,
            stage = "WBI评论",
            context = "kind=${target.kind}；type=$commentType；oid=$targetId；mode=$sortMode；offset=$offsetState；Cookie=无",
        )
    }

    private fun commentSortMode(sortId: String): Int = when (sortId) {
        SORT_HOT -> COMMENT_SORT_HOT
        SORT_LATEST -> COMMENT_SORT_LATEST
        else -> COMMENT_SORT_DEFAULT
    }

    private suspend fun wbiSignedQuery(params: Map<String, String>): String = try {
        val mixinKey = getWbiMixinKey()
        val values = params.toMutableMap()
        values["wts"] = (System.currentTimeMillis() / 1000L).toString()
        val query = values.toSortedMap()
            .entries
            .joinToString("&") { (key, value) ->
                "${wbiEncode(key)}=${wbiEncode(value.filterNot { it in WBI_FILTER_CHARS })}"
            }
        val digest = MessageDigest.getInstance("MD5")
            .digest((query + mixinKey).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        "$query&w_rid=$digest"
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException(
            "Bilibili 评论 WBI 签名生成失败：参数=${params.keys.sorted().joinToString(",")}；Cookie=无；原因=${e.message ?: e.javaClass.simpleName}",
            e,
        )
    }

    private suspend fun getWbiMixinKey(): String {
        val now = System.currentTimeMillis()
        wbiMixinKey
            ?.takeIf { it.isNotBlank() && now - wbiMixinFetchedAt < WBI_KEY_TTL }
            ?.let { return it }

        val request = Request.Builder()
            .url("$COMMENT_API_BASE/x/web-interface/nav")
            .headers(anonymousCommentHeaders("https://www.bilibili.com/"))
            .get()
            .build()
        val root = executeAnonymousCommentRequest(
            request = request,
            stage = "WBI密钥",
            context = "endpoint=/x/web-interface/nav；Cookie=无",
            acceptedCodes = setOf(0, -101),
        )
        val wbi = root.obj("data")?.obj("wbi_img")
            ?: throw IOException("Bilibili 评论 WBI 密钥数据为空：阶段=WBI密钥；Cookie=无")
        val imgKey = wbi.string("img_url")
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            .orEmpty()
        val subKey = wbi.string("sub_url")
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            .orEmpty()
        val original = imgKey + subKey
        if (original.length < 64) {
            throw IOException(
                "Bilibili 评论 WBI 密钥无效：imgKey长度=${imgKey.length}；subKey长度=${subKey.length}；Cookie=无",
            )
        }

        val mixin = WBI_MIXIN_KEY_TABLE
            .map { original[it] }
            .joinToString("")
            .take(32)
        wbiMixinKey = mixin
        wbiMixinFetchedAt = now
        return mixin
    }

    private fun wbiEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
        .replace("+", "%20")

    private fun anonymousCommentHeaders(referer: String): Headers = Headers.Builder()
        .set("User-Agent", WEBVIEW_USER_AGENT)
        .set("Accept", "application/json, text/plain, */*")
        .set("Accept-Language", "zh-CN,zh;q=0.9")
        .set("Referer", referer)
        .build()

    private fun executeAnonymousCommentRequest(
        request: Request,
        stage: String,
        context: String,
        acceptedCodes: Set<Int> = setOf(0),
    ): JsonObject {
        val response = try {
            anonymousCommentClient.newCall(request).execute()
        } catch (e: IOException) {
            throw IOException(
                "Bilibili 评论网络请求失败：阶段=$stage；$context；原因=${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }

        return response.use {
            val requestState = "阶段=$stage；HTTP=${it.code}；$context"
            if (!it.isSuccessful) {
                throw IOException("Bilibili 评论 HTTP 失败：$requestState")
            }

            val body = try {
                it.body.string()
            } catch (e: IOException) {
                throw IOException("Bilibili 评论响应读取失败：$requestState；原因=${e.message ?: e.javaClass.simpleName}", e)
            }

            val root = try {
                JSON.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                val contentType = it.header("Content-Type").orEmpty().ifBlank { "未知" }
                throw IOException(
                    "Bilibili 评论 JSON 解析失败：$requestState；Content-Type=$contentType；响应长度=${body.length}",
                    e,
                )
            }

            val code = root.int("code")
                ?: throw IOException("Bilibili 评论响应结构异常：$requestState；缺少 code")
            if (code !in acceptedCodes) {
                val message = root.string("message") ?: root.string("msg") ?: "未知错误"
                throw IOException("Bilibili 评论 API 返回错误：$requestState；code=$code；message=$message")
            }
            root
        }
    }

    override suspend fun getSourceAccount(): SourceAccount? {
        if (!isSignedIn()) return null

        val fallbackId = accountCookieValue("DedeUserID")
        val fallback = SourceAccount(
            id = fallbackId,
            name = "Bilibili 账号",
            profileUrl = fallbackId?.let { "https://space.bilibili.com/$it" },
        )

        return runCatching {
            val request = Request.Builder()
                .url("$COMMENT_API_BASE/x/web-interface/nav")
                .headers(
                    commentHeaders(0L).newBuilder()
                        .set("Referer", "https://www.bilibili.com/")
                        .removeAll("Origin")
                        .build(),
                )
                .get()
                .build()
            val root = executeCommentRequest(request)
            val data = root.obj("data") ?: return@runCatching fallback
            if (data.bool("isLogin") != true) return null
            val mid = data.long("mid")?.toString() ?: fallbackId
            SourceAccount(
                id = mid,
                name = data.string("uname").orEmpty().ifBlank { "Bilibili 账号" },
                avatarUrl = data.string("face")?.let(::normalizeBilibiliUrl),
                profileUrl = mid?.let { "https://space.bilibili.com/$it" },
            )
        }.getOrElse { fallback }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = "bilibili_account_login"
            title = if (isSignedIn()) "Bilibili 账号：已登录" else "登录 Bilibili 账号（PC 网页）"
            summary = if (isSignedIn()) {
                loginSessionSummary()
            } else {
                "点击打开 Bilibili PC 官方登录页面；支持扫码、密码、短信等官方登录方式。"
            }
            setOnPreferenceClickListener {
                val context = screen.context
                val intent = Intent().apply {
                    component = ComponentName(context, "eu.kanade.tachiyomi.ui.webview.WebViewActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("url_key", ACCOUNT_LOGIN_URL)
                    putExtra("source_key", id)
                    putExtra("title_key", "Bilibili 账号登录（PC）")
                }
                context.startActivity(intent)
                true
            }
        }.also(screen::addPreference)
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        StatusFilter(),
        AreaFilter(),
        StyleFilter(),
        OrderFilter(),
    )

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val root = postApi(
                "search.v1.Search/SearchKeyword",
                """{"keyword":${quote(query.trim())},"pageNum":$page,"pageSize":20}""",
                "$baseUrl/search",
                profile = ApiProfile.NONE,
            )
            return searchMangaPage(root, page)
        }

        val status = filters.filterIsInstance<StatusFilter>().firstOrNull()?.value() ?: -1
        val area = filters.filterIsInstance<AreaFilter>().firstOrNull()?.value() ?: -1
        val style = filters.filterIsInstance<StyleFilter>().firstOrNull()?.value() ?: -1
        val order = filters.filterIsInstance<OrderFilter>().firstOrNull()?.value() ?: 0
        return classPage(page, style, area, status, order)
    }

    private suspend fun classPage(
        page: Int,
        style: Int = -1,
        area: Int = -1,
        finish: Int = -1,
        order: Int = 0,
    ): MangasPage {
        val size = 18
        val body = """{"style_id":$style,"area_id":$area,"is_finish":$finish,"order":$order,"is_free":-1,"page_num":$page,"page_size":$size}"""
        return mangaPage(postApi("comic.v1.Comic/ClassPage", body, profile = ApiProfile.ANDROID), size)
    }

    private fun mangaPage(root: JsonObject, pageSize: Int): MangasPage {
        val data = root["data"]
        val list = when (data) {
            is JsonArray -> data
            is JsonObject -> (data["list"] as? JsonArray)
                ?: (data["comics"] as? JsonArray)
                ?: (data["data"] as? JsonArray)
                ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        val mangas = list.mapNotNull { it as? JsonObject }.mapNotNull(::mangaFromObject)
        return MangasPage(mangas, mangas.size >= pageSize)
    }

    private fun searchMangaPage(root: JsonObject, page: Int): MangasPage {
        val comicData = root.obj("data")?.obj("comic_data") ?: return MangasPage(emptyList(), false)
        val list = comicData["list"] as? JsonArray ?: JsonArray(emptyList())
        val mangas = list.mapNotNull { it as? JsonObject }.mapNotNull(::mangaFromObject)
        val totalPage = comicData.int("total_page") ?: page
        return MangasPage(mangas, page < totalPage)
    }

    private fun mangaFromObject(obj: JsonObject): SManga? {
        val id = obj.long("comic_id") ?: obj.long("season_id") ?: obj.long("id") ?: return null
        val title = obj.string("org_title") ?: obj.string("title") ?: return null
        return SManga.create().apply {
            url = "/detail/mc$id"
            this.title = title
            thumbnail_url = optimizedCover(obj.string("vertical_cover") ?: obj.string("vcover"))
            author = obj.stringList("author_name").ifEmpty { obj.stringList("author") }.joinToString(", ").takeIf(String::isNotBlank)
            genre = obj.stringList("styles").joinToString(", ").takeIf(String::isNotBlank)
            description = obj.string("introduction") ?: obj.string("evaluate")
            status = when (obj.int("is_finish")) {
                0 -> SManga.ONGOING
                1 -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun getMangaUrl(manga: SManga): String = if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val id = COMIC_ID.find(url.encodedPath)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return null
        return detail(id).first
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)
        val (details, freshChapters) = detail(comicId(manga))
        return SMangaUpdate(if (fetchDetails) details else manga, if (fetchChapters) freshChapters else chapters)
    }

    private suspend fun detail(comicId: Long): Pair<SManga, List<SChapter>> {
        val signedIn = isSignedIn()
        val root = postApi(
            "comic.v1.Comic/ComicDetail",
            """{"comicId":$comicId}""",
            "$baseUrl/detail/mc$comicId",
            profile = ApiProfile.ANDROID,
        )
        val data = root.obj("data") ?: throw IOException("哔哩哔哩漫画详情为空")
        val manga = mangaFromObject(data)?.apply {
            url = "/detail/mc$comicId"
            if (signedIn) description = "👤 已登录哔哩哔哩账号；已购/已解锁章节会按账号权限读取。\n\n" + description.orEmpty()
            initialized = true
        } ?: SManga.create().apply {
            url = "/detail/mc$comicId"
            title = data.string("title").orEmpty()
            initialized = true
        }
        val eps = data["ep_list"] as? JsonArray ?: JsonArray(emptyList())
        val episodeObjects = eps.mapNotNull { it as? JsonObject }
        val accountEntitlements = if (signedIn) resolveAccountEntitlements(comicId, episodeObjects) else emptyMap()
        val chapters = episodeObjects.mapNotNull { ep ->
            val epId = ep.long("id") ?: return@mapNotNull null
            val short = ep.string("short_title").orEmpty()
            val long = ep.string("title").orEmpty()
            val locked = ep.bool("is_locked") == true
            val inFree = ep.bool("is_in_free") == true
            val paid = ep.int("pay_mode") == 1 && (ep.int("pay_gold") ?: 0) > 0
            val unlocked = paid && (accountEntitlements[epId] ?: (!locked || inFree))
            val restricted = paid && !unlocked
            SChapter.create().apply {
                url = buildString {
                    append("/mc$comicId/$epId")
                    when {
                        unlocked -> append("?paid=1&unlocked=1")
                        restricted -> append("?paid=1&locked=1")
                        paid -> append("?paid=1")
                    }
                }
                val chapterTitle = listOf(short, long).filter(String::isNotBlank).distinct().joinToString(" ")
                name = when {
                    unlocked -> "✅ $chapterTitle"
                    paid -> "🔒 $chapterTitle"
                    else -> chapterTitle
                }
                chapter_number = ep.float("ord") ?: short.toFloatOrNull() ?: -1f
                date_upload = parseTime(ep.string("pub_time"))
            }
        }.sortedByDescending { it.chapter_number }
        return manga to chapters
    }

    private suspend fun resolveAccountEntitlements(
        comicId: Long,
        episodes: List<JsonObject>,
    ): Map<Long, Boolean> {
        val paidEpisodes = episodes.filter {
            it.int("pay_mode") == 1 && (it.int("pay_gold") ?: 0) > 0 && it.long("id") != null
        }
        if (paidEpisodes.isEmpty()) return emptyMap()

        val result = mutableMapOf<Long, Boolean>()
        val unresolved = mutableListOf<JsonObject>()
        var confirmedPurchased = 0

        paidEpisodes.forEach { ep ->
            val epId = ep.long("id") ?: return@forEach
            val locked = ep.bool("is_locked") == true
            val inFree = ep.bool("is_in_free") == true
            val temporary = ep.mayBeTemporaryUnlock()

            when {
                !locked || inFree -> {
                    result[epId] = true
                    if (!inFree && !temporary) confirmedPurchased++
                }
                else -> {
                    val cached = readCachedEntitlement(comicId, epId)
                    if (cached == null) {
                        unresolved += ep
                    } else {
                        result[epId] = cached
                    }
                }
            }
        }

        if (unresolved.isEmpty()) return result

        val purchasedCount = purchasedEpisodeCount(comicId)
        val sorted = unresolved.sortedBy { it.float("ord") ?: Float.MAX_VALUE }
        var cursor = 0

        while (
            cursor < sorted.size &&
            (purchasedCount == null || confirmedPurchased < purchasedCount)
        ) {
            val endIndex = minOf(cursor + ENTITLEMENT_BATCH_SIZE, sorted.size)
            val chunk = sorted.subList(cursor, endIndex)
            val statuses = probeEntitlementBatch(comicId, chunk)

            statuses.forEach { (ep, status) ->
                val epId = ep.long("id") ?: return@forEach
                if (status != null) {
                    result[epId] = status
                    writeCachedEntitlement(comicId, epId, status)
                    if (status && !ep.mayBeTemporaryUnlock()) confirmedPurchased++
                }
            }
            cursor = endIndex
        }

        val temporaryRemaining = sorted
            .drop(cursor)
            .filter { it.mayBeTemporaryUnlock() }

        temporaryRemaining.chunked(ENTITLEMENT_BATCH_SIZE).forEach { chunk ->
            probeEntitlementBatch(comicId, chunk).forEach { (ep, status) ->
                val epId = ep.long("id") ?: return@forEach
                if (status != null) {
                    result[epId] = status
                    writeCachedEntitlement(comicId, epId, status)
                }
            }
        }

        return result
    }

    private suspend fun probeEntitlementBatch(
        comicId: Long,
        episodes: List<JsonObject>,
    ): List<Pair<JsonObject, Boolean?>> = coroutineScope {
        episodes.map { ep ->
            async(Dispatchers.IO) {
                ep to runCatching {
                    val epId = ep.long("id") ?: return@runCatching null
                    postApi(
                        "comic.v1.Comic/GetEpisodeBuyInfo",
                        """{"ep_id":$epId}""",
                        "$baseUrl/detail/mc$comicId",
                        ApiProfile.PC,
                    ).obj("data")?.bool("is_locked")?.not()
                }.getOrNull()
            }
        }.awaitAll()
    }

    private suspend fun purchasedEpisodeCount(comicId: Long): Int? {
        readCachedPurchasedCount(comicId)?.let { return it }

        for (page in 1..PURCHASED_COMICS_MAX_PAGES) {
            val root = runCatching {
                postApi(
                    "user.v1.User/GetAutoBuyComics",
                    """{"page_num":$page,"page_size":$PURCHASED_COMICS_PAGE_SIZE}""",
                    "$baseUrl/account-center",
                    ApiProfile.PC,
                )
            }.getOrElse { return null }

            val entries = root["data"] as? JsonArray ?: return null
            val match = entries
                .mapNotNull { it as? JsonObject }
                .firstOrNull { it.long("comic_id") == comicId }

            if (match != null) {
                val count = match.int("bought_ep_count") ?: 0
                writeCachedPurchasedCount(comicId, count)
                return count
            }

            if (entries.isEmpty()) {
                writeCachedPurchasedCount(comicId, 0)
                return 0
            }
        }

        return null
    }

    private fun JsonObject.mayBeTemporaryUnlock(): Boolean {
        val unlockType = int("unlock_type") ?: 0
        val expires = string("unlock_expire_at").orEmpty()
        return unlockType != 0 || (expires.isNotBlank() && !expires.startsWith("0000-"))
    }

    private fun accountCacheId(): String {
        val cookies = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
        return cookies.firstOrNull { it.name == "DedeUserID" && it.value.isNotBlank() }?.value
            ?: cookies.firstOrNull { it.name == "SESSDATA" && it.value.isNotBlank() }?.value?.hashCode()?.toString()
            ?: "unknown"
    }

    private fun entitlementCacheKey(comicId: Long, epId: Long) = "bilibili_entitlement_${accountCacheId()}_${comicId}_$epId"

    private fun readCachedEntitlement(comicId: Long, epId: Long): Boolean? {
        val raw = preferences.getString(entitlementCacheKey(comicId, epId), null) ?: return null
        val state = raw.substringBefore('|')
        val checkedAt = raw.substringAfter('|', "").toLongOrNull() ?: return null
        val unlocked = when (state) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val ttl = if (unlocked) UNLOCKED_CACHE_TTL else LOCKED_CACHE_TTL
        return unlocked.takeIf { System.currentTimeMillis() - checkedAt <= ttl }
    }

    private fun writeCachedEntitlement(comicId: Long, epId: Long, unlocked: Boolean) {
        preferences.edit()
            .putString(
                entitlementCacheKey(comicId, epId),
                "${if (unlocked) 1 else 0}|${System.currentTimeMillis()}",
            )
            .apply()
    }

    private fun purchasedCountCacheKey(comicId: Long) = "bilibili_purchased_count_${accountCacheId()}_$comicId"

    private fun readCachedPurchasedCount(comicId: Long): Int? {
        val raw = preferences.getString(purchasedCountCacheKey(comicId), null) ?: return null
        val count = raw.substringBefore('|').toIntOrNull() ?: return null
        val checkedAt = raw.substringAfter('|', "").toLongOrNull() ?: return null
        return count.takeIf { System.currentTimeMillis() - checkedAt <= PURCHASED_COUNT_CACHE_TTL }
    }

    private fun writeCachedPurchasedCount(comicId: Long, count: Int) {
        preferences.edit()
            .putString(purchasedCountCacheKey(comicId), "$count|${System.currentTimeMillis()}")
            .apply()
    }

    override fun getChapterUrl(chapter: SChapter): String = if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val signedIn = isSignedIn()
        if (chapter.url.contains("locked=1") && !signedIn) {
            throw IOException("🔒 该章节为付费内容。若账号已购买，请先在本源 WebView 登录哔哩哔哩账号，再刷新章节目录")
        }
        val match = CHAPTER_ID.find(chapter.url) ?: throw IOException("无法解析哔哩哔哩漫画章节 ID")
        val comicId = match.groupValues[1]
        val epId = match.groupValues[2]
        val referer = "$baseUrl/mc$comicId/$epId?from=manga_detail"
        val index = try {
            postApi("comic.v1.Comic/GetImageIndex", """{"epId":$epId}""", referer, ApiProfile.ANDROID)
        } catch (e: IOException) {
            if (chapter.url.contains("locked=1") && signedIn) {
                throw IOException("🔒 当前账号没有该章节阅读权限，或登录状态已失效；请在 WebView 确认账号后刷新目录", e)
            }
            throw e
        }
        val images = index.obj("data")?.get("images") as? JsonArray ?: JsonArray(emptyList())
        if (images.isEmpty()) throw IOException("本话没有可读取图片，可能是账号未解锁或登录状态已失效")

        val paths = images.mapNotNull { item ->
            val image = item as? JsonObject ?: return@mapNotNull null
            optimizedPagePath(image)
        }
        val urlsJson = paths.joinToString(prefix = "[", postfix = "]") { quote(it) }
        val tokenBody = """{"urls":${quote(urlsJson)}}"""
        val tokens = postApi("comic.v1.Comic/ImageToken", tokenBody, referer, ApiProfile.ANDROID)
            .get("data") as? JsonArray ?: JsonArray(emptyList())
        val urls = tokens.mapNotNull { token ->
            val obj = token as? JsonObject ?: return@mapNotNull null
            if (obj.bool("hit_encrpyt") == true) return@mapNotNull null
            obj.string("complete_url")?.takeIf(String::isNotBlank)
                ?: obj.string("url")?.takeIf(String::isNotBlank)?.let { url ->
                    val value = obj.string("token").orEmpty()
                    if (value.isBlank()) url else url + (if ("?" in url) "&" else "?") + "token=" + value
                }
        }
        if (urls.size != paths.size) throw IOException("哔哩哔哩漫画正文返回了加密或无效图片：${urls.size}/${paths.size}")
        if (signedIn && chapter.url.contains("paid=1")) {
            writeCachedEntitlement(comicId.toLong(), epId.toLong(), true)
        }
        return urls.mapIndexed { indexNo, image -> Page(indexNo, url = referer, imageUrl = image) }
    }

    override fun imageRequest(page: Page): Request = GET(
        requireNotNull(page.imageUrl),
        headers.newBuilder()
            .removeAll("Content-Type")
            .removeAll("Cookie")
            .set("Referer", page.url)
            .set("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .build(),
    )

    private fun commentFromReply(obj: JsonObject): Comment? {
        val id = obj.string("rpid_str") ?: obj.long("rpid")?.toString() ?: return null
        val member = obj.obj("member") ?: JsonObject(emptyMap())
        val contentObject = obj.obj("content") ?: JsonObject(emptyMap())
        val message = commentMessageWithEmotes(contentObject)
        val pictures = (contentObject["pictures"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
            .mapNotNull { picture ->
                picture.string("img_src")
                    ?: picture.string("img_url")
                    ?: picture.string("url")
            }
            .map(::normalizeBilibiliUrl)
            .distinct()
        val content = commentContentWithImages(message, pictures) ?: return null
        val mid = member.string("mid") ?: member.long("mid")?.toString()
        val parentId = obj.string("parent_str")
            ?: obj.long("parent")?.takeIf { it > 0 }?.toString()

        return Comment(
            id = id,
            author = CommentAuthor(
                id = mid,
                name = member.string("uname").orEmpty().ifBlank { "Bilibili 用户" },
                avatarUrl = member.string("avatar")?.let(::normalizeBilibiliUrl),
                profileUrl = mid?.let { "https://space.bilibili.com/$it" },
            ),
            content = content,
            createdAt = (obj.long("ctime") ?: 0L) * 1000L,
            likeCount = obj.long("like") ?: 0L,
            replyCount = obj.long("rcount") ?: 0L,
            likedByMe = obj.int("action") == 1,
            parentId = parentId,
        )
    }

    private suspend fun commentHeaders(comicId: Long): Headers = headers.newBuilder()
        .removeAll("Content-Type")
        .removeAll("Origin")
        .removeAll("Referer")
        .set("User-Agent", WEBVIEW_USER_AGENT)
        .set("Accept", "application/json, text/plain, */*")
        .set("Origin", baseUrl)
        .set("Referer", if (comicId > 0L) "$baseUrl/detail/mc$comicId" else "https://www.bilibili.com/")
        .set("Cookie", mergedCookieHeader())
        .build()

    private fun executeCommentRequest(request: Request): JsonObject {
        val root = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Bilibili Web API HTTP " + response.code)
            JSON.parseToJsonElement(response.body.string()).jsonObject
        }
        val code = root.int("code") ?: 0
        if (code != 0) {
            val message = root.string("message") ?: root.string("msg") ?: "Bilibili Web API 错误 $code"
            throw IOException(message)
        }
        return root
    }

    private fun accountCookieValue(name: String): String? = client.cookieJar
        .loadForRequest(baseUrl.toHttpUrl())
        .firstOrNull { it.name == name && it.value.isNotBlank() }
        ?.value

    private fun commentMessageWithEmotes(contentObject: JsonObject): String {
        var message = contentObject.string("message").orEmpty()

        (contentObject["emote"] as? JsonObject)?.forEach { (code, element) ->
            val emote = element as? JsonObject ?: return@forEach
            val url = emote.string("url")
                ?: emote.string("icon_url")
                ?: emote.obj("emoji")?.string("icon_url")
            if (!url.isNullOrBlank()) {
                message = message.replace(
                    code,
                    markdownCommentImage(
                        normalizeBilibiliUrl(url),
                        code.removePrefix("[").removeSuffix("]").ifBlank { "表情" },
                    ),
                )
            }
        }

        val richNodes = contentObject["rich_text_nodes"] as? JsonArray
        if (richNodes != null && (message.isBlank() || message.contains('\uFFFD'))) {
            val richText = richNodes.mapNotNull { element ->
                val node = element as? JsonObject ?: return@mapNotNull null
                val nodeText = node.string("text").orEmpty()
                val nodeUrl = node.obj("emoji")?.string("icon_url")
                    ?: node.obj("emoji")?.string("url")
                    ?: node.string("emoji_url")
                    ?: node.obj("image")?.string("url")
                    ?: node.obj("image")?.string("src")
                when {
                    !nodeUrl.isNullOrBlank() -> markdownCommentImage(
                        normalizeBilibiliUrl(nodeUrl),
                        nodeText.ifBlank { "表情" },
                    )
                    nodeText.isNotBlank() -> nodeText
                    else -> null
                }
            }.joinToString("")
            if (richText.isNotBlank()) message = richText
        }

        return message
    }

    private fun markdownCommentImage(url: String, alt: String = "图片"): String = "![${alt.replace("]", "").replace("[", "")}]($url)"

    private fun commentContentWithImages(message: String, imageUrls: List<String>): String? = (
        listOf(message.trim()).filter(String::isNotBlank) +
            imageUrls.filter(String::isNotBlank).distinct().map { markdownCommentImage(it) }
        )
        .joinToString("\n\n")
        .takeIf(String::isNotBlank)

    private fun normalizeBilibiliUrl(value: String): String = when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://") -> "https://" + value.removePrefix("http://")
        else -> value
    }

    private suspend fun postApi(
        path: String,
        body: String,
        referer: String = "$baseUrl/",
        profile: ApiProfile = ApiProfile.ANDROID,
        retry: Boolean = true,
    ): JsonObject {
        val suffix = when (profile) {
            ApiProfile.ANDROID -> buildString {
                append("?ts=").append(System.currentTimeMillis() / 1000)
                append("&appkey=").append(APP_KEY)
                append("&mobi_app=android_comic")
                append("&version=").append(APP_VERSION)
                append("&build=").append(APP_BUILD)
                append("&channel=bilicomic")
                append("&platform=android")
                append("&device=android")
            }
            ApiProfile.PC -> "?device=pc&platform=web"
            ApiProfile.NONE -> ""
        }
        val cookie = mergedCookieHeader()
        val requestHeaders = headers.newBuilder()
            .removeAll("Origin")
            .removeAll("Referer")
            .set("User-Agent", APP_USER_AGENT)
            .set("Cookie", cookie)
            .apply {
                if (profile != ApiProfile.ANDROID) set("Referer", referer)
                if (profile == ApiProfile.PC) set("Origin", baseUrl)
            }
            .build()
        val request = Request.Builder()
            .url("$baseUrl/twirp/$path$suffix")
            .headers(requestHeaders)
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        val root = client.newCall(request).execute().use {
            if (!it.isSuccessful) throw IOException("哔哩哔哩漫画 HTTP ${it.code}")
            JSON.parseToJsonElement(it.body.string()).jsonObject
        }
        val code = root["code"]?.jsonPrimitive?.intOrNull ?: 0
        if (code == 99 && retry) {
            ensureFingerprintCookie(force = true)
            return postApi(path, body, referer, profile, retry = false)
        }
        if (code != 0) throw IOException(root["msg"]?.jsonPrimitive?.contentOrNull ?: "哔哩哔哩漫画接口错误 $code")
        return root
    }

    private suspend fun mergedCookieHeader(): String {
        val account = accountCookies()
        val fingerprint = ensureFingerprintCookie()
        val names = account.map { it.substringBefore('=') }.toSet()
        val fp = fingerprint.split(';').map(String::trim).filter(String::isNotBlank)
            .filter { it.substringBefore('=') !in names }
        return (account + fp).joinToString("; ")
    }

    private fun accountCookies(): List<String> = client.cookieJar
        .loadForRequest(baseUrl.toHttpUrl())
        .filter { it.name in ACCOUNT_COOKIE_NAMES }
        .map { "${it.name}=${it.value}" }

    private fun isSignedIn(): Boolean = client.cookieJar
        .loadForRequest(baseUrl.toHttpUrl())
        .any { it.name == "SESSDATA" && it.value.isNotBlank() }

    private fun loginSessionSummary(): String {
        val sessdata = client.cookieJar
            .loadForRequest(baseUrl.toHttpUrl())
            .firstOrNull { it.name == "SESSDATA" && it.value.isNotBlank() }

        val expiry = sessdata
            ?.takeIf { it.persistent && it.expiresAt < Long.MAX_VALUE }
            ?.expiresAt
            ?.let {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(it))
            }

        return buildString {
            append("当前已登录")
            if (expiry != null) append("；登录 Cookie 预计有效至 $expiry")
            append("。点击可重新登录或切换账号。若 Bilibili 提前使会话失效，则需要重新登录。")
        }
    }

    private suspend fun ensureFingerprintCookie(force: Boolean = false): String {
        val now = System.currentTimeMillis()
        fingerprintCookie
            ?.takeIf { !force && it.isNotBlank() && now - fingerprintFetchedAt < FINGERPRINT_TTL }
            ?.let { return it }

        val cookie = runCatching {
            val request = Request.Builder()
                .url(FINGERPRINT_URL)
                .header("User-Agent", APP_USER_AGENT)
                .header("Accept", "application/json")
                .header("Referer", "$baseUrl/")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("哔哩哔哩指纹接口 HTTP ${response.code}")
                val root = JSON.parseToJsonElement(response.body.string()).jsonObject
                val data = root.obj("data") ?: throw IOException("哔哩哔哩指纹接口数据为空")
                val b3 = data.string("b_3").orEmpty()
                val b4 = data.string("b_4").orEmpty()
                if (b3.isBlank()) throw IOException("哔哩哔哩指纹接口未返回 buvid3")
                buildString {
                    append("buvid3=").append(b3).append(';')
                    if (b4.isNotBlank()) append(" buvid4=").append(b4).append(';')
                }
            }
        }.getOrElse { "buvid3=${UUID.randomUUID()}infoc;" }

        fingerprintCookie = cookie
        fingerprintFetchedAt = now
        return cookie
    }

    private fun optimizedCover(url: String?): String? = url
        ?.takeIf(String::isNotBlank)
        ?.let { if ('@' in it.substringAfterLast('/')) it else "$it@${COVER_IMAGE_WIDTH}w.jpg" }

    private fun optimizedPagePath(image: JsonObject): String? {
        val path = image.string("path")?.takeIf(String::isNotBlank) ?: return null
        if ('@' in path.substringAfterLast('/')) return path
        val originalWidth = image.int("x")?.takeIf { it > 0 }
        val width = originalWidth?.coerceAtMost(PAGE_IMAGE_WIDTH) ?: PAGE_IMAGE_WIDTH
        return "$path@${width}w.jpg"
    }

    private fun comicId(manga: SManga): Long = COMIC_ID.find(manga.url)?.groupValues?.getOrNull(1)?.toLongOrNull()
        ?: throw IOException("无法解析哔哩哔哩漫画作品 ID")

    private fun parseTime(value: String?): Long = value?.let {
        runCatching { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).parse(it)?.time ?: 0L }.getOrDefault(0L)
    } ?: 0L

    private fun quote(value: String): String = JSON.encodeToString(kotlinx.serialization.serializer<String>(), value)

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.long(key: String) = this[key]?.jsonPrimitive?.longOrNull
    private fun JsonObject.float(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
    private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.stringList(key: String): List<String> = when (val value = this[key]) {
        is JsonArray -> value.mapNotNull { item ->
            when (item) {
                is JsonObject -> item.string("name") ?: item.string("title")
                else -> item.jsonPrimitive.contentOrNull
            }
        }
        else -> emptyList()
    }

    private class StatusFilter : Filter.Select<String>("状态", arrayOf("全部", "连载", "完结")) {
        fun value() = intArrayOf(-1, 0, 1)[state]
    }
    private class AreaFilter : Filter.Select<String>("地区", arrayOf("全部", "大陆", "日本", "韩国", "其他")) {
        fun value() = intArrayOf(-1, 1, 2, 6, 5)[state]
    }
    private class StyleFilter : Filter.Select<String>("题材", arrayOf("全部", "热血", "恋爱", "搞笑", "冒险", "奇幻", "玄幻", "都市", "悬疑", "治愈")) {
        fun value() = intArrayOf(-1, 999, 995, 994, 1013, 998, 1016, 1002, 1023, 1007)[state]
    }
    private class OrderFilter : Filter.Select<String>("排序", arrayOf("人气", "更新")) {
        fun value() = intArrayOf(0, 1)[state]
    }

    private enum class ApiProfile { ANDROID, PC, NONE }

    private companion object {
        const val APP_USER_AGENT = "Mozilla/5.0 (Linux; Android 12; SM-G9730) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        const val WEBVIEW_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        const val APP_KEY = "cc8617fd6961e070"
        const val APP_VERSION = "4.13.0"
        const val APP_BUILD = "36413002"
        const val FINGERPRINT_URL = "https://api.bilibili.com/x/frontend/finger/spi"
        const val COMMENT_API_BASE = "https://api.bilibili.com"
        const val MANGA_COMMENT_TYPE = 22
        const val CHAPTER_COMMENT_TYPE = 29
        const val COMMENT_SORT_DEFAULT = 1
        const val COMMENT_SORT_LATEST = 2
        const val COMMENT_SORT_HOT = 3
        const val SORT_DEFAULT = "default"
        const val SORT_HOT = "hot"
        const val SORT_LATEST = "latest"
        const val COMMENT_PAGE_SIZE = 20L
        const val COMMENT_WEB_LOCATION = "1315875"
        const val WBI_KEY_TTL = 6 * 60 * 60 * 1000L
        val WBI_FILTER_CHARS = setOf('!', '\'', '(', ')', '*')
        val WBI_MIXIN_KEY_TABLE = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
            26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
            20, 34, 44, 52,
        )
        const val FINGERPRINT_TTL = 6 * 60 * 60 * 1000L
        const val ACCOUNT_LOGIN_URL = "https://www.bilibili.com/cheese/mine/list"
        const val COVER_IMAGE_WIDTH = 512
        const val PAGE_IMAGE_WIDTH = 1200
        const val ENTITLEMENT_BATCH_SIZE = 6
        const val PURCHASED_COMICS_PAGE_SIZE = 100
        const val PURCHASED_COMICS_MAX_PAGES = 20
        const val UNLOCKED_CACHE_TTL = 6 * 60 * 60 * 1000L
        const val LOCKED_CACHE_TTL = 5 * 60 * 1000L
        const val PURCHASED_COUNT_CACHE_TTL = 10 * 60 * 1000L
        val ACCOUNT_COOKIE_NAMES = setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid", "buvid3", "buvid4")
        val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        val JSON_MEDIA = "application/json;charset=UTF-8".toMediaType()
        val COMIC_ID = Regex("(?:/detail/)?mc(\\d+)")
        val CHAPTER_ID = Regex("/mc(\\d+)/(\\d+)")
    }
}
