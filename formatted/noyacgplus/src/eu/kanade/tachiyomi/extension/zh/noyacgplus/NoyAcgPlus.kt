package eu.kanade.tachiyomi.extension.zh.noyacgplus

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
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
import eu.kanade.tachiyomi.source.mx.SourceAccount
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import openccjava.OpenCC
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@Source
abstract class NoyAcgPlus :
    KeiSource(),
    ConfigurableSource,
    CommentSource,
    AccountSource {

    private val prefs = getPreferences()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val imgBaseUrl get() = baseUrl.replace("://api.", "://img.")
    private val bucketBaseUrl get() = imgBaseUrl.replace("://img.", "://bucket.")
    private val loginLock = Any()
    private val autoSignRunning = AtomicBoolean(false)

    @Volatile
    private var sessionGeneration = 0

    override fun getHomeUrl() = WEB_BASE_URL

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        preferences(screen.context, prefs).forEach(screen::addPreference)
    }

    override fun Headers.Builder.configureHeaders() = apply {
        set("User-Agent", "NoyAcg/3.0")
        set("Accept", "application/json, text/plain, */*")
        set("allow-adult", prefs.getString(ADULT_PREF, "both") ?: "both")
    }

    override fun OkHttpClient.Builder.configureClient() = apply {
        cookieJar(SessionCookieJar(getHomeUrl().toHttpUrl(), network.client.cookieJar))
        addInterceptor { chain ->
            val requestGeneration = sessionGeneration
            val response = chain.proceed(chain.request())
            if (!response.isLoginRequired()) return@addInterceptor response

            response.close()
            synchronized(loginLock) {
                if (requestGeneration == sessionGeneration) {
                    login()
                    sessionGeneration++
                }
            }

            chain.proceed(chain.request()).also {
                if (it.isLoginRequired()) {
                    it.close()
                    throw IOException("登录失败，请检查账号与密码")
                }
            }
        }
    }

    private fun Response.isLoginRequired(): Boolean = header("Content-Type")?.contains("application/json") == true &&
        runCatching {
            parseObject(peekBody(LOGIN_RESPONSE_SIZE).string()).str("status") == "login"
        }.getOrDefault(false)

    private fun login() {
        val user = prefs.getString(USERNAME_PREF, "").orEmpty().trim()
        val pass = prefs.getString(PASSWORD_PREF, "").orEmpty()
        if (user.isBlank() || pass.isBlank()) {
            throw IOException("请在扩展设置填写账号与密码，或先在 WebView 登录")
        }

        val body = FormBody.Builder()
            .add("user", user)
            .add("pass", pass)
            .build()
        val loginHeaders = headers.newBuilder()
            .set("Origin", WEB_BASE_URL)
            .set("Referer", "$WEB_BASE_URL/")
            .build()

        network.client.newCall(POST("$WEB_BASE_URL/api/login", loginHeaders, body)).execute().use {
            val root = parseObject(it.body.string())
            when (root.str("status")) {
                "error" -> throw IOException("账号或密码错误")
                "danger" -> throw IOException("账号或密码包含不允许的字符")
            }
        }
    }

    private fun autoSignIfNeeded() {
        if (!prefs.getBoolean(AUTO_SIGN_PREF, true)) return

        val user = prefs.getString(USERNAME_PREF, "").orEmpty().trim()
        val pass = prefs.getString(PASSWORD_PREF, "").orEmpty()
        val hasSavedCredentials = user.isNotEmpty() && pass.isNotEmpty()
        val hasWebSession = network.client.cookieJar
            .loadForRequest(getHomeUrl().toHttpUrl())
            .isNotEmpty()
        if (!hasSavedCredentials && !hasWebSession) return

        val today = dayKey()
        if (prefs.getString(LAST_SIGN_DAY_PREF, "") == today) return

        val now = System.currentTimeMillis()
        val lastAttempt = prefs.getLong(LAST_SIGN_ATTEMPT_PREF, 0L)
        if (now - lastAttempt < AUTO_SIGN_RETRY_INTERVAL_MS) return
        if (!autoSignRunning.compareAndSet(false, true)) return

        prefs.edit().putLong(LAST_SIGN_ATTEMPT_PREF, now).apply()
        try {
            val record = postEmptyForm("$baseUrl/api/v4/signin/record")
            if (isSignedToday(record)) {
                markSigned(today, "今日已签到")
                return
            }

            val result = postEmptyForm("$baseUrl/api/v4/signin/sign")
            val status = result.str("status") ?: result.obj("data")?.str("status")
            if (status != null && status.lowercase(Locale.ROOT) in SIGN_ERROR_VALUES) {
                throw IOException(
                    result.str("message")
                        ?: result.str("msg")
                        ?: "自动签到失败",
                )
            }
            markSigned(today, "自动签到成功")
        } catch (e: Exception) {
            prefs.edit()
                .putString(LAST_SIGN_STATUS_PREF, "自动签到失败：${e.message ?: e.javaClass.simpleName}")
                .apply()
        } finally {
            autoSignRunning.set(false)
        }
    }

    private fun postEmptyForm(url: String): JsonObject {
        val request = POST(url, headers, FormBody.Builder().build())
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            parseObject(response.body.string())
        }
    }

    private fun isSignedToday(root: JsonObject): Boolean {
        val data = root.obj("data")
        val nested = data?.obj("data")
        val value = root["today"] ?: data?.get("today") ?: nested?.get("today") ?: return false
        val primitive = value as? JsonPrimitive ?: return false
        primitive.intOrNull?.let { return it == 1 || it == 200 }
        val text = primitive.contentOrNull
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?: return false
        return text in SIGNED_VALUES
    }

    private fun markSigned(day: String, status: String) {
        prefs.edit()
            .putString(LAST_SIGN_DAY_PREF, day)
            .putString(LAST_SIGN_STATUS_PREF, status)
            .apply()
    }

    private fun ensureSessionAndSign() {
        autoSignIfNeeded()
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        ensureSessionAndSign()
        val body = FormBody.Builder()
            .add("type", prefs.getString(POPULAR_PREF, "day") ?: "day")
            .add("page", page.toString())
            .build()
        return listing(client.post("$baseUrl/api/readLeaderboard", body), page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        ensureSessionAndSign()
        val body = FormBody.Builder()
            .add("page", page.toString())
            .add("sort", "new")
            .build()
        return listing(client.post("$baseUrl/api/b1/booklist", body), page)
    }

    override fun getFilterList(data: JsonElement?) = buildFilterList()

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        ensureSessionAndSign()
        val body = FormBody.Builder()
            .add("value", query)
            .add("page", page.toString())
            .add("type", "book")
        filters.filterIsInstance<SearchFilter>().forEach { it.addTo(body) }
        return listing(client.post("$baseUrl/api/v4/search/fetch", body.build()), page)
    }

    private fun listing(response: Response, page: Int): MangasPage {
        val root = response.use { parseObject(it.body.string()) }
        val entries = root.arr("data") ?: root.arr("info") ?: JsonArray(emptyList())
        val count = root.int("count") ?: root.int("len") ?: entries.size
        return MangasPage(
            entries.mapNotNull { (it as? JsonObject)?.toManga() },
            page * LISTING_PAGE_SIZE < count,
        )
    }

    override fun getMangaUrl(manga: SManga) = "$WEB_BASE_URL/manga/${manga.url}"

    override fun getChapterUrl(chapter: SChapter) = "$WEB_BASE_URL/reader/${chapter.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? = url.pathSegments.lastOrNull()?.toIntOrNull()?.let { loadBook(it.toString()).first }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val (fresh, freshChapters) = loadBook(manga.url)
        return SMangaUpdate(
            if (fetchDetails) fresh else manga,
            if (fetchChapters) freshChapters else chapters,
        )
    }

    private suspend fun loadBook(id: String): Pair<SManga, List<SChapter>> {
        ensureSessionAndSign()
        val root = client.get("$baseUrl/api/v4/book/$id?comment=false").use {
            parseObject(it.body.string())
        }
        val bookRoot = root.obj("book") ?: throw IOException("NoyAcg 作品信息为空")
        val info = bookRoot.obj("info") ?: bookRoot
        val manga = info.toManga()

        val categories = root.obj("chapters")?.arr("categories") ?: JsonArray(emptyList())
        val data = root.obj("chapters")?.obj("data")
        val chapters = if (data != null && categories.isNotEmpty()) {
            categories.flatMap { categoryElement ->
                val category = categoryElement as? JsonObject ?: return@flatMap emptyList<SChapter>()
                val categoryId = category.int("id")?.toString() ?: return@flatMap emptyList<SChapter>()
                val categoryName = simp(category.str("name")).orEmpty()
                data.arr(categoryId)
                    ?.mapNotNull { chapterElement ->
                        val chapter = chapterElement as? JsonObject ?: return@mapNotNull null
                        val chapterId = chapter.int("id") ?: return@mapNotNull null
                        val size = chapter.int("count") ?: 0
                        SChapter.create().apply {
                            url = "$id/$chapterId"
                            name = "${simp(chapter.str("name")) ?: chapterId.toString()}（${size}P）"
                            scanlator = categoryName
                            date_upload = (chapter.long("created_at") ?: 0L) * 1000
                            memo = buildJsonObject { put("size", size) }
                        }
                    }
                    ?.reversed()
                    .orEmpty()
            }
        } else {
            val size = info.int("Len") ?: info.int("len") ?: 0
            listOf(
                SChapter.create().apply {
                    url = id
                    name = "单章节（${size}P）"
                    date_upload = (info.long("Time") ?: info.long("time") ?: 0L) * 1000
                    chapter_number = 0F
                    memo = buildJsonObject { put("size", size) }
                },
            )
        }

        return manga to chapters
    }

    override suspend fun getPageList(chapter: SChapter) = List((chapter.memo["size"] as? JsonPrimitive)?.intOrNull ?: 0) {
        Page(it, imageUrl = "/${chapter.url}/${it + 1}.webp")
    }

    override fun imageRequest(page: Page) = GET(imgBaseUrl + page.imageUrl, headers)

    override val commentCapabilities = CommentCapabilities(
        supportsMangaComments = true,
        supportsChapterComments = false,
        canPost = false,
        canReply = false,
        canLike = false,
        requiresLoginToPost = true,
    )

    override suspend fun getMangaCommentTarget(manga: SManga) = CommentTarget(manga.url.substringAfterLast('/'), getMangaUrl(manga))

    override suspend fun getComments(target: CommentTarget, page: Int) = commentPage(target.id, null, page)

    override suspend fun getCommentReplies(
        target: CommentTarget,
        comment: Comment,
        page: Int,
    ) = commentPage(target.id, comment.id, page)

    private suspend fun commentPage(
        bookId: String,
        commentId: String?,
        page: Int,
    ): CommentPage {
        ensureSessionAndSign()
        val safePage = page.coerceAtLeast(1)
        val path = if (commentId == null) {
            "/api/v4/comment/book/$bookId/comments?page=$safePage"
        } else {
            "/api/v4/comment/book/$bookId/comment/$commentId/replies?page=$safePage"
        }
        val root = client.get(baseUrl + path).use { parseObject(it.body.string()) }
        val data = root.obj("data") ?: root
        val entries = data.arr(if (commentId == null) "comments" else "replies")
            ?: root.arr("replies")
            ?: JsonArray(emptyList())
        val comments = entries.mapNotNull { (it as? JsonObject)?.toComment(commentId) }
        val over = data.bool("over") ?: root.bool("over") ?: comments.isEmpty()
        return CommentPage(
            comments = comments,
            hasNextPage = !over,
            totalCount = data.long("count") ?: data.long("total"),
        )
    }

    override suspend fun getSourceAccount(): SourceAccount? = runCatching {
        ensureSessionAndSign()
        val root = client.post(
            "$baseUrl/api/v3/userinfo?msg=true",
            FormBody.Builder().build(),
        ).use { parseObject(it.body.string()) }
        val data = root.obj("data")
        val user = root.obj("userinfo")
            ?: root.obj("userInfo")
            ?: data?.obj("userinfo")
            ?: data?.obj("userInfo")
            ?: return@runCatching null
        val name = user.strAny("Username", "username", "nickname", "name")
            ?: return@runCatching null
        val id = user.strAny("Uid", "uid", "id")
        val avatarPath = user.strAny("Avatar", "avatar", "avatar_url")
            ?.trim()
            ?.trimStart('/')
        val avatar = avatarPath?.takeIf(String::isNotBlank)?.let { path ->
            if (path.startsWith("http://") || path.startsWith("https://")) {
                path
            } else {
                val avatarBase = if (path.startsWith("avatar/", ignoreCase = true)) {
                    bucketBaseUrl
                } else {
                    imgBaseUrl
                }
                "$avatarBase/$path"
            }
        }
        SourceAccount(
            id = id,
            name = simp(name) ?: name,
            avatarUrl = avatar,
            profileUrl = WEB_BASE_URL,
        )
    }.getOrNull()

    private fun JsonObject.toManga(): SManga {
        val id = int("Bid") ?: int("id") ?: return SManga.create()
        val mode = int("Mode") ?: int("mode") ?: 0
        val status = int("Status") ?: int("status") ?: 0
        val tags = str("Ptag")?.replace(" ", ", ")
            ?: arr("tags")?.joinToString { simp((it as? JsonPrimitive)?.contentOrNull).orEmpty() }.orEmpty()
        return SManga.create().apply {
            url = id.toString()
            title = simp(strAny("Bookname", "name")).orEmpty()
            author = simp(strAny("Author", "author"))
            description = simp(strAny("Description", "description"))
            genre = simp(tags)
            thumbnail_url = "$imgBaseUrl/$id/m1.webp"
            this.status = if (mode == 0 || status == 1) SManga.COMPLETED else SManga.ONGOING
            initialized = true
        }
    }

    private fun JsonObject.toComment(parent: String?): Comment? {
        val id = strAny("cid", "id") ?: return null
        val user = obj("user")
        val name = simp(
            strAny("username", "reply_username")
                ?: user?.strAny("name", "username")
                ?: "匿名用户",
        ) ?: "匿名用户"
        val avatarPath = (str("avatar") ?: user?.str("avatar"))
            ?.trim()
            ?.trimStart('/')
        val avatar = avatarPath?.takeIf(String::isNotBlank)?.let { path ->
            if (path.startsWith("http://") || path.startsWith("https://")) {
                path
            } else {
                val avatarBase = if (path.startsWith("avatar/", ignoreCase = true)) {
                    bucketBaseUrl
                } else {
                    imgBaseUrl
                }
                "$avatarBase/$path"
            }
        }
        val replies = arr("replies")
        return Comment(
            id = id,
            author = CommentAuthor(name = name, avatarUrl = avatar),
            content = simp(strAny("content", "reply")).orEmpty(),
            createdAt = normalizeTime(long("time") ?: long("created_at") ?: 0),
            replyCount = long("reply_num")
                ?: long("reply_count")
                ?: long("replyCount")
                ?: replies?.size?.toLong()
                ?: 0,
            parentId = parent,
        )
    }

    private fun normalizeTime(value: Long) = if (value > 1_000_000_000_000L) value else value * 1000

    private fun simp(text: String?): String? = text?.let { value ->
        if (prefs.getBoolean(SIMPLIFY_PREF, true)) {
            runCatching { OpenCC.convert(value, "t2s").toString() }.getOrElse { value }
        } else {
            value
        }
    }

    private fun parseObject(text: String) = json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())

    private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.strAny(vararg keys: String) = keys.firstNotNullOfOrNull { str(it) }

    private fun JsonObject.int(key: String) = (get(key) as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull

    private fun JsonObject.bool(key: String) = str(key)?.lowercase(Locale.ROOT)?.let {
        when (it) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }

    private fun JsonObject.obj(key: String) = get(key) as? JsonObject

    private fun JsonObject.arr(key: String) = get(key) as? JsonArray

    private fun dayKey() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    companion object {
        const val WEB_BASE_URL = "https://noymanga.com"
        const val LOGIN_RESPONSE_SIZE = 256L
        const val LISTING_PAGE_SIZE = 20
        const val LAST_SIGN_DAY_PREF = "last_sign_day"
        const val LAST_SIGN_ATTEMPT_PREF = "last_sign_attempt"
        const val LAST_SIGN_STATUS_PREF = "last_sign_status"
        const val AUTO_SIGN_RETRY_INTERVAL_MS = 10 * 60 * 1000L

        val SIGN_ERROR_VALUES = setOf("error", "danger", "login", "false", "0")
        val SIGNED_VALUES = setOf(
            "true",
            "1",
            "200",
            "yes",
            "ok",
            "signed",
            "signed_in",
            "已签到",
            "已簽到",
        )
    }
}
