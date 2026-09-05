package eu.kanade.tachiyomi.extension.zh.zaimanhuaplus

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.mx.Comment
import eu.kanade.tachiyomi.source.mx.CommentPage
import eu.kanade.tachiyomi.source.mx.CommentSource
import eu.kanade.tachiyomi.source.mx.CommentTarget
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

@Source
abstract class ZaimanhuaPlus :
    HttpSource(),
    ConfigurableSource,
    CommentSource {
    override val supportsLatest = true

    private val mobileBaseUrl = "https://m.zaimanhua.com"
    private val apiUrl = "https://v4api.zaimanhua.com/app/v1"
    private val accountApiUrl = "https://account-api.zaimanhua.com/v1"
    private val pcApiUrl = "$baseUrl/api/v1/comic2"
    private val pcDetailUrl = "$pcApiUrl/comic/detail"
    private val tryLoginRegex = Regex("""$apiUrl|$pcApiUrl""")
    private val checkCanReadRegex = Regex("""$apiUrl/comic/chapter""")

    private val json by injectLazy<Json>()

    private val preferences: SharedPreferences = getPreferences()
    private val loginRunning = AtomicBoolean(false)
    private val clientId: String by lazy {
        preferences.getString(CLIENT_ID_PREF, "")
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().replace("-", "").also {
                preferences.edit().putString(CLIENT_ID_PREF, it).apply()
            }
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(::authIntercept)
        .addInterceptor(::imageRetryInterceptor)
        .addInterceptor(CommentsInterceptor)
        .rateLimit(5)
        .build()

    private val mxComments by lazy {
        ZaimanhuaMxComments(
            client = client,
            headersProvider = { apiHeaders },
            apiUrl = apiUrl,
            mobileBaseUrl = mobileBaseUrl,
        )
    }

    private val autoSign by lazy {
        ZaimanhuaAutoSign(
            client = network.client,
            preferences = preferences,
            baseHeaders = headers,
            accountApiUrl = accountApiUrl,
        )
    }

    private fun authIntercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val username = preferences.getString(USERNAME_PREF, "")!!
        val password = preferences.getString(PASSWORD_PREF, "")!!
        var token = preferences.getString(TOKEN_PREF, "")!!
        var hasTriedLogin = false
        val url = request.url.toString()

        if (url.contains(tryLoginRegex) && request.header("authorization") == null && username.isNotBlank() && password.isNotBlank()) {
            hasTriedLogin = true
            token = runCatching { getToken(username, password) }
                .onFailure { saveLoginStatus("登录失败：${it.message ?: it.javaClass.simpleName}") }
                .getOrDefault("")
            apiHeaders = apiHeaders.newBuilder().setToken(token).build()
            if (token.isNotBlank()) {
                preferences.edit().putString(TOKEN_PREF, token).apply()
                saveLoginStatus("登录成功")
                request = request.newBuilder().headers(apiHeaders).build()
            } else {
                preferences.edit().putString(TOKEN_PREF, "").apply()
            }
        }
        if (url.contains(tryLoginRegex) && token.isNotBlank()) {
            autoSign.runIfNeeded(token)
        }
        val response = chain.proceed(request)
        // Only intercept chapter api requests that need token
        if (!url.contains(checkCanReadRegex) && !url.startsWith(pcApiUrl)) return response

        if (url.contains(checkCanReadRegex)) {
            // If chapter can read, return directly
            val responseBody = response.peekBody(Long.MAX_VALUE).stringCompat(response.header("Content-Encoding"))
            val canRead = responseBody.parseAs<ResponseDto<DataWrapperDto<CanReadDto>>>().data.data?.canRead ?: true
            if (canRead) return response
        }

        if (!isValid(token) && !hasTriedLogin) {
            token = runCatching { getToken(username, password) }
                .onFailure { saveLoginStatus("登录失败：${it.message ?: it.javaClass.simpleName}") }
                .getOrDefault("")
            apiHeaders = apiHeaders.newBuilder().setToken(token).build()
            preferences.edit().putString(TOKEN_PREF, token).apply()
            if (token.isBlank()) return response
            saveLoginStatus("登录成功")
        } else if (request.header("authorization") == "Bearer $token") {
            return response
        }

        response.close()
        val authRequest = request.newBuilder().apply {
            header("authorization", "Bearer $token")
            cacheControl(CacheControl.FORCE_NETWORK)
        }.build()
        return chain.proceed(authRequest)
    }

    private fun Headers.Builder.setToken(token: String): Headers.Builder = apply {
        if (token.isNotBlank()) set("authorization", "Bearer $token")
    }

    private var apiHeaders = headersBuilder().setToken(preferences.getString(TOKEN_PREF, "")!!).build()

    private fun saveLoginStatus(status: String) {
        preferences.edit().putString(LAST_LOGIN_STATUS_PREF, status).apply()
    }

    private fun loginSummary(): String {
        val token = preferences.getString(TOKEN_PREF, "").orEmpty()
        val status = preferences.getString(LAST_LOGIN_STATUS_PREF, "尚未登录").orEmpty()
        return if (token.isBlank()) "当前状态：未登录\n$status" else "当前状态：已登录\n$status"
    }

    private fun accountUrl(path: String): HttpUrl = "$accountApiUrl$path".toHttpUrl().newBuilder()
        .addQueryParameter("platform", "android")
        .addQueryParameter("timestamp", (System.currentTimeMillis() / 1000).toString())
        .addQueryParameter("_v", ACCOUNT_APP_VERSION)
        .addQueryParameter("_c", ACCOUNT_APP_CHANNEL)
        .build()

    private fun accountHeaders(token: String = ""): Headers = headersBuilder()
        .set("Platform", "android")
        .set("X-Client-ID", clientId)
        .set("AppVersion", ACCOUNT_APP_VERSION)
        .set("BuildNumber", ACCOUNT_BUILD_NUMBER)
        .set("Channel", ACCOUNT_APP_CHANNEL)
        .set("Accept", "application/json, text/plain, */*")
        .set("Accept-Encoding", "identity")
        .setToken(token)
        .build()

    private fun isValid(token: String): Boolean {
        if (token.isBlank()) return false
        return runCatching {
            val response = network.client.newCall(
                GET(
                    accountUrl("/userInfo/get"),
                    accountHeaders(token),
                    cache = CacheControl.FORCE_NETWORK,
                ),
            ).execute().use { it.parseAs<SimpleResponseDto>() }
            response.errno == 0
        }.getOrDefault(false)
    }

    private fun getToken(username: String, password: String): String {
        if (username.isBlank() || password.isBlank()) throw IOException("用户名或密码不能为空")
        val passwordEncoded = MessageDigest.getInstance("MD5")
            .digest(password.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val formBody: RequestBody = FormBody.Builder()
            .add("username", username)
            .add("passwd", passwordEncoded)
            .build()
        val result = network.client.newCall(
            POST(
                accountUrl("/login/passwd").toString(),
                accountHeaders(),
                formBody,
            ),
        ).execute().use { response ->
            if (!response.isSuccessful) throw IOException("登录请求失败：HTTP ${response.code}")
            response.parseAs<LoginResponseDto>()
        }
        if (result.errno != null && result.errno != 0) {
            throw IOException(result.errmsg.ifBlank { "登录失败(${result.errno})" })
        }
        return result.data?.user?.token?.takeIf { it.isNotBlank() }
            ?: throw IOException("登录成功但服务端未返回 Token")
    }

    // Detail
    override fun getMangaUrl(manga: SManga): String = "$mobileBaseUrl/pages/comic/detail?id=${manga.url}"

    // path: "/comic/detail/mangaId"
    private fun getMangaUrl(id: String): HttpUrl = "$apiUrl/comic/detail/$id?_v=2.2.5#$id".toHttpUrl()
    override fun mangaDetailsRequest(manga: SManga): Request = GET(getMangaUrl(manga.url), apiHeaders)

    override fun mangaDetailsParse(response: Response): SManga {
        val result = response.parseAs<ResponseDto<DataWrapperDto<MangaDto>>>()
        if (result.errmsg.isNotBlank()) {
            throw Exception(result.errmsg)
        } else {
            return result.data.data!!.toSManga()
        }
    }

    // Chapter
    override fun chapterListRequest(manga: SManga): Request = GET(getMangaUrl(manga.url), apiHeaders.newBuilder().apply { set("Platform", "pc") }.build())

    private fun pcChapterListRequest(mangaId: String): Request = GET("$pcDetailUrl?id=$mangaId", apiHeaders.newBuilder().apply { set("Platform", "pc") }.build())

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = response.parseAs<ResponseDto<DataWrapperDto<ChapterDataDto>>>()
        if (result.errmsg.isNotBlank()) {
            throw Exception(result.errmsg)
        } else {
            val data = result.data.data!!
            if (response.request.url.toString().startsWith(apiUrl) && data.isHideChapter == 1 && data.canRead == true) {
                val mangaId = response.request.url.fragment!!
                response.close()
                return chapterListParse(client.newCall(pcChapterListRequest(mangaId)).execute())
            }
            if (data.chapterList.isNullOrEmpty()) {
                throw Exception("章节列表为空，用户权限不足或漫画不存在")
            }
            return data.parseChapterList()
        }
    }

    // PageList
    override fun getChapterUrl(chapter: SChapter): String {
        val (mangaId, chapterId) = chapter.url.split("/", limit = 2)
        return "$mobileBaseUrl/pages/comic/page?comic_id=$mangaId&chapter_id=$chapterId"
    }

    // path: "/comic/chapter/mangaId/chapterId"
    private fun pageListApiRequest(path: String): Request = GET("$apiUrl/comic/chapter/$path?_v=2.2.5", apiHeaders.newBuilder().apply { set("Platform", "h5") }.build(), USE_CACHE)

    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val response = client.newCall(pageListApiRequest(chapter.url)).execute()
        val result = response.parseAs<ResponseDto<DataWrapperDto<ChapterImagesDto>>>()
        if (result.errmsg.isNotBlank()) {
            throw Exception(result.errmsg)
        } else {
            if (!result.data.data!!.canRead) {
                throw Exception("用户权限不足，请提升用户等级")
            }
            return Observable.fromCallable {
                val images = result.data.data.images
                val pageList = images.mapIndexedTo(ArrayList(images.size + 1)) { index, it ->
                    val fragment = json.encodeToString(ImageRetryParamsDto(chapter.url, index))
                    Page(index, imageUrl = "$it#$fragment")
                }
                if (preferences.getBoolean(COMMENTS_PREF, false)) {
                    val (mangaId, chapterId) = chapter.url.split("/", limit = 2)
                    pageList.add(Page(pageList.size, COMMENTS_FLAG, chapterCommentsUrl(mangaId, chapterId)))
                }
                pageList
            }
        }
    }

    private fun imageRetryInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val fragment = request.url.fragment
        if (response.isSuccessful || request.tag(String::class) != IMAGE_RETRY_FLAG || fragment == null) return response
        response.close()

        val params = json.decodeFromString<ImageRetryParamsDto>(fragment)
        val pageListResponse = client.newCall(pageListApiRequest(params.url)).execute()
        val result = pageListResponse.parseAs<ResponseDto<DataWrapperDto<ChapterImagesDto>>>()
        if (result.errmsg.isNotBlank()) {
            throw IOException(result.errmsg)
        } else {
            val imageUrl = result.data.data!!.images[params.index]
            return chain.proceed(GET(imageUrl, headers))
        }
    }

    override fun imageRequest(page: Page): Request {
        val flag = if (page.url == COMMENTS_FLAG) COMMENTS_FLAG else IMAGE_RETRY_FLAG
        val reqHeaders = if (page.url == COMMENTS_FLAG) apiHeaders else headers
        return GET(page.imageUrl!!, reqHeaders).newBuilder()
            .tag(String::class, flag)
            .build()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // Popular
    private fun rankApiUrl(): HttpUrl.Builder = "$apiUrl/comic/rank/list".toHttpUrl().newBuilder()
        .addQueryParameter("tag_id", "0")

    override fun popularMangaRequest(page: Int): Request = GET(
        rankApiUrl().apply {
            addQueryParameter("page", page.toString())
        }.build(),
        apiHeaders,
    )

    private fun genreApiUrl(): HttpUrl.Builder = "$apiUrl/comic/filter/list".toHttpUrl().newBuilder()
        .addQueryParameter("size", DEFAULT_PAGE_SIZE.toString())

    override fun popularMangaParse(response: Response): MangasPage = latestUpdatesParse(response)

    // Search
    private fun searchApiUrl(): HttpUrl.Builder = "$apiUrl/search/index".toHttpUrl().newBuilder().addQueryParameter("source", "0")
        .addQueryParameter("size", DEFAULT_PAGE_SIZE.toString())

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val ranking = filters.firstInstanceOrNull<RankingGroup>()
        val genres = filters.firstInstanceOrNull<GenreGroup>()
        val searchById = filters.firstInstanceOrNull<SearchByIdFilter>()?.state ?: false
        val url = when {
            query.isEmpty() && ranking != null && (ranking.state[0] as TimeFilter).state != 0 -> rankApiUrl().apply {
                ranking.state.filterIsInstance<QueryFilter>().forEach { it.addQuery(this) }
                addQueryParameter("page", page.toString())
            }.build()

            query.isEmpty() && genres != null -> genreApiUrl().apply {
                genres.state.filterIsInstance<QueryFilter>().forEach { it.addQuery(this) }
                addQueryParameter("page", page.toString())
            }.build()

            query.isNotBlank() && searchById && query.toIntOrNull()?.let { it > 0 } ?: false -> getMangaUrl(query)

            else -> searchApiUrl().apply {
                addQueryParameter("keyword", query)
                addQueryParameter("page", page.toString())
            }.build()
        }
        return GET(url, apiHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val url = response.request.url
        return if (url.toString().startsWith("$apiUrl/comic/rank/list")) {
            latestUpdatesParse(response)
        } else if (url.toString().startsWith("$apiUrl/comic/detail")) {
            MangasPage(listOf(mangaDetailsParse(response)), false)
        } else {
            // "$apiUrl/comic/filter/list" or "$apiUrl/search/index"
            response.parseAs<ResponseDto<PageDto>>().data.toMangasPage(url.queryParameter("page")!!.toInt())
        }
    }

    // Latest
    // "$apiUrl/comic/update/list/1/$page" is same content
    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/comic/update/list/0/$page", apiHeaders)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val mangas = response.parseAs<ResponseDto<List<PageItemDto>?>>().data
        if (mangas.isNullOrEmpty()) {
            throw Exception("没有更多结果了")
        }
        return MangasPage(mangas.map { it.toSManga() }, true)
    }

    override fun getFilterList() = FilterList(
        SearchByIdFilter(),
        RankingGroup(),
        Filter.Separator(),
        Filter.Header("分类(搜索/查看排行榜时无效)"),
        GenreGroup(),
    )

    private fun chapterCommentsUrl(comicId: String, chapterId: String) = "$apiUrl/viewpoint/list?comicId=$comicId&chapterId=$chapterId"

    override val commentCapabilities
        get() = mxComments.capabilities

    override suspend fun getMangaCommentTarget(manga: SManga) = mxComments.getMangaTarget(manga)

    override suspend fun getChapterCommentTarget(manga: SManga, chapter: SChapter) = mxComments.getChapterTarget(manga, chapter)

    override suspend fun getComments(target: CommentTarget, page: Int): CommentPage = mxComments.getComments(target, page)

    override suspend fun getCommentReplies(
        target: CommentTarget,
        comment: Comment,
        page: Int,
    ): CommentPage = mxComments.getReplies(comment, page)

    companion object {
        val USE_CACHE = CacheControl.Builder().maxStale(170.seconds).build()
        const val USERNAME_PREF = "USERNAME"
        const val PASSWORD_PREF = "PASSWORD"
        const val TOKEN_PREF = "TOKEN"
        const val CLIENT_ID_PREF = "CLIENT_ID"
        const val LAST_LOGIN_STATUS_PREF = "LAST_LOGIN_STATUS"
        const val ACCOUNT_APP_VERSION = "2.3.7"
        const val ACCOUNT_BUILD_NUMBER = "1502277"
        const val ACCOUNT_APP_CHANNEL = "101_01_01_000"
        const val COMMENTS_PREF = "COMMENTS"
        const val COMMENTS_FLAG = "COMMENTS"
        const val IMAGE_RETRY_FLAG = "IMAGE_RETRY"
        const val DEFAULT_PAGE_SIZE = 20
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            SwitchPreferenceCompat(screen.context).apply {
                key = COMMENTS_PREF
                title = "章末吐槽页"
                summary = "修改后，已加载的章节需要清除章节缓存才能生效。"
                setDefaultValue(false)
            }.let(screen::addPreference)

            SwitchPreferenceCompat(screen.context).apply {
                key = AUTO_SIGN_PREF
                title = "自动签到"
                summary = "登录后每天首次使用再漫画 Plus 时自动检查并签到"
                setDefaultValue(true)
            }.let(screen::addPreference)

            EditTextPreference(screen.context).apply {
                title = "签到状态"
                summary = preferences.getString(LAST_SIGN_STATUS_PREF, "尚未检查")
                setEnabled(false)
            }.let(screen::addPreference)

            EditTextPreference(screen.context).apply {
                key = USERNAME_PREF
                title = "用户名"
                summary = "该配置被修改后，会清空令牌(Token)以便重新登录；如果登录失败，会清空该配置"
                setOnPreferenceChangeListener { _, _ ->
                    preferences.edit()
                        .putString(TOKEN_PREF, "")
                        .putString(LAST_LOGIN_STATUS_PREF, "登录信息已修改，请点击“立即登录 / 检查登录”")
                        .apply()
                    apiHeaders = apiHeaders.newBuilder().setToken("").build()
                    true
                }
            }.let(screen::addPreference)

            EditTextPreference(screen.context).apply {
                key = PASSWORD_PREF
                title = "密码"
                summary = "该配置被修改后，会清空令牌(Token)以便重新登录；如果登录失败，会清空该配置"
                setOnPreferenceChangeListener { _, _ ->
                    preferences.edit()
                        .putString(TOKEN_PREF, "")
                        .putString(LAST_LOGIN_STATUS_PREF, "登录信息已修改，请点击“立即登录 / 检查登录”")
                        .apply()
                    apiHeaders = apiHeaders.newBuilder().setToken("").build()
                    true
                }
            }.let(screen::addPreference)

            EditTextPreference(screen.context).apply {
                key = "LOGIN_ACTION"
                title = "立即登录 / 检查登录"
                summary = loginSummary()
                setOnPreferenceClickListener { preference ->
                    if (!loginRunning.compareAndSet(false, true)) return@setOnPreferenceClickListener true
                    preference.setEnabled(false)
                    preference.summary = "正在登录…"
                    Thread {
                        val status = runCatching {
                            val username = preferences.getString(USERNAME_PREF, "").orEmpty()
                            val password = preferences.getString(PASSWORD_PREF, "").orEmpty()
                            val token = getToken(username, password)
                            preferences.edit().putString(TOKEN_PREF, token).apply()
                            apiHeaders = apiHeaders.newBuilder().setToken(token).build()
                            saveLoginStatus("登录成功")
                            autoSign.runIfNeeded(token)
                            "当前状态：已登录\n登录成功"
                        }.getOrElse {
                            preferences.edit().putString(TOKEN_PREF, "").apply()
                            val message = "登录失败：${it.message ?: it.javaClass.simpleName}"
                            saveLoginStatus(message)
                            "当前状态：未登录\n$message"
                        }
                        Handler(Looper.getMainLooper()).post {
                            preference.summary = status
                            preference.setEnabled(true)
                            loginRunning.set(false)
                        }
                    }.start()
                    true
                }
            }.let(screen::addPreference)

            EditTextPreference(screen.context).apply {
                key = "LOGIN_STATUS_VIEW"
                title = "登录状态"
                summary = loginSummary()
                setEnabled(false)
            }.let(screen::addPreference)
        }
    }
}
