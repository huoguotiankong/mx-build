package eu.kanade.tachiyomi.extension.zh.miaoqumanhuaplus

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@Source
abstract class MiaoquManhuaPlus : KeiSource() {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile
    private var remoteConfig: RemoteConfig? = null

    override fun OkHttpClient.Builder.configureClient() = apply {
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(MiaoquTrustManager), null)
        }
        sslSocketFactory(sslContext.socketFactory, MiaoquTrustManager)
        addNetworkInterceptor(MiaoquImageInterceptor)
    }

    override fun Headers.Builder.configureHeaders() = apply {
        set("Accept", "application/json, text/plain, */*")
        set("User-Agent", APP_USER_AGENT)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val config = getRemoteConfig()
        val preferred = config.layoutCategories.firstOrNull { it == "推荐" }
            ?: config.layoutCategories.firstOrNull()
            ?: "推荐"
        return MangasPage(fetchCategoryManga(config, preferred), false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val config = getRemoteConfig()
        val candidates = buildList {
            addAll(config.layoutCategories.filter { it.contains("最新") || it.contains("更新") })
            addAll(config.layoutCategories)
            addAll(DEFAULT_LAYOUT_CATEGORIES)
        }.distinct().take(6)

        val mangas = candidates
            .flatMap { category -> runCatching { fetchCategoryManga(config, category) }.getOrDefault(emptyList()) }
            .distinctBy { it.url }

        return MangasPage(mangas, false)
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement {
        val config = getRemoteConfig()
        return JsonArray(config.categories.map(::JsonPrimitive))
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val categories = (data as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter(String::isNotBlank)
            .orEmpty()
            .ifEmpty { DEFAULT_CATEGORIES }

        return FilterList(
            CategoryFilter(
                buildList {
                    add(CategoryOption("全部", ""))
                    addAll(categories.map { CategoryOption(it, it) })
                }.distinctBy { it.id }.toTypedArray(),
            ),
        )
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val config = getRemoteConfig()

        if (query.isNotBlank()) {
            val url = config.otherUrl.toHttpUrl().newBuilder()
                .addPathSegment("sousuo")
                .addQueryParameter("keyword", query.trim())
                .build()
            return MangasPage(parseMangaList(getJson(url), config), false)
        }

        val category = filters.filterIsInstance<CategoryFilter>()
            .firstOrNull()
            ?.selectedId
            .orEmpty()

        if (category.isBlank()) return getPopularManga(page)
        return MangasPage(fetchCategoryManga(config, category), false)
    }

    override fun getMangaUrl(manga: SManga): String = "$OFFICIAL_SITE/?mangaId=${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String {
        val parts = chapter.url.split(':')
        return if (parts.size >= 2) {
            "$OFFICIAL_SITE/?mangaId=${parts[0]}&chapterId=${parts[1]}"
        } else {
            OFFICIAL_SITE
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val id = url.queryParameter("mangaId")
            ?: url.pathSegments.firstOrNull { it.toIntOrNull() != null }
            ?: return null
        return SManga.create().apply {
            this.url = id
            title = "喵趣漫画 #$id"
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mangaId = manga.url.substringAfterLast('/').toIntOrNull()
            ?: throw IOException("喵趣漫画 ID 无效")
        val config = getRemoteConfig()

        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)

        val info = fetchMangaInfo(config, mangaId)
        val updatedManga = if (fetchDetails) {
            toManga(info, config)?.apply { url = mangaId.toString() } ?: manga
        } else {
            manga
        }
        val updatedChapters = if (fetchChapters) parseChapters(info, mangaId) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override fun imageRequest(page: Page): Request = GET(
        page.imageUrl ?: throw IOException("喵趣正文图片地址为空"),
        headers.newBuilder()
            .set("User-Agent", READER_USER_AGENT)
            .set("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .build(),
    )

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val parts = chapter.url.split(':')
        if (parts.size < 3) throw IOException("喵趣章节参数无效")

        val mangaId = parts[0].toIntOrNull() ?: throw IOException("喵趣漫画 ID 无效")
        val chapterId = parts[1].toIntOrNull() ?: throw IOException("喵趣章节 ID 无效")
        val pageCount = parts[2].toIntOrNull() ?: throw IOException("喵趣正文页数无效")
        if (pageCount <= 0) throw IOException("喵趣正文页数为空")

        val config = getRemoteConfig()
        val imageBase = config.mangaImageUrl
            .ifBlank { config.coverImageUrl }
            .ifBlank { config.otherUrl }
        if (imageBase.isBlank()) throw IOException("喵趣图片域名为空")
        val group = mangaId / 1000

        return (1..pageCount).mapIndexed { index, pageNo ->
            val imageUrl = imageBase.toHttpUrl().newBuilder()
                .addPathSegment("tu")
                .addPathSegment(group.toString())
                .addPathSegment(mangaId.toString())
                .addPathSegment(chapterId.toString())
                .addPathSegment("$pageNo.jpeg")
                .build()
                .toString()
            Page(index, imageUrl = imageUrl)
        }
    }

    private suspend fun fetchCategoryManga(config: RemoteConfig, category: String): List<SManga> {
        val urls = listOf(
            config.textUrl.toHttpUrl().newBuilder()
                .addPathSegments("api/com.xinmiaoqu.app")
                .addPathSegment("$category.json")
                .build(),
            config.textUrl.toHttpUrl().newBuilder()
                .addPathSegments("fenlei/com.xinmiaoqu.app")
                .addPathSegment("$category.json")
                .build(),
        )

        var lastError: Throwable? = null
        for (url in urls) {
            val result = runCatching { parseMangaList(getJson(url), config) }
            if (result.isSuccess && result.getOrThrow().isNotEmpty()) return result.getOrThrow()
            result.exceptionOrNull()?.let { lastError = it }
        }
        if (lastError != null) throw IOException("喵趣分类加载失败", lastError)
        return emptyList()
    }

    private suspend fun fetchMangaInfo(config: RemoteConfig, mangaId: Int): JsonObject {
        val group = mangaId / 1000
        val url = config.textUrl.toHttpUrl().newBuilder()
            .addPathSegment(MANGA_INFO_PATH_KEY)
            .addPathSegment(group.toString())
            .addPathSegment(mangaId.toString())
            .addPathSegment("info.json")
            .build()

        val response = client.get(url.toString(), chapterHeaders())
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException("喵趣详情/目录接口 HTTP $code")
        }

        val encrypted = response.use { it.body.string() }
        val decrypted = decryptMangaInfo(encrypted)
        return runCatching { json.parseToJsonElement(decrypted) as JsonObject }
            .getOrElse { throw IOException("喵趣详情/目录数据解析失败", it) }
    }

    private fun parseChapters(root: JsonObject, mangaId: Int): List<SChapter> {
        val chapterArray = root["chapter"] as? JsonArray ?: return emptyList()
        return chapterArray
            .mapNotNull { it as? JsonObject }
            .mapNotNull { obj ->
                val chapterId = int(obj, "chapterid", "chapterId", "chapter_id") ?: return@mapNotNull null
                val chapterName = string(obj, "chaptername", "chapterName", "name", "title") ?: return@mapNotNull null
                val pageCount = int(obj, "geshu", "pageCount", "pagecount", "pages") ?: 0
                val sortId = int(obj, "paixuid", "sortId", "sortid", "sort") ?: chapterId

                SChapter.create().apply {
                    url = "$mangaId:$chapterId:$pageCount"
                    name = chapterName
                    date_upload = parseTimestamp(
                        string(
                            obj,
                            "uptatime",
                            "last_updated_time",
                            "updatedtime",
                            "updateTime",
                            "createdtime",
                            "createdTime",
                        ),
                    )
                    chapter_number = sortId.toFloat()
                } to sortId
            }
            .distinctBy { it.first.url }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun parseMangaList(root: JsonElement, config: RemoteConfig): List<SManga> = collectObjects(root)
        .mapNotNull { obj -> toManga(obj, config) }
        .distinctBy { it.url }

    private fun toManga(obj: JsonObject, config: RemoteConfig): SManga? {
        val id = string(obj, "manhuaid", "mangaid", "id")?.toIntOrNull() ?: return null
        val title = string(obj, "manhuaming", "mangaName", "title", "name") ?: return null
        val cover = string(obj, "fengmiantu", "lunbotu", "cover", "coverUrl")

        return SManga.create().apply {
            url = id.toString()
            this.title = title
            thumbnail_url = cover?.let { resolveImageUrl(config.coverImageUrl, it) }
            author = string(obj, "manhuazuozhe", "zuozhe", "author")
            description = string(obj, "description", "desc")
            genre = string(obj, "fenlei", "genre")
            status = when (val raw = string(obj, "zhuangtai", "status").orEmpty()) {
                "完结", "已完结", "完結", "已完結" -> SManga.COMPLETED
                "连载", "连载中", "連載", "連載中" -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    private suspend fun getRemoteConfig(force: Boolean = false): RemoteConfig {
        if (!force) remoteConfig?.let { return it }

        for (endpoint in CONFIG_ENDPOINTS) {
            val result = runCatching {
                val root = getJson(endpoint.toHttpUrl())
                parseRemoteConfig(root)
            }
            if (result.isSuccess) {
                val config = result.getOrThrow()
                remoteConfig = config
                return config
            }
        }

        val fallback = RemoteConfig(
            textUrl = "https://apitong.zqykfz.cn",
            coverImageUrl = "https://tong.zqykfz.cn",
            mangaImageUrl = "",
            otherUrl = "https://api.zqykfz.cn",
            layoutCategories = DEFAULT_LAYOUT_CATEGORIES,
            categories = DEFAULT_CATEGORIES,
        )
        remoteConfig = fallback
        return fallback
    }

    private fun parseRemoteConfig(root: JsonElement): RemoteConfig {
        val api = findObject(root) { obj ->
            (obj.containsKey("txturl") || obj.containsKey("textUrl")) &&
                (obj.containsKey("qitaurl") || obj.containsKey("otherUrl"))
        } ?: throw IOException("喵趣配置缺少 apiurl")

        val textUrl = string(api, "txturl", "textUrl")?.normalizeBaseUrl()
            ?: throw IOException("喵趣配置缺少 txturl")
        val coverImageUrl = string(api, "fengmiantuurl", "coverImageUrl")?.normalizeBaseUrl().orEmpty()
        val mangaImageUrl = string(api, "manhuatuurl", "mangaImageUrl")?.normalizeBaseUrl().orEmpty()
        val otherUrl = string(api, "qitaurl", "otherUrl")?.normalizeBaseUrl()
            ?: throw IOException("喵趣配置缺少 qitaurl")

        val layoutCategories = (findValue(root, "buju") ?: findValue(root, "layoutCategories"))
            ?.let(::extractCategories)
            .orEmpty()
            .filter(String::isNotBlank)
            .distinct()
            .ifEmpty { DEFAULT_LAYOUT_CATEGORIES }

        val categories = (findValue(root, "fenlei") ?: findValue(root, "categories"))
            ?.let(::extractCategories)
            .orEmpty()
            .filter(String::isNotBlank)
            .distinct()
            .ifEmpty { DEFAULT_CATEGORIES }

        return RemoteConfig(
            textUrl = textUrl,
            coverImageUrl = coverImageUrl,
            mangaImageUrl = mangaImageUrl,
            otherUrl = otherUrl,
            layoutCategories = layoutCategories,
            categories = categories,
        )
    }

    private suspend fun getJson(url: HttpUrl): JsonElement {
        val response = client.get(url.toString(), headers)
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException("喵趣接口 HTTP $code")
        }
        val text = response.use { it.body.string() }
        return runCatching { json.parseToJsonElement(text) }
            .getOrElse { throw IOException("喵趣接口返回格式异常", it) }
    }

    private fun chapterHeaders(): Headers = headers.newBuilder()
        .set("User-Agent", READER_USER_AGENT)
        .set("Accept", "application/json")
        .build()

    private fun decryptMangaInfo(encoded: String): String = try {
        val raw = Base64.decode(encoded.trim(), Base64.DEFAULT)
        if (raw.size < 29) throw IOException("喵趣目录密文长度不足")
        val nonce = raw.copyOfRange(0, 12)
        val cipherText = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(MANGA_INFO_AES_KEY.toByteArray(StandardCharsets.UTF_8), "AES"),
            GCMParameterSpec(128, nonce),
        )
        String(cipher.doFinal(cipherText), StandardCharsets.UTF_8)
    } catch (e: Exception) {
        throw IOException("喵趣目录解密失败", e)
    }

    private fun resolveImageUrl(base: String, value: String): String {
        val raw = value.trim()
        if (raw.startsWith("https://") || raw.startsWith("http://")) return raw
        if (raw.startsWith("//")) return "https:$raw"
        if (base.isBlank()) return raw
        return "${base.trimEnd('/')}/${raw.trimStart('/')}"
    }

    private fun String.normalizeBaseUrl(): String = trim().trimEnd('/').let { raw ->
        when {
            raw.startsWith("https://") || raw.startsWith("http://") -> raw
            raw.startsWith("//") -> "https:$raw"
            else -> "https://$raw"
        }
    }

    private fun collectObjects(element: JsonElement): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        fun walk(current: JsonElement) {
            when (current) {
                is JsonObject -> {
                    out += current
                    current.values.forEach(::walk)
                }
                is JsonArray -> current.forEach(::walk)
                else -> Unit
            }
        }
        walk(element)
        return out
    }

    private fun findObject(element: JsonElement, predicate: (JsonObject) -> Boolean): JsonObject? {
        when (element) {
            is JsonObject -> {
                if (predicate(element)) return element
                element.values.forEach { child -> findObject(child, predicate)?.let { return it } }
            }
            is JsonArray -> element.forEach { child -> findObject(child, predicate)?.let { return it } }
            else -> Unit
        }
        return null
    }

    private fun findValue(element: JsonElement, key: String): JsonElement? {
        if (element is JsonObject) {
            element[key]?.let { return it }
            element.values.forEach { child -> findValue(child, key)?.let { return it } }
        } else if (element is JsonArray) {
            element.forEach { child -> findValue(child, key)?.let { return it } }
        }
        return null
    }

    private fun extractCategories(element: JsonElement): List<String> = when (element) {
        is JsonArray -> element.flatMap { child ->
            when (child) {
                is JsonPrimitive -> listOfNotNull(child.contentOrNull)
                is JsonObject -> listOfNotNull(string(child, "name", "title", "categoryName", "fenlei"))
                else -> emptyList()
            }
        }
        is JsonPrimitive -> element.contentOrNull?.split(',', '，')?.map(String::trim).orEmpty()
        else -> emptyList()
    }

    private fun string(obj: JsonObject, vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        when (val value = obj[key]) {
            is JsonPrimitive -> value.contentOrNull
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .joinToString(", ")
                .takeIf(String::isNotBlank)
            else -> null
        }
    }?.trim()?.takeIf(String::isNotBlank)

    private fun int(obj: JsonObject, vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
        val value = obj[key] as? JsonPrimitive
        value?.intOrNull ?: value?.contentOrNull?.toIntOrNull()
    }

    private fun parseTimestamp(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        raw.toLongOrNull()?.let { return if (it in 1..9_999_999_999L) it * 1000L else it }
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).parse(raw)?.time ?: 0L
        }.getOrDefault(0L)
    }

    private data class RemoteConfig(
        val textUrl: String,
        val coverImageUrl: String,
        val mangaImageUrl: String,
        val otherUrl: String,
        val layoutCategories: List<String>,
        val categories: List<String>,
    )

    private data class CategoryOption(val name: String, val id: String) {
        override fun toString(): String = name
    }

    private class CategoryFilter(options: Array<CategoryOption>) : Filter.Select<CategoryOption>("分类", options) {
        val selectedId: String get() = values[state].id
    }

    companion object {
        private const val APP_USER_AGENT = "QingManLSLand/1.0"
        private const val READER_USER_AGENT = "MangaApp/1.0"
        private const val OFFICIAL_SITE = "https://www.miaoqu.me"
        private const val MANGA_INFO_PATH_KEY = "miaoqu46"
        private const val MANGA_INFO_AES_KEY = "0000o9iu10pol777"

        private val CONFIG_ENDPOINTS = listOf(
            "https://apitong.zqykfz.cn/api/com.xinmiaoqu.app/index.json",
            "https://apitong.trgfd.cn/api/com.xinmiaoqu.app/index.json",
        )

        private val DEFAULT_LAYOUT_CATEGORIES = listOf("推荐", "搞笑", "爱情", "悬疑", "玄幻", "耽美")
        private val DEFAULT_CATEGORIES = listOf(
            "玄幻", "冒险", "热血", "动作", "奇幻", "穿越", "古风", "搞笑", "恐怖", "悬疑",
            "都市", "恋爱", "耽美", "治愈", "异能", "科幻", "修真", "推理", "历史", "竞技", "其他",
        )

        private val LEGACY_TLS_SPKI_SHA256 = setOf(
            "78b45928b59cba105590431ec7af6179a21a4e302ddea4b516e5f0b43003e6e9",
            "197e33ea57fcdcb4edb7b5386e5c41c72d68a9941f79d34573f9665fe57f31d3",
        )

        private val systemTrustManager: X509TrustManager by lazy {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(null as KeyStore?)
            }
            factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        private object MiaoquTrustManager : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                systemTrustManager.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                try {
                    systemTrustManager.checkServerTrusted(chain, authType)
                    return
                } catch (_: CertificateException) {
                    // The legacy app still uses two HTTPS endpoints with broken certificate deployment:
                    // apitong.zqykfz.cn omits its intermediate CA; api.zqykfz.cn currently serves an expired leaf.
                }

                val leaf = chain?.firstOrNull() ?: throw CertificateException("喵趣 TLS 证书链为空")
                val pin = MessageDigest.getInstance("SHA-256")
                    .digest(leaf.publicKey.encoded)
                    .joinToString("") { byte -> "%02x".format(byte) }
                if (pin !in LEGACY_TLS_SPKI_SHA256) {
                    throw CertificateException("喵趣 TLS 证书指纹不匹配")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrustManager.acceptedIssuers
        }
    }
}
