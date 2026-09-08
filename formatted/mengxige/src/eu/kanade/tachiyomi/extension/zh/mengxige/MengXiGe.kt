package eu.kanade.tachiyomi.extension.zh.mengxige

import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.seconds

internal const val DEFAULT_DOMAIN = "folongteng.com"

private const val APP_PACKAGE = "com.mengxigeyd.novel"
private const val APP_SECRET = "vhjJVz1St6tK7!8n#B0MqRIuE2Dh7!C#"
private const val APP_VERSION = "1.0.6"
private const val APP_CHANNEL = "baidu_tlmh"
private const val APP_ID = "11000001"
private const val DEFAULT_COMIC_APP_ID = "227"
private const val API_NETWORK_ATTEMPTS = 2
private const val EMPTY_PAYLOAD_ATTEMPTS = 2
private const val RETRY_DELAY_MS = 300L

private val RETRYABLE_HTTP_CODES = setOf(408, 425, 429, 500, 502, 503, 504)
private val PATH_ARRAY_KEYS = listOf("data", "lists", "list", "catalog", "chapters", "chapter", "content", "result")

@Source
abstract class MengXiGe :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()
    private val json = Json { ignoreUnknownKeys = true }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor(MengXiGeImageDecoderInterceptor())
        retryOnConnectionFailure(true)
        connectTimeout(20.seconds)
        readTimeout(60.seconds)
    }

    override fun Headers.Builder.configureHeaders() = apply {
        set(
            "User-Agent",
            "OPPO_R821T/1.0 Linux/3.4.5 Android/4.2.2 Release/03.26.2013 " +
                "Browser/AppleWebKit534.30 Mobile Safari/534.30 MBBMS/2.2 " +
                "System/Android 4.2.2_$APP_PACKAGE",
        )
        set("Accept", "*/*")
        set("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
        set("Referer", "https://book.folongteng.com/")
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        buildPreferences(screen.context, preferences).forEach(screen::addPreference)
    }

    override fun getHomeUrl(): String = bookBaseUrl()

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        return MangasPage(fetchBookList(rankUrl(rank = "2", category = "0")), false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val mangas = fetchBookList(
            "${bookBaseUrl()}/book_city/v7/comics/1/0/${page.coerceAtLeast(1)}.html",
        )
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        RankFilter(),
        CategoryFilter(),
    )

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        if (query.isNotBlank()) {
            if (page > 1) return MangasPage(emptyList(), false)

            val url = "${host("s")}/v4/1/lists.api".toHttpUrl()
                .newBuilder()
                .addQueryParameter("keyword", query.trim())
                .addQueryParameter("form", "3")
                .build()
                .toString()

            return MangasPage(fetchBookList(url), false)
        }

        if (page > 1) return MangasPage(emptyList(), false)
        val rank = filters.firstInstanceOrNull<RankFilter>()?.value() ?: "1"
        val category = filters.firstInstanceOrNull<CategoryFilter>()?.value() ?: "0"
        return MangasPage(fetchBookList(rankUrl(rank, category)), false)
    }

    override fun getMangaUrl(manga: SManga): String = mangaIdFromUrl(manga.url)?.let(::detailUrl) ?: getHomeUrl()

    override fun getChapterUrl(chapter: SChapter): String = chapter.url

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val id = mangaIdFromUrl(url.toString()) ?: return null
        return fetchDetailObject(id)?.toDetailedManga(id)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)

        val id = mangaIdFromUrl(manga.url)
            ?: throw IOException("无法识别梦溪阁漫画 ID：${manga.url}")

        val detail = try {
            fetchDetailObject(id)
        } catch (e: IOException) {
            if (!fetchChapters) throw e
            null
        }

        if (detail == null && fetchDetails && !fetchChapters) {
            throw IOException("梦溪阁详情接口未返回漫画 #$id")
        }

        val updatedManga = if (fetchDetails && detail != null) {
            detail.toDetailedManga(id).apply {
                url = manga.url
                if (title.isBlank()) title = manga.title
                if (thumbnail_url.isNullOrBlank()) thumbnail_url = manga.thumbnail_url
                if (author.isNullOrBlank()) author = manga.author
                if (description.isNullOrBlank()) description = manga.description
                if (genre.isNullOrBlank()) genre = manga.genre
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            fetchChapterList(id, detail, manga.thumbnail_url)
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val rows = fetchPathRows(chapter.url, "正文页")
        val pages = rows.mapIndexedNotNull { index, element ->
            val item = element as? JsonObject ?: return@mapIndexedNotNull null
            val path = item.string("path")?.trim().orEmpty()
            if (path.isBlank()) return@mapIndexedNotNull null

            val imageUrl = when {
                path.startsWith("http://") || path.startsWith("https://") -> path
                path.startsWith("//") -> "https:$path"
                else -> "${host("c-chapter")}/${path.trimStart('/')}"
            }

            Page(index = index, url = chapter.url, imageUrl = imageUrl)
        }

        if (pages.isEmpty()) throw IOException("梦溪阁正文接口未返回图片")
        return pages
    }

    override fun imageRequest(page: Page): Request {
        val imageHeaders = headers.newBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .set("Referer", page.url.ifBlank { "${bookBaseUrl()}/" })
            .build()

        return GET(requireNotNull(page.imageUrl), imageHeaders)
    }

    private suspend fun fetchBookList(url: String): List<SManga> {
        val root = apiGetJson(url)
        val result = mutableListOf<SManga>()
        val seen = mutableSetOf<String>()

        fun walk(element: JsonElement) {
            when (element) {
                is JsonArray -> element.forEach(::walk)
                is JsonObject -> {
                    val id = element.string("book_id") ?: element.string("id")
                    val title = element.string("name")
                        ?: element.string("book_name")
                        ?: element.string("title")
                    if (!id.isNullOrBlank() && !title.isNullOrBlank() && seen.add(id)) {
                        result += element.toListManga(id, title)
                    }
                    element.forEach { (key, value) ->
                        if (key != "author_book" && key != "related_book") walk(value)
                    }
                }
                else -> Unit
            }
        }

        walk(root)
        return result
    }

    private suspend fun fetchDetailObject(id: String): JsonObject? {
        val root = apiGetJson(detailUrl(id))

        fun walk(element: JsonElement): JsonObject? {
            when (element) {
                is JsonArray -> element.forEach { item -> walk(item)?.let { return it } }
                is JsonObject -> {
                    val currentId = element.string("book_id") ?: element.string("id")
                    val hasBookFields = element.string("name") != null ||
                        element.string("book_name") != null ||
                        element.string("image") != null ||
                        element.string("cover") != null
                    if ((currentId == id || currentId == null) && hasBookFields) return element
                    element.values.forEach { value -> walk(value)?.let { return it } }
                }
                else -> Unit
            }
            return null
        }

        return walk(root)
    }

    private suspend fun fetchChapterList(
        id: String,
        initialDetail: JsonObject?,
        thumbnailUrl: String?,
    ): List<SChapter> {
        val attemptedUrls = linkedSetOf<String>()
        var lastFailure: IOException? = null

        suspend fun tryCandidates(detail: JsonObject?): List<SChapter>? {
            for (url in chapterListUrls(id, detail, thumbnailUrl)) {
                if (!attemptedUrls.add(url)) continue

                try {
                    val chapters = parseChapterList(fetchPathRows(url, "章节目录"))
                    if (chapters.isNotEmpty()) return chapters.latestFirstForSourceOrder()
                    lastFailure = IOException("梦溪阁章节目录为空：$url")
                } catch (e: IOException) {
                    lastFailure = e
                }
            }
            return null
        }

        tryCandidates(initialDetail)?.let { return it }

        val refreshedDetail = try {
            fetchDetailObject(id)
        } catch (e: IOException) {
            lastFailure = e
            null
        }

        if (refreshedDetail != null) {
            tryCandidates(refreshedDetail)?.let { return it }
        }

        throw IOException("梦溪阁章节目录暂时不可用，已刷新详情并重试", lastFailure)
    }

    private fun chapterListUrls(
        id: String,
        detail: JsonObject?,
        thumbnailUrl: String?,
    ): List<String> = buildList {
        detail?.string("list_path")
            ?.let(::catalogUrl)
            ?.let { add(it) }

        val appIds = linkedSetOf<String>()
        detail?.let(::comicAppId)?.let(appIds::add)
        comicAppIdFromPath(thumbnailUrl)?.let(appIds::add)
        appIds += DEFAULT_COMIC_APP_ID

        appIds.forEach { appId ->
            add("${host("c-catalog")}/app/$appId/d0/33/$id.html")
        }
    }.distinct()

    private fun catalogUrl(path: String): String? {
        val value = path.trim()
        if (value.isBlank()) return null

        return when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("//") -> "https:$value"
            else -> "${host("c-catalog")}/${value.trimStart('/')}"
        }
    }

    private fun parseChapterList(rows: JsonArray): List<SChapter> = rows.mapIndexedNotNull { index, element ->
        val item = element as? JsonObject ?: return@mapIndexedNotNull null
        val path = item.string("path")?.trim().orEmpty()
        if (path.isBlank()) return@mapIndexedNotNull null

        val name = item.string("name")?.trim().orEmpty()
        val updatedAt = item.string("updated_at").orEmpty()
        val baseUrl = when {
            path.startsWith("http://") || path.startsWith("https://") -> path
            path.startsWith("//") -> "https:$path"
            else -> "${host("c-pic")}/${path.trimStart('/')}"
        }
        val chapterUrl = baseUrl.toHttpUrl()
            .newBuilder()
            .apply {
                if (updatedAt.isNotBlank()) setQueryParameter("time", updatedAt)
            }
            .build()
            .toString()

        SChapter.create().apply {
            this.url = chapterUrl
            this.name = name.ifBlank { "第${index + 1}话" }
            chapter_number = Regex("""\d+(?:\.\d+)?""")
                .find(this.name)
                ?.value
                ?.toFloatOrNull()
                ?: -1F
        }
    }

    private suspend fun fetchPathRows(url: String, label: String): JsonArray {
        repeat(EMPTY_PAYLOAD_ATTEMPTS) { attempt ->
            val rows = apiGetJson(url).findPathArray()
            if (rows != null && rows.isNotEmpty()) return rows
            if (attempt + 1 < EMPTY_PAYLOAD_ATTEMPTS) delay(RETRY_DELAY_MS)
        }
        throw IOException("梦溪阁${label}接口返回空数据")
    }

    private fun List<SChapter>.latestFirstForSourceOrder(): List<SChapter> {
        val chapterNumbers = mapNotNull { chapter -> chapter.chapter_number.takeIf { it >= 0F } }
        if (chapterNumbers.size < 2) return this
        return if (chapterNumbers.first() < chapterNumbers.last()) asReversed() else this
    }

    private fun JsonObject.toListManga(id: String, rawTitle: String): SManga = SManga.create().apply {
        url = "/comic/$id"
        title = rawTitle.trim()
        author = string("author") ?: string("author_name")
        thumbnail_url = coverUrl(string("image") ?: string("cover") ?: string("cover_url"))
        description = string("remark") ?: string("desc") ?: string("description")
        genre = genreText()
    }

    private fun JsonObject.toDetailedManga(id: String): SManga = SManga.create().apply {
        url = "/comic/$id"
        title = (string("name") ?: string("book_name") ?: string("title") ?: "漫画 #$id").trim()
        author = string("author") ?: string("author_name")
        thumbnail_url = coverUrl(string("image") ?: string("cover") ?: string("cover_url"))
        description = string("remark") ?: string("desc") ?: string("description")
        genre = genreText()
        status = statusValue()
    }

    private fun JsonObject.genreText(): String? {
        val values = mutableListOf<String>()
        listOf("ltype", "stype", "area", "category").forEach { key ->
            string(key)?.trim()?.takeIf(String::isNotBlank)?.let(values::add)
        }

        when (val tags = this["tags"]) {
            is JsonArray -> tags.forEach { tag ->
                when (tag) {
                    is JsonPrimitive -> tag.contentOrNull?.trim()?.takeIf(String::isNotBlank)?.let(values::add)
                    is JsonObject -> (tag.string("name") ?: tag.string("title"))
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?.let(values::add)
                    else -> Unit
                }
            }
            is JsonPrimitive ->
                tags.contentOrNull
                    ?.split(',', '/', '、', '|')
                    ?.map(String::trim)
                    ?.filter(String::isNotBlank)
                    ?.let(values::addAll)
            else -> Unit
        }

        return values.distinct().takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun JsonObject.statusValue(): Int {
        val text = listOfNotNull(
            string("status"),
            string("state"),
            string("full"),
            string("is_finish"),
            string("remark"),
        ).joinToString(" ")

        return when {
            text.contains("完结") || text.contains("已完") || text == "1" -> SManga.COMPLETED
            text.contains("连载") || text.contains("更新") || text == "0" -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    private fun comicAppId(detail: JsonObject): String? = comicAppIdFromPath(
        detail.string("image") ?: detail.string("cover") ?: detail.string("cover_url"),
    )

    private fun comicAppIdFromPath(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        val path = value.toHttpUrlOrNull()?.encodedPath ?: value.substringBefore('?')
        return path.trim('/').substringBefore('/').takeIf { it.all(Char::isDigit) }
    }

    private fun coverUrl(path: String?): String? {
        val value = path?.trim().orEmpty()
        if (value.isBlank()) return null
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        if (value.startsWith("//")) return "https:$value"
        return "${host("c-res")}/${value.trimStart('/')}"
    }

    private suspend fun apiGetJson(url: String): JsonElement {
        ensureLoginIfConfigured()
        var lastParseFailure: IOException? = null

        repeat(API_NETWORK_ATTEMPTS) { attempt ->
            val result = performAuthorizedApiGet(url)
            try {
                return parsePayload(result.second)
            } catch (e: IOException) {
                lastParseFailure = e
                if (attempt + 1 < API_NETWORK_ATTEMPTS) delay(RETRY_DELAY_MS)
            }
        }

        throw lastParseFailure ?: IOException("梦溪阁接口返回无法解析的数据")
    }

    private suspend fun performAuthorizedApiGet(url: String): Pair<Int, String> {
        var result = performApiGetWithRetry(url)
        if (result.first in setOf(401, 403) && hasConfiguredCredentials()) {
            ensureLoginIfConfigured(force = true)
            result = performApiGetWithRetry(url)
        }
        if (result.first !in 200..299) throw IOException("梦溪阁接口请求失败：HTTP ${result.first}")
        return result
    }

    private suspend fun performApiGetWithRetry(url: String): Pair<Int, String> {
        var lastFailure: IOException? = null
        var lastResult: Pair<Int, String>? = null

        repeat(API_NETWORK_ATTEMPTS) { attempt ->
            try {
                val result = performApiGet(url)
                lastResult = result
                if (result.first !in RETRYABLE_HTTP_CODES || attempt + 1 >= API_NETWORK_ATTEMPTS) {
                    return result
                }
            } catch (e: IOException) {
                lastFailure = e
                if (attempt + 1 >= API_NETWORK_ATTEMPTS) {
                    throw IOException("梦溪阁接口网络请求失败", e)
                }
            }
            delay(RETRY_DELAY_MS * (attempt + 1))
        }

        lastResult?.let { return it }
        throw lastFailure ?: IOException("梦溪阁接口网络请求失败")
    }

    private suspend fun performApiGet(url: String): Pair<Int, String> = client.get(url, appHeaders(currentToken())).use { response ->
        response.code to response.body.string()
    }

    private suspend fun ensureLoginIfConfigured(force: Boolean = false) {
        val account = preferences.getString(ACCOUNT_PREF, "").orEmpty().trim()
        val password = preferences.getString(PASSWORD_PREF, "").orEmpty()

        if (account.isBlank() || password.isBlank()) {
            if (preferences.getString(TOKEN_PREF, "").orEmpty().isNotBlank()) {
                preferences.edit()
                    .remove(TOKEN_PREF)
                    .remove(USER_PREF)
                    .remove(USER_ID_PREF)
                    .remove(CREDENTIAL_KEY_PREF)
                    .apply()
            }
            return
        }

        val credentialKey = md5Hex("$account\u0000$password")
        val savedCredentialKey = preferences.getString(CREDENTIAL_KEY_PREF, "").orEmpty()
        val savedToken = preferences.getString(TOKEN_PREF, "").orEmpty()
        if (!force && savedCredentialKey == credentialKey && savedToken.isNotBlank()) return

        val body = FormBody.Builder()
            .add("login", account)
            .add("password", password)
            .add("tel", "86")
            .add("encode", "1")
            .build()

        val raw = client.post(
            "${host("my")}/v6/login.api",
            appHeaders(token = ""),
            body,
        ).use { response ->
            if (response.code !in 200..299) throw IOException("梦溪阁登录失败：HTTP ${response.code}")
            response.body.string()
        }

        val root = parsePayload(raw)
        val auth = findAuthObject(root)
            ?: throw IOException(loginErrorMessage(root) ?: "梦溪阁登录失败：服务器未返回 token")
        val token = auth.string("token").orEmpty()
        if (token.isBlank()) throw IOException("梦溪阁登录失败：token 为空")

        preferences.edit()
            .putString(TOKEN_PREF, token)
            .putString(USER_PREF, auth.string("user") ?: account)
            .putString(USER_ID_PREF, auth.string("user_id").orEmpty())
            .putString(CREDENTIAL_KEY_PREF, credentialKey)
            .apply()
    }

    private fun findAuthObject(root: JsonElement): JsonObject? {
        fun walk(element: JsonElement): JsonObject? {
            when (element) {
                is JsonObject -> {
                    if (!element.string("token").isNullOrBlank()) return element
                    element.values.forEach { value -> walk(value)?.let { return it } }
                }
                is JsonArray -> element.forEach { value -> walk(value)?.let { return it } }
                else -> Unit
            }
            return null
        }
        return walk(root)
    }

    private fun loginErrorMessage(root: JsonElement): String? {
        fun walk(element: JsonElement): String? {
            when (element) {
                is JsonObject -> {
                    listOf("msg", "message", "error").forEach { key ->
                        element.string(key)?.takeIf(String::isNotBlank)?.let { return it }
                    }
                    element.values.forEach { value -> walk(value)?.let { return it } }
                }
                is JsonArray -> element.forEach { value -> walk(value)?.let { return it } }
                else -> Unit
            }
            return null
        }
        return walk(root)
    }

    private fun hasConfiguredCredentials(): Boolean = !preferences.getString(ACCOUNT_PREF, "").isNullOrBlank() &&
        !preferences.getString(PASSWORD_PREF, "").isNullOrBlank()

    private fun currentToken(): String = preferences.getString(TOKEN_PREF, "").orEmpty()

    private fun appHeaders(token: String): Headers {
        val now = (System.currentTimeMillis() / 1000L).toString()
        val user = preferences.getString(USER_PREF, "").orEmpty()

        return headers.newBuilder()
            .set("Accept", "*/*")
            .set("pt", "1")
            .set("version", APP_VERSION)
            .set("channel", APP_CHANNEL)
            .set("uuid", deviceId())
            .set("xf-appid", APP_ID)
            .set("fc-session-id", "")
            .set("package", APP_PACKAGE)
            .set("time", now)
            .set("stamp", "")
            .set("sign", md5Hex(APP_PACKAGE + token + "1" + now + APP_SECRET))
            .set("Cache-Control", "no-cache, no-store")
            .apply {
                if (user.isNotBlank()) set("user", user)
                if (token.isNotBlank()) set("token", token)
            }
            .build()
    }

    private fun deviceId(): String {
        val saved = preferences.getString(DEVICE_ID_PREF, "").orEmpty()
        if (saved.isNotBlank()) return saved
        val created = UUID.randomUUID().toString()
        preferences.edit().putString(DEVICE_ID_PREF, created).apply()
        return created
    }

    private fun parsePayload(raw: String): JsonElement {
        val decoded = decodeEnvelope(raw)
        return runCatching { json.parseToJsonElement(decoded) }
            .getOrElse { cause -> throw IOException("梦溪阁接口返回无法解析的数据", cause) }
    }

    private fun decodeEnvelope(raw: String): String {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return raw
        val data = root["data"] as? JsonObject ?: return raw
        val version = data.string("ver")?.toIntOrNull() ?: return raw
        val content = data.string("content") ?: return raw
        if (version != 1) return raw

        val all = runCatching { Base64.decode(content, Base64.DEFAULT) }.getOrElse { return raw }
        if (all.size < 32) return raw

        val headBytes = all.copyOfRange(0, 16)
        val tailBytes = all.copyOfRange(all.size - 16, all.size)
        val head = String(headBytes, StandardCharsets.US_ASCII)
        val tail = String(tailBytes, StandardCharsets.US_ASCII)
        val key = MessageDigest.getInstance("SHA-256").digest(head.toByteArray(StandardCharsets.US_ASCII))
        val md5Bytes = md5Hex(tail).toByteArray(StandardCharsets.US_ASCII)
        val iv = ByteArray(16) { index ->
            val mixed = (md5Bytes[index].toInt() and 0xFF) xor (tailBytes[index].toInt() and 0xFF)
            (mixed.inv() and 0xFF).toByte()
        }
        val body = all.copyOfRange(16, all.size - 16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(body), StandardCharsets.UTF_8)
    }

    private fun rankUrl(rank: String, category: String): String = "${bookBaseUrl()}/book_city/v7/4/rank2tag/$rank/$category.html"

    private fun detailUrl(id: String): String {
        val bucket = id.toLongOrNull()?.div(1000L) ?: 0L
        return "${bookBaseUrl()}/comics/details/v4/$bucket/$id.html"
    }

    private fun bookBaseUrl(): String = host("book")

    private fun host(subdomain: String): String = "https://$subdomain.${domainSuffix()}"

    private fun domainSuffix(): String {
        val raw = preferences.getString(DOMAIN_PREF, DEFAULT_DOMAIN)
            .orEmpty()
            .trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .trim('.')
        return raw.ifBlank { DEFAULT_DOMAIN }
    }

    private fun mangaIdFromUrl(url: String): String? = Regex("""(\d+)(?:\.html)?(?:[/?#]|$)""")
        .findAll(url)
        .lastOrNull()
        ?.groupValues
        ?.getOrNull(1)

    private fun JsonElement.findPathArray(): JsonArray? {
        if (this is JsonArray) {
            val hasPathRows = any { element ->
                (element as? JsonObject)?.string("path")?.isNotBlank() == true
            }
            if (hasPathRows) return this
            for (element in this) {
                element.findPathArray()?.let { return it }
            }
            return null
        }

        if (this is JsonObject) {
            for (key in PATH_ARRAY_KEYS) {
                this[key]?.findPathArray()?.let { return it }
            }
            for ((key, value) in this) {
                if (key !in PATH_ARRAY_KEYS) value.findPathArray()?.let { return it }
            }
        }

        return null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun md5Hex(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}
