package eu.kanade.tachiyomi.extension.zh.dongmanmanhuaplus

import android.os.Handler
import android.os.Looper
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.mx.AccountSource
import eu.kanade.tachiyomi.source.mx.ChapterContentReplacementCapabilities
import eu.kanade.tachiyomi.source.mx.ChapterContentReplacementSource
import eu.kanade.tachiyomi.source.mx.Comment
import eu.kanade.tachiyomi.source.mx.CommentAuthor
import eu.kanade.tachiyomi.source.mx.CommentCapabilities
import eu.kanade.tachiyomi.source.mx.CommentPage
import eu.kanade.tachiyomi.source.mx.CommentSource
import eu.kanade.tachiyomi.source.mx.CommentTarget
import eu.kanade.tachiyomi.source.mx.CommentTargetKind
import eu.kanade.tachiyomi.source.mx.SourceAccount
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Calendar
import java.util.Locale
import javax.crypto.Cipher

@Source
abstract class DongmanManhuaPlus :
    KeiSource(),
    ConfigurableSource,
    CommentSource,
    AccountSource,
    ChapterContentReplacementSource {

    private val pref by getPreferencesLazy()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val dateFormat = SimpleDateFormat("yyyy-M-d", Locale.ENGLISH)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var loginStatusPreference: EditTextPreference? = null

    override fun Headers.Builder.configureHeaders() = apply {
        set("Accept-Language", "zh-CN,zh;q=0.9")
    }

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor(::appAuthInterceptor)
    }

    private fun appAuthInterceptor(chain: Interceptor.Chain): Response {
        var request = chain.request()
        if (request.url.host == API_HOST) {
            val token = currentToken()
            if (token.isNotBlank() && request.header("Authorization").isNullOrBlank()) {
                request = request.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            }
        }
        return chain.proceed(request)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = ACCOUNT_PREF
            title = "咚漫账号（邮箱 / ID）"
            summary = pref.getString(ACCOUNT_PREF, "").orEmpty().ifBlank { "未填写" }
            setOnPreferenceChangeListener { preference, value ->
                preference.summary = value.toString().trim().ifBlank { "未填写" }
                clearLoginSession()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PASSWORD_PREF
            title = "密码"
            summary = if (pref.getString(PASSWORD_PREF, "").isNullOrBlank()) "未填写" else "已填写"
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setOnPreferenceChangeListener { preference, value ->
                preference.summary = if (value.toString().isBlank()) "未填写" else "已填写"
                clearLoginSession()
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
                        val token = currentToken().takeIf { it.isNotBlank() && validateToken(it) }
                            ?: loginWithStoredCredentials()
                        val account = fetchAccount(token)
                        if (account != null) "已登录：${account.name}" else "登录成功"
                    }.getOrElse { "登录失败：${it.message ?: "未知错误"}" }
                    pref.edit().putString(LOGIN_STATUS_PREF, result).apply()
                    mainHandler.post { summary = result }
                }.start()
                true
            }
        }.also(screen::addPreference)
    }

    private fun clearLoginSession() {
        pref.edit()
            .remove(TOKEN_PREF)
            .putString(LOGIN_STATUS_PREF, "账号信息已变更，请重新登录")
            .apply()
        mainHandler.post { loginStatusPreference?.summary = "账号信息已变更，请重新登录" }
    }

    private fun currentToken(): String = pref.getString(TOKEN_PREF, "").orEmpty().trim()

    private fun loginWithStoredCredentials(): String {
        val account = pref.getString(ACCOUNT_PREF, "").orEmpty().trim()
        val password = pref.getString(PASSWORD_PREF, "").orEmpty()
        if (account.isBlank() || password.isBlank()) throw IOException("请先填写账号和密码")

        val attempts = buildList {
            add(LoginAttempt("/app/member/login/email", mapOf("email" to account, "password" to password)))
            add(LoginAttempt("/app/member/id/login", mapOf("userId" to account, "password" to password)))
            add(LoginAttempt("/app/member/id/login", mapOf("neoId" to account, "password" to password)))
            rsaLoginAttempt(account, password)?.let(::add)
        }
        var lastError = "服务器未返回登录令牌"
        for (attempt in attempts) {
            for (asJson in listOf(true, false)) {
                val result = runCatching { executeLogin(attempt, asJson) }
                val token = result.getOrNull().orEmpty()
                if (token.isNotBlank()) {
                    pref.edit().putString(TOKEN_PREF, token).apply()
                    return token
                }
                result.exceptionOrNull()?.message?.takeIf(String::isNotBlank)?.let { lastError = it }
            }
        }
        throw IOException(lastError)
    }

    private fun executeLogin(attempt: LoginAttempt, asJson: Boolean): String {
        val body = if (asJson) {
            JsonObject(attempt.fields.mapValues { JsonPrimitive(it.value) })
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)
        } else {
            FormBody.Builder().apply {
                attempt.fields.forEach { (key, value) -> add(key, value) }
            }.build()
        }
        val request = Request.Builder()
            .url(API_BASE + attempt.path)
            .headers(appHeaders())
            .post(body)
            .build()
        return network.client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw IOException(readServerMessage(text) ?: "HTTP ${response.code}")
            }
            parseJson(text)?.firstString("accessToken", "access_token", "token", "jwt").orEmpty()
        }
    }

    private fun rsaLoginAttempt(account: String, password: String): LoginAttempt? = runCatching {
        val request = Request.Builder()
            .url("$API_BASE/app/rsakey/get")
            .headers(appHeaders())
            .get()
            .build()
        val root = network.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            parseJson(response.body.string()) ?: return@runCatching null
        }
        val keyName = root.firstString("keyName", "encnm") ?: return@runCatching null
        val publicKey = root.firstString("publicKey", "key")
        val nValue = root.firstString("nValue", "nvalue", "modulus")
        val eValue = root.firstString("eValue", "evalue", "exponent")
        val sessionKey = root.firstString("sessionKey").orEmpty()
        val packed = buildString {
            append(sessionKey.length.toChar())
            append(sessionKey)
            append(account.length.toChar())
            append(account)
            append(password.length.toChar())
            append(password)
        }.encodeToByteArray()
        val encrypted = encryptRsa(publicKey, nValue, eValue, packed) ?: return@runCatching null
        LoginAttempt("/app/member/id/login", mapOf("encnm" to keyName, "encpw" to encrypted))
    }.getOrNull()

    private fun encryptRsa(
        publicKeyText: String?,
        nValue: String?,
        eValue: String?,
        data: ByteArray,
    ): String? = runCatching {
        val factory = KeyFactory.getInstance("RSA")
        val key = if (!nValue.isNullOrBlank() && !eValue.isNullOrBlank()) {
            factory.generatePublic(
                RSAPublicKeySpec(
                    BigInteger(nValue.removePrefix("0x"), 16),
                    BigInteger(eValue.removePrefix("0x"), 16),
                ),
            )
        } else {
            val normalized = publicKeyText.orEmpty()
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace(Regex("\\s+"), "")
            factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(normalized)))
        }
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.doFinal(data).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }.getOrNull()

    private fun validateToken(token: String): Boolean = runCatching {
        val request = Request.Builder()
            .url("$API_BASE/app/member/checkToken")
            .headers(appHeaders().newBuilder().set("Authorization", "Bearer $token").build())
            .get()
            .build()
        network.client.newCall(request).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun fetchAccount(token: String): SourceAccount? = runCatching {
        val request = Request.Builder()
            .url("$API_BASE/app/member/getProfile")
            .headers(appHeaders().newBuilder().set("Authorization", "Bearer $token").build())
            .get()
            .build()
        network.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            parseJson(response.body.string())?.let(::accountFromJson)
        }
    }.getOrNull()

    private fun accountFromJson(root: JsonElement): SourceAccount? {
        val obj = root.objects().firstOrNull {
            it.stringAny("nickName", "nickname", "userName", "name", "email") != null
        } ?: return null
        val name = obj.stringAny("nickName", "nickname", "userName", "name", "email") ?: return null
        return SourceAccount(
            id = obj.stringAny("memberNo", "userNo", "userId", "id"),
            name = name,
            avatarUrl = obj.stringAny("profileImage", "profileImageUrl", "avatarUrl"),
            profileUrl = "$baseUrl/my",
        )
    }

    private fun appHeaders(): Headers = Headers.Builder()
        .set("Accept", "application/json")
        .set("User-Agent", APP_UA)
        .set("Referer", "$baseUrl/")
        .build()

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val document = client.get("$baseUrl/dailySchedule").asJsoup()
        val entries = document
            .select("div#dailyList .daily_section li a, div.daily_lst.comp li a, .daily_section li a")
            .mapNotNull(::mangaFromElement)
            .distinctBy { it.url }
        return MangasPage(entries, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val document = client.get("$baseUrl/dailySchedule?sortOrder=UPDATE&webtoonCompleteType=ONGOING").asJsoup()
        val day = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> "div._list_SUNDAY"
            Calendar.MONDAY -> "div._list_MONDAY"
            Calendar.TUESDAY -> "div._list_TUESDAY"
            Calendar.WEDNESDAY -> "div._list_WEDNESDAY"
            Calendar.THURSDAY -> "div._list_THURSDAY"
            Calendar.FRIDAY -> "div._list_FRIDAY"
            Calendar.SATURDAY -> "div._list_SATURDAY"
            else -> "div"
        }
        val entries = document
            .select("div#dailyList > $day li > a, div#dailyList li > a")
            .mapNotNull(::mangaFromElement)
            .distinctBy { it.url }
        return MangasPage(entries, false)
    }

    private fun mangaFromElement(element: Element): SManga? {
        val href = element.absUrl("href").ifBlank { element.attr("href") }
        val title = element.selectFirst("p.subj, .subj, .title")?.text()?.trim().orEmpty()
        if (href.isBlank() || title.isBlank()) return null
        return SManga.create().apply {
            url = relativeWebUrl(href)
            this.title = title
            thumbnail_url = element.selectFirst("img")?.let {
                it.absUrl("src").ifBlank { it.attr("data-src") }
            }
        }
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("keyword", query)
            .apply { if (page > 1) addQueryParameter("page", page.toString()) }
            .build()
        val document = client.get(url).asJsoup()
        val entries = document
            .select("#content > div.card_wrap.search ul:not(#filterLayer) li a, .card_wrap.search li a")
            .mapNotNull(::mangaFromElement)
        val hasNext = document.selectFirst("div.more_area, div.paginate a[onclick] + a") != null
        return MangasPage(entries, hasNext)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!url.host.endsWith("dongmanmanhua.cn")) return null
        val manga = SManga.create().apply { this.url = relativeWebUrl(url.toString()) }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(absoluteWebUrl(manga.url)).asJsoup()
        val updatedManga = if (fetchDetails) parseMangaDetails(document, manga) else manga
        val updatedChapters = if (fetchChapters) parseAllChapters(document, manga) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(document: Document, fallback: SManga): SManga {
        val detail = document.selectFirst(".detail_header .info")
        val aside = document.selectFirst("#_asideDetail")
        return SManga.create().apply {
            url = fallback.url
            title = document.selectFirst("h1.subj, h3.subj, .detail_header .subj")
                ?.text()?.trim().orEmpty().ifBlank { fallback.title }
            author = detail?.selectFirst(".author:nth-of-type(1), .author_area")?.ownText()?.trim()
            artist = detail?.selectFirst(".author:nth-of-type(2), .author_area")?.ownText()?.trim() ?: author
            genre = detail?.select(".genre")?.joinToString { it.text() }
            description = aside?.selectFirst("p.summary")?.text()?.trim()
                ?: document.selectFirst(".detail_body .summary, .summary")?.text()?.trim()
            status = with(aside?.selectFirst("p.day_info")?.text().orEmpty()) {
                when {
                    contains("更新") -> SManga.ONGOING
                    contains("完结") -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
            }
            thumbnail_url = document.selectFirst(".detail_header .thmb img, .detail_body img")?.let {
                it.absUrl("src").ifBlank { it.attr("src") }
            }
            initialized = true
        }
    }

    private suspend fun parseAllChapters(firstDocument: Document, manga: SManga): List<SChapter> {
        val titleNo = titleNoOf(manga.url) ?: titleNoOf(firstDocument.location())
        val web = mutableListOf<SChapter>()
        var document = firstDocument
        val visited = mutableSetOf<String>()
        while (true) {
            document.select("ul#_listUl li, .episode_lst li, .episode_list li")
                .mapNotNullTo(web) { chapterFromElement(it, titleNo) }
            val next = document.selectFirst("div.paginate a[onclick] + a")?.absUrl("href").orEmpty()
            if (next.isBlank() || !visited.add(next)) break
            document = client.get(next).asJsoup()
        }

        val appItems = titleNo?.let {
            runCatching { fetchEpisodeMetadata(it) }.getOrDefault(emptyList())
        }.orEmpty()
        val webByNo = web.mapNotNull { chapter ->
            episodeNoOf(chapter.url)?.let { it to chapter }
        }.toMap()
        val appEpisodeNos = appItems.mapNotNull { it.longAny("episodeNo")?.toString() }.toSet()

        val merged = if (appItems.isNotEmpty() && titleNo != null) {
            appItems.mapNotNull { item ->
                val episodeNo = item.longAny("episodeNo", "episode_no")?.toString() ?: return@mapNotNull null
                val existing = webByNo[episodeNo]
                val rawName = item.stringAny("episodeTitle", "episodeName", "episodeTitleName", "name")
                    ?.takeIf(String::isNotBlank)
                    ?: existing?.name?.removePrefix(LOCK_PREFIX)
                    ?: "第${episodeNo}话"
                val locked = item.isLockedEpisode()
                (existing ?: SChapter.create()).apply {
                    name = if (locked) LOCK_PREFIX + rawName.removePrefix(LOCK_PREFIX) else rawName.removePrefix(LOCK_PREFIX)
                    url = existing?.url ?: viewerRelativeUrl(titleNo, episodeNo)
                    chapter_number = rawName.extractChapterNumber() ?: episodeNo.toFloatOrNull() ?: -1f
                    if (date_upload == 0L) {
                        date_upload = item.stringAny("serviceDate", "publishDate", "registerDate")
                            ?.let(dateFormat::tryParse) ?: 0L
                    }
                }
            }
        } else {
            web
        }

        val extras = web.filter { episodeNoOf(it.url) !in appEpisodeNos }
        return (merged + extras)
            .distinctBy { episodeNoOf(it.url) ?: it.url }
            .sortedWith(compareByDescending<SChapter> { it.chapter_number }.thenByDescending { it.date_upload })
    }

    private fun chapterFromElement(element: Element, titleNo: String?): SChapter? {
        val link = element.selectFirst("a[href]") ?: return null
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        val episodeNo = episodeNoOf(href)
            ?: element.attr("data-episode-no").takeIf(String::isNotBlank)
            ?: element.selectFirst("[data-episode-no]")?.attr("data-episode-no")?.takeIf(String::isNotBlank)
        var rawName = element.selectFirst("span.subj span, span.subj, .subj, .episode_title")
            ?.text()?.trim() ?: link.text().trim()
        if (rawName.isBlank()) return null
        val webLocked = element.selectFirst(".ico_lock, .lock, [class*=lock]") != null ||
            element.text().contains("付费")
        if (webLocked) rawName = LOCK_PREFIX + rawName.removePrefix(LOCK_PREFIX)
        return SChapter.create().apply {
            name = rawName
            url = when {
                href.isNotBlank() -> relativeWebUrl(href)
                titleNo != null && episodeNo != null -> viewerRelativeUrl(titleNo, episodeNo)
                else -> return null
            }
            chapter_number = rawName.removePrefix(LOCK_PREFIX).extractChapterNumber()
                ?: episodeNo?.toFloatOrNull() ?: -1f
            date_upload = dateFormat.tryParse(element.selectFirst("span.date, .date")?.text())
        }
    }

    private suspend fun fetchEpisodeMetadata(titleNo: String): List<JsonObject> {
        val url = API_BASE.toHttpUrl().newBuilder()
            .addPathSegments("app/episode/list/v6")
            .addQueryParameter("titleNo", titleNo)
            .addQueryParameter("pageNo", "1")
            .addQueryParameter("pageSize", "999")
            .build()
        val response = client.get(url, appHeaders(), ensureSuccess = false)
        if (!response.isSuccessful) {
            response.close()
            return fetchEpisodeMetadataPost(titleNo)
        }
        val root = parseJson(response.body.string()) ?: return emptyList()
        return root.objects()
            .filter { it.longAny("episodeNo") != null }
            .distinctBy { it.longAny("episodeNo") }
            .toList()
    }

    private suspend fun fetchEpisodeMetadataPost(titleNo: String): List<JsonObject> {
        val body = JsonObject(
            mapOf(
                "titleNo" to jsonNumberOrString(titleNo),
                "pageNo" to JsonPrimitive(1),
                "pageSize" to JsonPrimitive(999),
            ),
        ).toString().toRequestBody(JSON_MEDIA_TYPE)
        val response = client.post(
            "$API_BASE/app/episode/list/v6",
            appHeaders(),
            body,
            ensureSuccess = false,
        )
        if (!response.isSuccessful) {
            response.close()
            return emptyList()
        }
        val root = parseJson(response.body.string()) ?: return emptyList()
        return root.objects()
            .filter { it.longAny("episodeNo") != null }
            .distinctBy { it.longAny("episodeNo") }
            .toList()
    }

    private fun JsonObject.isLockedEpisode(): Boolean {
        if (boolAny("purchased", "hasPurchased", "purchase") == true) return false
        if (boolAny("borrowed", "hasBorrowed") == true) return false
        if (boolAny("free", "isFree", "freeLimited") == true) return false
        val status = stringAny("serviceStatus", "episodeServiceStatus").orEmpty().uppercase(Locale.ROOT)
        if (status in setOf("UNLOCKED", "FREE", "PURCHASED", "BORROW", "BORROWED_END")) return false
        if (status in setOf("LOCKED", "CHARGE")) return true
        if (boolAny("isPaid", "paid", "episodeIsPaid") == true) return true
        return (doubleAny("price", "purchase_price", "coinNumber", "ticketNumber") ?: 0.0) > 0.0
    }

    override fun getMangaUrl(manga: SManga): String = absoluteWebUrl(manga.url)

    override fun getChapterUrl(chapter: SChapter): String = absoluteWebUrl(chapter.url)

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val webResponse = client.get(absoluteWebUrl(chapter.url), ensureSuccess = false)
        if (webResponse.isSuccessful) {
            val document = webResponse.asJsoup()
            val pages = document.select("div#_imageList > img, #_imageList img")
                .mapNotNull {
                    it.attr("data-url").ifBlank { it.absUrl("src") }.takeIf(String::isNotBlank)
                }
                .distinct()
            if (pages.isNotEmpty()) {
                return pages.mapIndexed { index, url -> Page(index, imageUrl = url) }
            }
        } else {
            webResponse.close()
        }

        val titleNo = titleNoOf(chapter.url)
        val episodeNo = episodeNoOf(chapter.url)
        if (titleNo != null && episodeNo != null) {
            val official = fetchOfficialEpisodePages(titleNo, episodeNo)
            if (official.isNotEmpty()) {
                return official.mapIndexed { index, url -> Page(index, imageUrl = url) }
            }
        }
        throw IOException("正文未返回图片。若这是付费章节，请先在咚漫官方账号中解锁/购买，或使用 MX 的章节正文替换功能。")
    }

    private suspend fun fetchOfficialEpisodePages(titleNo: String, episodeNo: String): List<String> {
        val url = API_BASE.toHttpUrl().newBuilder()
            .addPathSegments("app/episode/info/v5")
            .addQueryParameter("titleNo", titleNo)
            .addQueryParameter("episodeNo", episodeNo)
            .build()
        var response = client.get(url, appHeaders(), ensureSuccess = false)
        if (!response.isSuccessful) {
            response.close()
            val body = JsonObject(
                mapOf(
                    "titleNo" to jsonNumberOrString(titleNo),
                    "episodeNo" to jsonNumberOrString(episodeNo),
                ),
            ).toString().toRequestBody(JSON_MEDIA_TYPE)
            response = client.post(
                "$API_BASE/app/episode/info/v5",
                appHeaders(),
                body,
                ensureSuccess = false,
            )
        }
        if (!response.isSuccessful) {
            response.close()
            return emptyList()
        }
        val root = parseJson(response.body.string()) ?: return emptyList()
        return buildList {
            root.objects().forEach { obj ->
                (obj["imageUrlArray"] as? JsonArray)?.forEach {
                    (it as? JsonPrimitive)?.contentOrNull?.let(::add)
                }
                obj.stringAny("imageUrl", "image_url")?.let(::add)
            }
        }.filter { it.startsWith("http://") || it.startsWith("https://") }.distinct()
    }

    override val commentCapabilities = CommentCapabilities(
        supportsMangaComments = false,
        supportsChapterComments = true,
        canPost = false,
        canReply = false,
        canLike = false,
        requiresLoginToPost = true,
    )

    override suspend fun getMangaCommentTarget(manga: SManga): CommentTarget = throw UnsupportedOperationException("咚漫画 Plus 当前只开放章评")

    override suspend fun getChapterCommentTarget(manga: SManga, chapter: SChapter): CommentTarget {
        val titleNo = titleNoOf(chapter.url) ?: titleNoOf(manga.url) ?: throw IOException("无法解析作品编号")
        val episodeNo = episodeNoOf(chapter.url) ?: throw IOException("无法解析章节编号")
        return CommentTarget("$titleNo:$episodeNo", getChapterUrl(chapter), CommentTargetKind.CHAPTER)
    }

    override suspend fun getComments(target: CommentTarget, page: Int): CommentPage {
        require(target.kind == CommentTargetKind.CHAPTER) { "仅支持章评" }
        val parts = target.id.split(':', limit = 2)
        val titleNo = parts.first()
        val episodeNo = parts.getOrElse(1) { "" }
        if (episodeNo.isBlank()) throw IOException("无效章评目标")
        val root = fetchCommentJson("/v2/comment", titleNo, episodeNo, page)
        val comments = root.toComments(parentId = null)
        return CommentPage(
            comments = comments,
            hasNextPage = comments.size >= COMMENT_PAGE_SIZE,
            totalCount = root.firstLong("commentCount", "totalCount", "total"),
        )
    }

    override suspend fun getCommentReplies(
        target: CommentTarget,
        comment: Comment,
        page: Int,
    ): CommentPage {
        val candidates = listOf(
            "/v1/comment_reply/${comment.id}?pageNo=${page.coerceAtLeast(1)}&pageSize=$COMMENT_PAGE_SIZE",
            "/v1/comment/${comment.id}",
        )
        for (path in candidates) {
            val response = runCatching {
                client.get(API_BASE + path, appHeaders(), ensureSuccess = false)
            }.getOrNull() ?: continue
            if (!response.isSuccessful) {
                response.close()
                continue
            }
            val root = parseJson(response.body.string()) ?: continue
            val replies = root.toComments(parentId = comment.id).filter { it.id != comment.id }
            if (replies.isNotEmpty()) {
                return CommentPage(replies, replies.size >= COMMENT_PAGE_SIZE, comment.replyCount)
            }
        }
        return CommentPage(emptyList(), false, comment.replyCount)
    }

    private suspend fun fetchCommentJson(
        path: String,
        titleNo: String,
        episodeNo: String,
        page: Int,
    ): JsonElement {
        val url = (API_BASE + path).toHttpUrl().newBuilder()
            .addQueryParameter("titleNo", titleNo)
            .addQueryParameter("episodeNo", episodeNo)
            .addQueryParameter("pageNo", page.coerceAtLeast(1).toString())
            .addQueryParameter("pageSize", COMMENT_PAGE_SIZE.toString())
            .build()
        var response = client.get(url, appHeaders(), ensureSuccess = false)
        if (response.isSuccessful) {
            return parseJson(response.body.string()) ?: JsonObject(emptyMap())
        }
        response.close()

        val body = JsonObject(
            mapOf(
                "titleNo" to jsonNumberOrString(titleNo),
                "episodeNo" to jsonNumberOrString(episodeNo),
                "pageNo" to JsonPrimitive(page.coerceAtLeast(1)),
                "pageSize" to JsonPrimitive(COMMENT_PAGE_SIZE),
            ),
        ).toString().toRequestBody(JSON_MEDIA_TYPE)
        response = client.post(API_BASE + path, appHeaders(), body, ensureSuccess = false)
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException("章评接口 HTTP $code")
        }
        return parseJson(response.body.string()) ?: JsonObject(emptyMap())
    }

    private fun JsonElement.toComments(parentId: String?): List<Comment> = objects()
        .filter { it.stringAny("commentId", "commentNo", "id") != null }
        .filter {
            it.stringAny("content", "commentContentText", "commentText", "text")?.isNotBlank() == true
        }
        .map { obj ->
            val id = obj.stringAny("commentId", "commentNo", "id")!!
            Comment(
                id = id,
                author = CommentAuthor(
                    id = obj.stringAny("memberNo", "userNo", "userId"),
                    name = obj.stringAny("nickName", "nickname", "userName", "name") ?: "咚漫用户",
                    avatarUrl = obj.stringAny("profileImage", "profileImageUrl", "avatarUrl"),
                ),
                content = obj.stringAny("content", "commentContentText", "commentText", "text")!!,
                createdAt = obj.longAny("createdAt", "commentDate", "createTime", "registerDate")
                    ?.normalizeEpoch() ?: 0L,
                displayTime = obj.stringAny("displayTime", "commentDateText", "dateText"),
                likeCount = obj.longAny("likeCount", "likeCnt", "like") ?: 0L,
                replyCount = obj.longAny("replyCount", "commentReplyCount", "replyCnt")
                    ?: (obj["commentReplyList"] as? JsonArray)?.size?.toLong()
                    ?: 0L,
                likedByMe = obj.boolAny("liked", "likeYn", "isLike") == true,
                parentId = parentId,
            )
        }
        .distinctBy { it.id }
        .toList()

    override suspend fun getSourceAccount(): SourceAccount? {
        val token = currentToken().takeIf(String::isNotBlank) ?: return null
        return fetchAccount(token)
    }

    override val chapterContentReplacementCapabilities = ChapterContentReplacementCapabilities(
        showInChapterList = true,
        showInReader = true,
    )

    private fun titleNoOf(url: String): String? = runCatching {
        val parsed = absoluteWebUrl(url).toHttpUrl()
        parsed.queryParameter("titleNo") ?: parsed.queryParameter("title_no")
    }.getOrNull()

    private fun episodeNoOf(url: String): String? = runCatching {
        absoluteWebUrl(url).toHttpUrl().queryParameter("episodeNo")
    }.getOrNull()

    private fun viewerRelativeUrl(titleNo: String, episodeNo: String): String = "/viewer?titleNo=$titleNo&episodeNo=$episodeNo"

    private fun absoluteWebUrl(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("/") -> baseUrl + url
        else -> "$baseUrl/$url"
    }

    private fun relativeWebUrl(url: String): String = runCatching {
        val parsed = url.toHttpUrl()
        buildString {
            append(parsed.encodedPath)
            parsed.encodedQuery?.let { append('?').append(it) }
        }
    }.getOrElse { url }

    private fun String.extractChapterNumber(): Float? = Regex("(?:第\\s*)?(\\d+(?:\\.\\d+)?)\\s*(?:话|章|回)?")
        .find(this)?.groupValues?.get(1)?.toFloatOrNull()

    private fun parseJson(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()

    private fun JsonElement.objects(): Sequence<JsonObject> = sequence {
        when (this@objects) {
            is JsonObject -> {
                yield(this@objects)
                values.forEach { yieldAll(it.objects()) }
            }
            is JsonArray -> forEach { yieldAll(it.objects()) }
            else -> Unit
        }
    }

    private fun JsonObject.stringAny(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JsonObject.longAny(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
        val primitive = this[key] as? JsonPrimitive ?: return@firstNotNullOfOrNull null
        primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
    }

    private fun JsonObject.doubleAny(vararg keys: String): Double? = keys.firstNotNullOfOrNull { key ->
        val primitive = this[key] as? JsonPrimitive ?: return@firstNotNullOfOrNull null
        primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
    }

    private fun JsonObject.boolAny(vararg keys: String): Boolean? = keys.firstNotNullOfOrNull { key ->
        val primitive = this[key] as? JsonPrimitive ?: return@firstNotNullOfOrNull null
        primitive.booleanOrNull ?: when (primitive.contentOrNull?.lowercase(Locale.ROOT)) {
            "y", "yes", "1", "true" -> true
            "n", "no", "0", "false" -> false
            else -> null
        }
    }

    private fun JsonElement.firstString(vararg keys: String): String? = objects().firstNotNullOfOrNull { it.stringAny(*keys) }

    private fun JsonElement.firstLong(vararg keys: String): Long? = objects().firstNotNullOfOrNull { it.longAny(*keys) }

    private fun readServerMessage(text: String): String? = parseJson(text)?.firstString("message", "errorMessage", "error_description", "error")

    private fun jsonNumberOrString(value: String): JsonPrimitive = value.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)

    private fun Long.normalizeEpoch(): Long = when {
        this <= 0L -> 0L
        this < 10_000_000_000L -> this * 1000L
        else -> this
    }

    private data class LoginAttempt(val path: String, val fields: Map<String, String>)

    companion object {
        private const val API_BASE = "https://apis.dongmanmanhua.cn"
        private const val API_HOST = "apis.dongmanmanhua.cn"
        private const val APP_UA = "Dongman/Android"
        private const val LOCK_PREFIX = "🔒 "
        private const val COMMENT_PAGE_SIZE = 20
        private const val ACCOUNT_PREF = "account"
        private const val PASSWORD_PREF = "password"
        private const val TOKEN_PREF = "access_token"
        private const val LOGIN_STATUS_PREF = "login_status"
        private const val LOGIN_ACTION_PREF = "login_action"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
