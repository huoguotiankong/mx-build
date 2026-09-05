package eu.kanade.tachiyomi.extension.zh.komiicplus

import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
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
import eu.kanade.tachiyomi.source.mx.SourceAccount
import keiyoushi.annotation.Source
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseGraphQLAs
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.time.Instant

@Source
abstract class KomiicPlus :
    KeiSource(),
    ConfigurableSource,
    CommentSource,
    AccountSource {

    private val pref by getPreferencesLazy()
    private val json = Json { ignoreUnknownKeys = true }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var loginStatusPreference: EditTextPreference? = null

    @Volatile
    private var quotaPreference: EditTextPreference? = null

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor(::authInterceptor)
        addInterceptor(::mirrorInterceptor)
    }

    private fun authInterceptor(chain: Interceptor.Chain): Response {
        var request = chain.request()
        if (!request.url.isKomiicHost()) return chain.proceed(request)

        var token = currentToken()
        if (hasStoredCredentials() && (token.isBlank() || tokenNeedsRefresh(token))) {
            token = runCatching { loginWithStoredCredentials(updateUi = false) }.getOrDefault(token)
        }
        if (token.isNotBlank()) {
            request = request.newBuilder().header("Authorization", "Bearer $token").build()
        }

        var response = chain.proceed(request)
        if (response.code == 401 && hasStoredCredentials()) {
            response.close()
            val refreshed = runCatching { loginWithStoredCredentials(updateUi = false) }.getOrDefault("")
            if (refreshed.isNotBlank()) {
                request = request.newBuilder().header("Authorization", "Bearer $refreshed").build()
                response = chain.proceed(request)
            }
        }

        if (request.url.encodedPath.startsWith("/api/image/") && response.code == 402) {
            response.close()
            markQuotaExhausted()
            throw IOException("今日图片读取额度已用完，请登录账号或等待额度重置")
        }
        return response
    }

    private fun mirrorInterceptor(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!original.url.isKomiicHost()) return chain.proceed(original)

        val selected = normalizedBaseUrl()
        val selectedRequest = routeToBase(original, selected)
        val autoFallback = pref.getBoolean(AUTO_FALLBACK_PREF, true)

        val response = try {
            chain.proceed(selectedRequest)
        } catch (first: IOException) {
            if (!autoFallback) throw first
            return chain.proceed(routeToBase(original, alternateBase(selected)))
        }

        if (!autoFallback || !shouldTryAlternate(response.code)) return response
        response.close()
        return chain.proceed(routeToBase(original, alternateBase(selected)))
    }

    private fun shouldTryAlternate(code: Int): Boolean = code == 408 || code == 429 || code == 403 || code >= 500

    private fun normalizedBaseUrl(): String = baseUrl.removeSuffix("/").takeIf { it in KNOWN_BASE_URLS } ?: KNOWN_BASE_URLS.first()

    private fun alternateBase(current: String): String = KNOWN_BASE_URLS.first { it != current }

    private fun routeToBase(request: Request, targetBase: String): Request {
        val targetHost = targetBase.toHttpUrl().host
        val sourceHost = request.url.host
        val mappedHost = when {
            sourceHost == "komiic.com" || sourceHost == "komiic.cc" -> targetHost
            sourceHost.endsWith(".komiic.com") -> sourceHost.removeSuffix("komiic.com") + targetHost
            sourceHost.endsWith(".komiic.cc") -> sourceHost.removeSuffix("komiic.cc") + targetHost
            else -> sourceHost
        }
        if (mappedHost == sourceHost) return request

        val newUrl = request.url.newBuilder().host(mappedHost).build()
        return request.newBuilder()
            .url(newUrl)
            .apply {
                request.header("Origin")?.let { header("Origin", targetBase) }
                request.header("Referer")?.let { header("Referer", "$targetBase/") }
            }
            .build()
    }

    private fun HttpUrl.isKomiicHost(): Boolean = host == "komiic.com" || host == "komiic.cc" || host.endsWith(".komiic.com") || host.endsWith(".komiic.cc")

    private fun currentToken(): String = pref.getString(TOKEN_PREF, "").orEmpty().trim()

    private fun hasStoredCredentials(): Boolean = pref.getString(EMAIL_PREF, "").orEmpty().isNotBlank() && pref.getString(PASSWORD_PREF, "").orEmpty().isNotBlank()

    private fun tokenNeedsRefresh(token: String): Boolean = runCatching {
        val parts = token.split('.')
        if (parts.size != 3) return@runCatching false
        val payload = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).decodeToString()
        val exp = json.decodeFromString(JwtPayload.serializer(), payload).exp
        System.currentTimeMillis() + TOKEN_REFRESH_WINDOW_MS >= exp * 1000
    }.getOrDefault(false)

    private fun loginWithStoredCredentials(updateUi: Boolean): String {
        val email = pref.getString(EMAIL_PREF, "").orEmpty().trim()
        val password = pref.getString(PASSWORD_PREF, "").orEmpty()
        if (email.isBlank() || password.isBlank()) throw IOException("请先填写邮箱和密码")
        return login(email, password, updateUi)
    }

    private fun login(email: String, password: String, updateUi: Boolean): String {
        val body = json.encodeToString(LoginRequestDto.serializer(), LoginRequestDto(email, password))
            .toRequestBody(JSON_MEDIA_TYPE)
        var lastError = "登录失败"

        for (host in orderedLoginBases()) {
            for (path in LOGIN_PATHS) {
                val request = Request.Builder()
                    .url(host + path)
                    .headers(loginHeaders(host))
                    .post(body)
                    .build()
                val result = runCatching {
                    network.client.newCall(request).execute().use { response ->
                        val text = response.body.string()
                        val payload = runCatching { json.decodeFromString(LoginResponseDto.serializer(), text) }.getOrNull()
                        val token = payload?.token.orEmpty().trim()
                        if (response.isSuccessful && token.isNotBlank()) {
                            token
                        } else {
                            if (response.code !in listOf(404, 405)) {
                                lastError = payload?.message?.takeIf { it.isNotBlank() }
                                    ?: payload?.error?.takeIf { it.isNotBlank() }
                                    ?: "登录失败：HTTP ${response.code}"
                            }
                            ""
                        }
                    }
                }.getOrElse {
                    lastError = it.message ?: "网络请求失败"
                    ""
                }
                if (result.isNotBlank()) {
                    pref.edit()
                        .putString(TOKEN_PREF, result)
                        .putString(LOGIN_STATUS_PREF, "已登录：$email")
                        .apply()
                    if (updateUi) updateLoginStatus("已登录：$email")
                    return result
                }
            }
        }

        pref.edit().remove(TOKEN_PREF).putString(LOGIN_STATUS_PREF, "登录失败：$lastError").apply()
        if (updateUi) updateLoginStatus("登录失败：$lastError")
        throw IOException(lastError)
    }

    private fun orderedLoginBases(): List<String> = buildList {
        add(normalizedBaseUrl())
        KNOWN_BASE_URLS.filterTo(this) { it != normalizedBaseUrl() }
    }

    private fun loginHeaders(host: String): Headers = Headers.Builder()
        .set("Accept", "application/json, text/plain, */*")
        .set("Content-Type", "application/json")
        .set("Origin", host)
        .set("Referer", "$host/login")
        .build()

    private suspend fun OkHttpClient.query(body: RequestBody): Response {
        var response = post("$baseUrl/api/query", body)
        if (response.hasExpiredTokenError() && hasStoredCredentials()) {
            response.close()
            runCatching { loginWithStoredCredentials(updateUi = false) }
            response = post("$baseUrl/api/query", body)
        }
        return response
    }

    private fun Response.hasExpiredTokenError(): Boolean {
        if (!isSuccessful) return false
        val text = runCatching { peekBody(64 * 1024).string().lowercase() }.getOrDefault("")
        return "token is expired" in text || "no token" in text
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = AUTO_FALLBACK_PREF
            title = "自动故障切换"
            summary = "当前站点：${normalizedBaseUrl()}。网络失败、403、429 或服务器错误时自动尝试另一个 Komiic 站点"
            setDefaultValue(true)
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = CHAPTER_FILTER_PREF
            title = "章节列表显示"
            summary = "%s"
            entries = arrayOf("同时显示卷和章节", "仅显示章节", "仅显示卷")
            entryValues = arrayOf("all", "chapter", "book")
            setDefaultValue("all")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = EMAIL_PREF
            title = "登录邮箱"
            summary = pref.getString(EMAIL_PREF, "").orEmpty().ifBlank { "未填写" }
            setOnPreferenceChangeListener { preference, newValue ->
                preference.summary = newValue.toString().trim().ifBlank { "未填写" }
                clearSessionForCredentialChange()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PASSWORD_PREF
            title = "登录密码"
            summary = if (pref.getString(PASSWORD_PREF, "").isNullOrBlank()) "未填写" else "已填写"
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setOnPreferenceChangeListener { preference, newValue ->
                preference.summary = if (newValue.toString().isBlank()) "未填写" else "已填写"
                clearSessionForCredentialChange()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = LOGIN_ACTION_PREF
            title = "立即登录 / 检查登录"
            summary = pref.getString(LOGIN_STATUS_PREF, "未登录") ?: "未登录"
            loginStatusPreference = this
            setOnPreferenceClickListener {
                summary = "正在登录…"
                Thread {
                    val result = runCatching {
                        loginWithStoredCredentials(updateUi = true)
                        runCatching { refreshQuota(updateUi = true) }
                        "登录成功"
                    }.getOrElse { "登录失败：${it.message ?: "未知错误"}" }
                    mainHandler.post { summary = result }
                }.start()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = QUOTA_ACTION_PREF
            title = "今日图片额度（点击刷新）"
            summary = pref.getString(QUOTA_SUMMARY_PREF, "尚未检查") ?: "尚未检查"
            quotaPreference = this
            setOnPreferenceClickListener {
                summary = "正在读取额度…"
                Thread {
                    val result = runCatching { refreshQuota(updateUi = true) }
                        .getOrElse { "额度查询失败：${it.message ?: "未知错误"}" }
                    mainHandler.post { summary = result }
                }.start()
                true
            }
        }.also(screen::addPreference)
    }

    private fun clearSessionForCredentialChange() {
        pref.edit().remove(TOKEN_PREF).putString(LOGIN_STATUS_PREF, "账号信息已变更，请重新登录").apply()
        updateLoginStatus("账号信息已变更，请重新登录")
    }

    private fun updateLoginStatus(text: String) {
        mainHandler.post { loginStatusPreference?.summary = text }
    }

    private fun refreshQuota(updateUi: Boolean): String {
        val request = Request.Builder()
            .url("$baseUrl/api/query")
            .headers(headers)
            .post(imageLimitQuery())
            .build()
        val quota = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.parseGraphQLAs<ImageLimitDataDto>().getImageLimit
                ?: throw IOException("服务端未返回额度信息")
        }
        val summary = quota.toSummary()
        pref.edit().putString(QUOTA_SUMMARY_PREF, summary).apply()
        if (updateUi) mainHandler.post { quotaPreference?.summary = summary }
        return summary
    }

    private fun ImageLimitDto.toSummary(): String {
        val reset = formatResetTime(resetInSeconds)
        if (limit <= 0) return "已用 $usage · $reset"
        val remaining = (limit - usage).coerceAtLeast(0)
        return "已用 $usage / $limit · 剩余 $remaining · $reset"
    }

    private fun formatResetTime(seconds: Long): String {
        if (seconds <= 0) return "即将重置"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 -> "${hours}小时${minutes}分后重置"
            minutes > 0 -> "${minutes}分后重置"
            else -> "${seconds}秒后重置"
        }
    }

    private fun markQuotaExhausted() {
        val summary = "今日图片额度已用完，请登录账号或等待额度重置"
        pref.edit().putString(QUOTA_SUMMARY_PREF, summary).apply()
        mainHandler.post { quotaPreference?.summary = summary }
    }

    private val SManga.komiicId get() = url.substringAfterLast('/')
    private val SChapter.komiicId get() = url.substringAfterLast('/')

    private suspend fun mangasPage(page: Int, orderBy: OrderBy): MangasPage {
        val pagination = Pagination((page - 1) * PAGE_SIZE, orderBy)
        val response = client.query(commonQuery(ListingVariables(pagination)))
        return parseListing(response.parseGraphQLAs())
    }

    override suspend fun getPopularManga(page: Int) = mangasPage(page, OrderBy.MONTH_VIEWS)

    override suspend fun getLatestUpdates(page: Int) = mangasPage(page, OrderBy.DATE_UPDATED)

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?) = buildFilterList()

    override suspend fun getMangaByUrl(url: HttpUrl) = url.takeIf { url.pathSegments.firstOrNull() == "comic" }?.let {
        val response = client.query(idsQuery(listOf(url.pathSegments[1])))
        parseListing(response.parseGraphQLAs()).mangas.firstOrNull()
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val body = if (query.isNotBlank()) {
            searchQuery(query)
        } else {
            val variables = ListingVariables(Pagination((page - 1) * PAGE_SIZE))
            filters.filterIsInstance<KomiicFilter>().forEach { it.apply(variables) }
            listingQuery(variables)
        }
        return parseListing(client.query(body).parseGraphQLAs())
    }

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url + "/images/all"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.query(mangaQuery(manga.komiicId, fetchDetails, fetchChapters))
        val data = response.parseGraphQLAs<DataDto>()
        val sManga = if (fetchDetails) data.comicById!!.toSManga() else manga
        val sChapters = if (fetchChapters) {
            val raw = data.chaptersByComicId!!.toMutableList()
            when (pref.getString(CHAPTER_FILTER_PREF, "all")) {
                "chapter" -> raw.retainAll { it.type == "chapter" }
                "book" -> raw.retainAll { it.type == "book" }
            }
            raw.sortWith(compareByDescending<ChapterDto> { it.type }.thenByDescending { it.serial.toFloatOrNull() })
            raw.map { it.toSChapter(manga.url) }
        } else {
            chapters
        }
        return SMangaUpdate(sManga, sChapters)
    }

    override val supportsRelatedMangas get() = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val response = client.query(recommendQuery(manga.komiicId))
        val ids = response.parseGraphQLAs<DataDto>().recommendComicById.orEmpty()
        if (ids.isEmpty()) return emptyList()
        return parseListing(client.query(idsQuery(ids)).parseGraphQLAs()).mangas
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.query(pageListQuery(chapter.komiicId))
        val data = response.parseGraphQLAs<DataDto>()
        if (data.reachedImageLimit == true) {
            markQuotaExhausted()
            throw IOException("今日图片读取额度已用完，请登录账号或等待额度重置")
        }
        return data.imagesByChapterId.orEmpty().mapIndexed { index, image ->
            Page(index, baseUrl + "${chapter.url}/page/${index + 1}", "$baseUrl/api/image/${image.kid}")
        }
    }

    override fun imageRequest(page: Page) = super.imageRequest(page).newBuilder()
        .addHeader("Accept", "*/*")
        .header("Referer", page.url)
        .build()

    override val commentCapabilities = CommentCapabilities(
        supportsMangaComments = true,
        supportsChapterComments = false,
        canPost = false,
        canReply = false,
        canLike = false,
        requiresLoginToPost = false,
    )

    override suspend fun getMangaCommentTarget(manga: SManga) = CommentTarget(
        id = manga.komiicId,
        url = getMangaUrl(manga),
        kind = CommentTargetKind.MANGA,
    )

    override suspend fun getComments(target: CommentTarget, page: Int): CommentPage {
        val response = client.query(commentsQuery(target.id, page))
        val items = response.parseGraphQLAs<CommentDataDto>().getMessagesByComicId.orEmpty()
        val total = if (page == 1) {
            runCatching {
                client.query(commentCountQuery(target.id)).parseGraphQLAs<CommentDataDto>().messageCountByComicId
            }.getOrNull()
        } else {
            null
        }
        return CommentPage(
            comments = items.map(::toComment),
            hasNextPage = items.size == COMMENT_PAGE_SIZE,
            totalCount = total,
        )
    }

    override suspend fun getCommentReplies(target: CommentTarget, comment: Comment, page: Int): CommentPage {
        if (page > 1) return CommentPage(emptyList(), false)
        val response = client.query(commentRepliesQuery(comment.id))
        val items = response.parseGraphQLAs<CommentDataDto>().messageChan.orEmpty()
        return CommentPage(
            comments = items.map(::toComment),
            hasNextPage = false,
            totalCount = items.size.toLong(),
        )
    }

    private fun toComment(item: KomiicCommentDto): Comment {
        val rawTime = item.dateUpdated ?: item.dateCreated
        return Comment(
            id = item.id,
            author = CommentAuthor(
                id = item.account?.id,
                name = item.account?.nickname?.takeIf { it.isNotBlank() } ?: "Komiic 用户",
                avatarUrl = item.account?.profileImageUrl,
            ),
            content = item.message,
            createdAt = rawTime?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrDefault(0L) } ?: 0L,
            displayTime = rawTime,
            parentId = item.replyTo?.id,
        )
    }

    override suspend fun getSourceAccount(): SourceAccount? {
        var token = currentToken()
        if (token.isBlank() && hasStoredCredentials()) {
            token = runCatching { loginWithStoredCredentials(updateUi = false) }.getOrDefault("")
        }
        if (token.isBlank()) return null
        val email = pref.getString(EMAIL_PREF, "").orEmpty().trim()
        return SourceAccount(name = email.ifBlank { "Komiic 用户" })
    }

    companion object {
        private const val CHAPTER_FILTER_PREF = "chapter_filter"
        private const val AUTO_FALLBACK_PREF = "auto_mirror_fallback"
        private const val EMAIL_PREF = "login_email"
        private const val PASSWORD_PREF = "login_password"
        private const val TOKEN_PREF = "login_token"
        private const val LOGIN_STATUS_PREF = "login_status"
        private const val LOGIN_ACTION_PREF = "login_action"
        private const val QUOTA_ACTION_PREF = "image_quota_action"
        private const val QUOTA_SUMMARY_PREF = "image_quota_summary"
        private const val COMMENT_PAGE_SIZE = 100
        private const val TOKEN_REFRESH_WINDOW_MS = 3_600_000L

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val KNOWN_BASE_URLS = listOf("https://komiic.com", "https://komiic.cc")
        private val LOGIN_PATHS = listOf("/api/login", "/auth/login")
    }
}
