package eu.kanade.tachiyomi.extension.zh.lizimanhua

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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@Source
abstract class LiziManhua : KeiSource() {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile
    private var runtimeConfig: RuntimeConfig? = null

    override fun Headers.Builder.configureHeaders() = apply {
        set("Accept", "application/json, text/plain, */*")
        set("User-Agent", APP_USER_AGENT)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val config = getRuntimeConfig()
        val root = getApiJson(config, HOME_PATH)
        val lists = dataObject(root)?.get("home_content_list") as? JsonArray
        val mangas = lists.orEmpty()
            .mapNotNull { it as? JsonObject }
            .flatMap { section ->
                val comics = section["comic_list"] as? JsonArray ?: JsonArray(emptyList())
                comics.mapNotNull { (it as? JsonObject)?.let { obj -> toManga(obj, config) } }
            }
            .distinctBy { it.url }
        return MangasPage(mangas, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val config = getRuntimeConfig()
        val url = apiUrl(config, CATEGORY_PATH).newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        val mangas = parseCategoryList(getJson(url), config)
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement {
        val config = getRuntimeConfig()
        val data = dataObject(config.apiConfig)
        val general = data?.get("cfg_general") as? JsonObject
        val tags = (general?.get("category_tabs") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
            .orEmpty()
            .distinct()

        val classes = (data?.get("cfg_comic_class") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
            .mapNotNull { obj ->
                val id = primitiveInt(obj["id"]) ?: return@mapNotNull null
                val name = primitiveString(obj["name"]) ?: return@mapNotNull null
                JsonObject(mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(name)))
            }

        return JsonObject(
            mapOf(
                "tags" to JsonArray(tags.map(::JsonPrimitive)),
                "classes" to JsonArray(classes),
            ),
        )
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val root = data as? JsonObject
        val tags = (root?.get("tags") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
            .orEmpty()

        val classes = (root?.get("classes") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { obj ->
                val id = primitiveInt(obj["id"]) ?: return@mapNotNull null
                val name = primitiveString(obj["name"]) ?: return@mapNotNull null
                ClassOption(name, id)
            }
            .orEmpty()

        return FilterList(
            TagFilter(
                buildList {
                    add(TagOption("全部", ""))
                    addAll(tags.map { TagOption(it, it) })
                }.toTypedArray(),
            ),
            ClassFilter(
                buildList {
                    add(ClassOption("全部", 0))
                    addAll(classes)
                }.toTypedArray(),
            ),
            StatusFilter(
                arrayOf(
                    StatusOption("全部", 0),
                    StatusOption("连载", 1),
                    StatusOption("完结", 2),
                ),
            ),
        )
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val config = getRuntimeConfig()

        if (query.isNotBlank()) {
            if (page > 1) return MangasPage(emptyList(), false)
            val url = apiUrl(config, SEARCH_PATH).newBuilder()
                .addQueryParameter("q", query.trim())
                .build()
            val root = getJson(url)
            val array = dataObject(root)?.get("search_full") as? JsonArray
            val mangas = array.orEmpty()
                .mapNotNull { (it as? JsonObject)?.let { obj -> toManga(obj, config) } }
                .distinctBy { it.url }
            return MangasPage(mangas, false)
        }

        val tag = filters.filterIsInstance<TagFilter>().firstOrNull()?.selectedValue.orEmpty()
        val comicClass = filters.filterIsInstance<ClassFilter>().firstOrNull()?.selectedId ?: 0
        val status = filters.filterIsInstance<StatusFilter>().firstOrNull()?.selectedId ?: 0

        val builder = apiUrl(config, CATEGORY_PATH).newBuilder()
            .addQueryParameter("page", page.toString())
        if (tag.isNotBlank()) builder.addQueryParameter("tag", tag)
        if (comicClass != 0) builder.addQueryParameter("class", comicClass.toString())
        if (status != 0) builder.addQueryParameter("isend", status.toString())

        val mangas = parseCategoryList(getJson(builder.build()), config)
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    override fun getMangaUrl(manga: SManga): String = "$OFFICIAL_SITE/?comicId=${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String {
        val parts = chapter.url.split(':', limit = 2)
        return if (parts.size == 2) {
            "$OFFICIAL_SITE/?comicId=${parts[0]}&chapterId=${parts[1]}"
        } else {
            OFFICIAL_SITE
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val id = url.queryParameter("comicId")
            ?: url.pathSegments.firstOrNull { it.toLongOrNull() != null }
            ?: return null
        return SManga.create().apply {
            this.url = id
            title = "栗子漫画 #$id"
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)

        val comicId = manga.url.substringAfterLast('/').substringBefore(':')
        if (comicId.toLongOrNull() == null) throw IOException("栗子漫画 ID 无效")

        val config = getRuntimeConfig()
        val root = getApiJson(config, "$DETAIL_PATH$comicId")
        val detail = dataObject(root) ?: throw IOException("栗子漫画详情数据为空")

        val updatedManga = if (fetchDetails) {
            toManga(detail, config)?.apply { url = comicId } ?: manga
        } else {
            manga
        }
        val updatedChapters = if (fetchChapters) parseChapters(detail, comicId) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val parts = chapter.url.split(':', limit = 2)
        if (parts.size != 2) throw IOException("栗子漫画章节参数无效")
        val chapterId = parts[1]

        val config = getRuntimeConfig()
        val url = apiUrl(config, "$CHAPTER_PATH$chapterId").newBuilder()
            .addQueryParameter("packname", OFFICIAL_PACKAGE)
            .addQueryParameter("appsign256", OFFICIAL_APP_SIGN_SHA256)
            .build()
        val root = getJson(url)
        val pics = (dataObject(root)?.get("pics") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        if (pics.isEmpty()) throw IOException("栗子漫画正文图片列表为空")

        return pics.mapIndexedNotNull { index, path ->
            resolveImageUrl(config.imageBase, path)?.let { Page(index, imageUrl = it) }
        }.ifEmpty { throw IOException("栗子漫画正文图片地址解析失败") }
    }

    override fun imageRequest(page: Page): Request = GET(
        page.imageUrl ?: throw IOException("栗子漫画图片地址为空"),
        headers.newBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .set("Referer", "$OFFICIAL_SITE/")
            .build(),
    )

    private fun parseCategoryList(root: JsonElement, config: RuntimeConfig): List<SManga> {
        val array = dataObject(root)?.get("category_list") as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonObject)?.let { obj -> toManga(obj, config) } }
            .distinctBy { it.url }
    }

    private fun toManga(obj: JsonObject, config: RuntimeConfig): SManga? {
        val id = primitiveString(obj["id"]) ?: return null
        val title = primitiveString(obj["name"]) ?: return null
        val cover = primitiveString(obj["picY"]).orEmpty().ifBlank { primitiveString(obj["picX"]).orEmpty() }
        val alias = primitiveString(obj["alias"])
        val content = primitiveString(obj["content"])
        val isEnd = primitiveInt(obj["isend"])

        return SManga.create().apply {
            url = id
            this.title = title
            thumbnail_url = resolveImageUrl(config.imageBase, cover)
            author = primitiveString(obj["author"])
            description = listOfNotNull(
                content,
                alias?.takeIf(String::isNotBlank)?.let { "别名：$it" },
            ).joinToString("\n\n").takeIf(String::isNotBlank)
            genre = primitiveString(obj["tags"])
            status = when (isEnd) {
                1 -> SManga.ONGOING
                2 -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    private fun parseChapters(detail: JsonObject, comicId: String): List<SChapter> {
        val array = detail["chapters"] as? JsonArray ?: return emptyList()
        return array
            .mapNotNull { it as? JsonObject }
            .mapNotNull { obj ->
                val chapterId = primitiveString(obj["id"]) ?: return@mapNotNull null
                val chapterName = primitiveString(obj["name"]) ?: return@mapNotNull null
                val order = primitiveInt(obj["order"]) ?: 0
                val createdAt = primitiveString(obj["created_at"])
                val createdTime = parseTimestamp(createdAt)
                val type = primitiveInt(obj["type"]) ?: 0

                ChapterEntry(
                    chapter = SChapter.create().apply {
                        url = "$comicId:$chapterId"
                        name = chapterName
                        chapter_number = order.toFloat().takeIf { order > 0 } ?: parseChapterNumber(chapterName)
                        date_upload = createdTime
                    },
                    order = order,
                    createdTime = createdTime,
                    type = type,
                    id = chapterId.toLongOrNull() ?: 0L,
                )
            }
            .distinctBy { it.chapter.url }
            .sortedWith(
                compareByDescending<ChapterEntry> { it.order }
                    .thenByDescending { it.createdTime }
                    .thenBy { it.type }
                    .thenByDescending { it.id },
            )
            .map { it.chapter }
    }

    private suspend fun getRuntimeConfig(force: Boolean = false): RuntimeConfig {
        if (!force) runtimeConfig?.let { return it }

        val dynamicCandidates = discoverApiBases()
        val candidates = buildList {
            addAll(dynamicCandidates)
            if (dynamicCandidates.isEmpty()) addAll(discoverBootstrapApiBases())
            addAll(LAST_KNOWN_API_BASES)
        }.map(::normalizeBaseUrl).distinct()

        var lastError: Throwable? = null
        for (candidate in candidates) {
            val result = runCatching {
                val baseConfig = RuntimeConfig(candidate, DEFAULT_IMAGE_BASE, JsonObject(emptyMap()))
                // app/api/config doubles as the availability check; avoid a redundant health round-trip.
                val apiConfig = getJson(apiUrl(baseConfig, CONFIG_PATH))
                val imageBase = parseImageBase(apiConfig)
                RuntimeConfig(candidate, imageBase, apiConfig)
            }
            if (result.isSuccess) {
                return result.getOrThrow().also { runtimeConfig = it }
            }
            lastError = result.exceptionOrNull()
        }

        throw IOException("栗子漫画动态 API 寻址失败", lastError)
    }

    private suspend fun discoverApiBases(): List<String> {
        val result = mutableListOf<String>()
        for (host in DISCOVERY_HOSTS) {
            for (resolver in DOH_RESOLVERS) {
                val candidate = runCatching {
                    val url = "https://$resolver/resolve".toHttpUrl().newBuilder()
                        .addQueryParameter("name", host)
                        .addQueryParameter("type", "txt")
                        .build()
                    val response = client.get(
                        url.toString(),
                        headers.newBuilder().set("Accept", "application/dns-json").build(),
                    )
                    if (!response.isSuccessful) {
                        val code = response.code
                        response.close()
                        throw IOException("DoH HTTP $code")
                    }
                    val root = response.use { json.parseToJsonElement(it.body.string()) }
                    val answers = (root as? JsonObject)?.get("Answer") as? JsonArray ?: return@runCatching emptyList<String>()
                    answers.mapNotNull { answer ->
                        val obj = answer as? JsonObject ?: return@mapNotNull null
                        if (primitiveInt(obj["type"]) != 16) return@mapNotNull null
                        val ciphertext = primitiveString(obj["data"])
                            ?.trim()
                            ?.trim('"')
                            ?.takeIf(String::isNotBlank)
                            ?: return@mapNotNull null
                        runCatching { decryptAes256Ecb(ciphertext) }
                            .getOrNull()
                            ?.trim()
                            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    }
                }.getOrDefault(emptyList())
                if (candidate.isNotEmpty()) return candidate.distinct()
            }
        }
        return result.distinct()
    }

    private suspend fun discoverBootstrapApiBases(): List<String> {
        val response = runCatching { client.get(BOOTSTRAP_URL, headers) }.getOrNull() ?: return emptyList()
        if (!response.isSuccessful) {
            response.close()
            return emptyList()
        }
        val encrypted = response.use { it.body.string() }
        val plain = runCatching { decryptAes256Ecb(encrypted.trim()) }.getOrNull() ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(plain) }.getOrNull() ?: return emptyList()
        return collectStringsByKey(root, setOf("domain", "api", "api_domain", "apiDomain"))
            .filter { it.startsWith("http://") || it.startsWith("https://") || it.contains('.') }
            .distinct()
    }

    private suspend fun healthCheck(apiBase: String): Boolean {
        val url = normalizeBaseUrl(apiBase).toHttpUrl().newBuilder()
            .addPathSegments(HEALTH_PATH)
            .build()
        return runCatching {
            val response = client.get(url.toString(), headers)
            if (!response.isSuccessful) {
                response.close()
                false
            } else {
                val text = response.use { it.body.string() }
                val root = json.parseToJsonElement(text) as? JsonObject
                (root?.get("status") as? JsonPrimitive)?.booleanOrNull ?: true
            }
        }.getOrDefault(false)
    }

    private fun parseImageBase(apiConfig: JsonElement): String {
        val data = dataObject(apiConfig)
        val general = data?.get("cfg_general") as? JsonObject
        val generators = (general?.get("img_generator") as? JsonObject)
            ?.get("generators") as? JsonArray

        val available = generators.orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { obj ->
                val needsPermission = (obj["need_permission"] as? JsonPrimitive)?.booleanOrNull ?: false
                val raw = primitiveString(obj["url"]) ?: return@mapNotNull null
                if (needsPermission || raw.equals("www.baidu.com", true)) return@mapNotNull null
                normalizeBaseUrl(raw)
            }
            .distinct()

        val preferred = FAST_IMAGE_BASE_PRIORITY.firstOrNull { preferredBase ->
            available.any { it.equals(preferredBase, ignoreCase = true) }
        }
        return preferred ?: available.firstOrNull() ?: DEFAULT_IMAGE_BASE
    }

    private fun decryptAes256Ecb(encoded: String): String = try {
        val encrypted = Base64.decode(encoded.trim(), Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(DISCOVERY_AES_KEY.toByteArray(StandardCharsets.UTF_8), "AES"),
        )
        String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    } catch (e: Exception) {
        throw IOException("栗子漫画线路数据解密失败", e)
    }

    private suspend fun getApiJson(config: RuntimeConfig, path: String): JsonElement = getJson(apiUrl(config, path))

    private suspend fun getJson(url: HttpUrl): JsonElement {
        val response = client.get(url.toString(), headers)
        if (!response.isSuccessful) {
            val code = response.code
            val message = response.use { it.body.string().take(160) }
            throw IOException("栗子漫画接口 HTTP $code${message.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}")
        }
        val text = response.use { it.body.string() }
        return runCatching { json.parseToJsonElement(text) }
            .getOrElse { throw IOException("栗子漫画接口返回格式异常", it) }
    }

    private fun apiUrl(config: RuntimeConfig, path: String): HttpUrl = config.apiBase.toHttpUrl().newBuilder()
        .addPathSegments(path.trimStart('/'))
        .build()

    private fun dataObject(root: JsonElement): JsonObject? = (root as? JsonObject)?.get("data") as? JsonObject

    private fun primitiveString(element: JsonElement?): String? = (element as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private fun primitiveInt(element: JsonElement?): Int? = (element as? JsonPrimitive)?.intOrNull
        ?: primitiveString(element)?.toIntOrNull()

    private fun collectStringsByKey(root: JsonElement, keys: Set<String>): List<String> {
        val result = mutableListOf<String>()
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (key in keys) primitiveString(value)?.let(result::add)
                    walk(value)
                }
                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return result
    }

    private fun resolveImageUrl(base: String, value: String): String? {
        val raw = value.trim()
        if (raw.isBlank()) return null
        if (raw.startsWith("https://") || raw.startsWith("http://")) return raw
        if (raw.startsWith("//")) return "https:$raw"
        return "${base.trimEnd('/')}/${raw.trimStart('/')}"
    }

    private fun normalizeBaseUrl(value: String): String = value.trim().trim('"', '\'', ' ').trimEnd('/').let { raw ->
        when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("//") -> "https:$raw"
            else -> "https://$raw"
        }
    }

    private fun parseChapterNumber(name: String): Float = Regex("""(?:第\s*)?(\d+(?:\.\d+)?)\s*(?:话|話|章|回|卷)?""")
        .find(name)
        ?.groupValues
        ?.getOrNull(1)
        ?.toFloatOrNull()
        ?: -1f

    private fun parseTimestamp(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        value.toLongOrNull()?.let { return if (it in 1..9_999_999_999L) it * 1000L else it }
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.CHINA).parse(value)?.time }.getOrNull()
        } ?: 0L
    }

    private data class RuntimeConfig(
        val apiBase: String,
        val imageBase: String,
        val apiConfig: JsonElement,
    )

    private data class ChapterEntry(
        val chapter: SChapter,
        val order: Int,
        val createdTime: Long,
        val type: Int,
        val id: Long,
    )

    private data class TagOption(val name: String, val value: String) {
        override fun toString(): String = name
    }

    private data class ClassOption(val name: String, val id: Int) {
        override fun toString(): String = name
    }

    private data class StatusOption(val name: String, val id: Int) {
        override fun toString(): String = name
    }

    private class TagFilter(options: Array<TagOption>) : Filter.Select<TagOption>("标签", options) {
        val selectedValue: String get() = values[state].value
    }

    private class ClassFilter(options: Array<ClassOption>) : Filter.Select<ClassOption>("地区", options) {
        val selectedId: Int get() = values[state].id
    }

    private class StatusFilter(options: Array<StatusOption>) : Filter.Select<StatusOption>("状态", options) {
        val selectedId: Int get() = values[state].id
    }

    companion object {
        private const val OFFICIAL_SITE = "https://lizimh.com"
        private const val OFFICIAL_PACKAGE = "com.hbsclj.uth"
        private const val OFFICIAL_APP_SIGN_SHA256 =
            "CDD266DF8B2399C24DA38E408B6D9825C7BD2AF073229847F551EA653EA096E1"
        private const val APP_USER_AGENT = "Dart/3.5 (dart:io)"

        private const val DISCOVERY_AES_KEY = "f8d992c74b29491d8a3e3fd5f07389d8"
        private val DISCOVERY_HOSTS = listOf("adnet.lizimh.com", "adnet.lizi.lat")
        private val DOH_RESOLVERS = listOf(
            "doh.pub",
            "dns.alidns.com",
            "223.5.5.5",
            "120.53.53.53",
            "doh.360.cn",
            "dns.google",
        )

        private const val BOOTSTRAP_URL =
            "https://lz-1382057604.cos.ap-hongkong.myqcloud.com/5A6F4D2B7E9A1C3D8F0B2E4A6C8E0D2F4B6A8C0E2D4F6A8B0C2E4D6F8A0C2E4B.json"
        private val LAST_KNOWN_API_BASES = listOf("http://crm.weichu.asia", "http://ai.xajtl.com")
        private val FAST_IMAGE_BASE_PRIORITY = listOf(
            "https://p.imgo.buzz",
            "https://i.lzimg.xyz",
            "https://cf-1.imgio.club",
            "https://img.kunmu.asia",
        )
        private const val DEFAULT_IMAGE_BASE = "https://p.imgo.buzz"

        private const val HEALTH_PATH = "app/api/health"
        private const val HOME_PATH = "app/api/home/data"
        private const val CONFIG_PATH = "app/api/config"
        private const val CATEGORY_PATH = "app/api/category/list"
        private const val SEARCH_PATH = "app/api/search/full"
        private const val DETAIL_PATH = "app/api/detail/"
        private const val CHAPTER_PATH = "app/api/chapter/v2/"
    }
}