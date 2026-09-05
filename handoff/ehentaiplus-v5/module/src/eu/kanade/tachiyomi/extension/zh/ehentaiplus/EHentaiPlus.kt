package eu.kanade.tachiyomi.extension.zh.ehentaiplus

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.widget.Toast
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
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
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Source
abstract class EHentaiPlus :
    KeiSource(),
    ConfigurableSource,
    CommentSource,
    AccountSource,
    MangaDetailSource {

    private val preferences: SharedPreferences = getPreferences()
    private val webViewCookies: CookieManager by lazy { CookieManager.getInstance() }
    private val detailCache = ConcurrentHashMap<String, CachedDetail>()
    private val imageUrlCache = ConcurrentHashMap<String, CachedImageUrl>()
    private val translationMutex = Mutex()
    private val translationMap = ConcurrentHashMap<String, String>()

    @Volatile private var translationLoadAttempted = false

    private var lastGalleryId = ""

    @Volatile private var webLoginWatchGeneration = 0

    override val baseUrl: String
        get() = when {
            System.getenv("CI") == "true" -> EH_URL
            siteMode() == "eh" -> EH_URL
            siteMode() == "ex" -> EX_URL
            isLoggedIn() && preferences.getBoolean(PREF_EX_ACCESS, false) -> EX_URL
            else -> EH_URL
        }

    override fun getHomeUrl(): String = if (isLoggedIn()) baseUrl else FORUM_LOGIN_URL

    override fun Headers.Builder.configureHeaders() = apply {
        set("User-Agent", USER_AGENT)
        set("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
    }

    override fun OkHttpClient.Builder.configureClient() = apply {
        connectTimeout(12, TimeUnit.SECONDS)
        readTimeout(60, TimeUnit.SECONDS)
        addInterceptor { chain ->
            val request = chain.request()
            val isImageRequest = request.header(IMAGE_REQUEST_HEADER) == "1"
            val viewerUrl = request.header(IMAGE_VIEWER_HEADER)
            val host = request.url.host.lowercase(Locale.ROOT)
            val builder = request.newBuilder()
                .removeHeader(IMAGE_REQUEST_HEADER)
                .removeHeader(IMAGE_VIEWER_HEADER)
            if (host in EH_COOKIE_HOSTS || host.endsWith(".e-hentai.org") || host == "exhentai.org") {
                syncWebViewCookies(save = true, onlyMissing = true)
                val cookie = cookieHeader()
                if (cookie.isNotBlank()) builder.header("Cookie", cookie)
                if (request.header("Referer").isNullOrBlank()) {
                    val referer = if (host.contains("forums")) {
                        FORUMS_URL
                    } else if (host == "exhentai.org") {
                        "$EX_URL/"
                    } else {
                        "$EH_URL/"
                    }
                    builder.header("Referer", referer)
                }
            }
            val response = chain.proceed(builder.build())
            captureSetCookies(response)
            when {
                !isImageRequest -> response
                isDecodableImageResponse(response) -> response
                else -> {
                    val firstFailure = imageFailureDescription(response)
                    response.close()
                    retryImageResponse(chain, viewerUrl, firstFailure)
                }
            }
        }
    }

    private fun isDecodableImageResponse(response: Response): Boolean {
        if (!response.isSuccessful) return false
        val contentType = response.header("Content-Type").orEmpty().substringBefore(';').trim()
        if (!contentType.startsWith("image/", ignoreCase = true)) return false
        return hasImageMagic(response)
    }

    private fun hasImageMagic(response: Response): Boolean = runCatching {
        val bytes = response.body.source().peek().readByteArray(16)
        fun b(index: Int) = bytes[index].toInt() and 0xFF
        when {
            bytes.size >= 3 && b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF -> true // JPEG
            bytes.size >= 8 && b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47 && b(4) == 0x0D && b(5) == 0x0A && b(6) == 0x1A && b(7) == 0x0A -> true // PNG
            bytes.size >= 6 && b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x38 && (b(4) == 0x37 || b(4) == 0x39) && b(5) == 0x61 -> true // GIF
            bytes.size >= 12 && b(0) == 0x52 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x46 && b(8) == 0x57 && b(9) == 0x45 && b(10) == 0x42 && b(11) == 0x50 -> true // WebP
            bytes.size >= 12 && b(4) == 0x66 && b(5) == 0x74 && b(6) == 0x79 && b(7) == 0x70 -> true // AVIF/HEIF family
            else -> false
        }
    }.getOrDefault(false)

    private fun imageFailureDescription(response: Response): String {
        val contentType = response.header("Content-Type").orEmpty().substringBefore(';').trim().ifBlank { "未知 Content-Type" }
        return "HTTP ${response.code} · $contentType · ${response.request.url.host}"
    }

    private fun retryImageResponse(chain: Interceptor.Chain, viewerUrl: String?, firstFailure: String): Response {
        val viewer = viewerUrl?.takeIf(String::isNotBlank)
            ?: throw IOException("正文图片无效：$firstFailure；缺少 viewer 地址，无法自动切换图片服务器")
        val firstDoc = fetchViewerDocument(chain, viewer)
        val reloadToken = Regex("""nl\('([^']+)'\)""")
            .find(firstDoc.selectFirst("#loadfail")?.attr("onclick").orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?: throw IOException("正文图片无效：$firstFailure；当前页面没有备用图片服务器 token")
        val retryViewerUrl = Uri.parse(viewer).buildUpon().appendQueryParameter("nl", reloadToken).build().toString()
        val retryDoc = fetchViewerDocument(chain, retryViewerUrl)
        val retryImageUrl = retryDoc.selectFirst("#img")?.attr("abs:src")?.takeIf(String::isNotBlank)
            ?: throw IOException("正文图片无效：$firstFailure；备用 viewer 未返回图片地址")
        if (retryImageUrl == "https://ehgt.org/g/509.gif" || retryImageUrl == "https://exhentai.org/img/509.gif") {
            throw IOException("E-Hentai 图片配额已用尽，请稍后再试或在 My Home 检查 Image Limits")
        }
        val retryRequest = buildRawImageRequest(retryImageUrl, retryViewerUrl)
        val retryResponse = chain.proceed(retryRequest)
        captureSetCookies(retryResponse)
        if (isDecodableImageResponse(retryResponse)) return retryResponse
        val retryFailure = imageFailureDescription(retryResponse)
        retryResponse.close()
        throw IOException("正文图片服务器返回无效数据：首次 $firstFailure；备用线路 $retryFailure")
    }

    private fun fetchViewerDocument(chain: Interceptor.Chain, url: String): Document {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Cache-Control", "no-cache")
            .apply {
                cookieHeader().takeIf(String::isNotBlank)?.let { header("Cookie", it) }
            }
            .build()
        return chain.proceed(request).use { response ->
            captureSetCookies(response)
            if (!response.isSuccessful) throw IOException("备用 viewer 请求失败：HTTP ${response.code}")
            Jsoup.parse(response.body.string(), response.request.url.toString())
        }
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val language = if (chineseOnly()) "language:chinese" else ""
        val url = buildString {
            append(baseUrl)
            append("/?f_srdd=5&f_sr=on")
            if (language.isNotBlank()) append("&f_search=${enc(language)}")
        }
        return fetchList(exGetRequest(url, page), chineseOnly())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchList(exGetRequest(baseUrl, page), chineseOnly())

    private suspend fun fetchList(request: Request, enforceChinese: Boolean): MangasPage {
        val response = client.newCall(request).awaitSuccess()
        val result = EHParser.parseMangaList(response, enforceChinese)
        lastGalleryId = result.lastGalleryId
        if (baseUrl == EX_URL && result.mangas.isEmpty() && siteMode() == "auto") {
            preferences.edit().putBoolean(PREF_EX_ACCESS, false).apply()
        }
        return MangasPage(result.mangas, result.hasNextPage)
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Header(accountFilterStatus()),
        ChineseOnlyFilter(chineseOnly()),
        FavoritesFilter(),
        WatchedFilter(),
        CategoryGroup(),
        Filter.Header("标签可用英文原标签；多个标签用英文逗号分隔，前加 - 表示排除"),
        TagTextFilter("全部标签", "tag"),
        TagTextFilter("女性标签", "female"),
        TagTextFilter("男性标签", "male"),
        AdvancedGroup(),
    )

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        directGalleryPath(query)?.let { path ->
            val response = client.get(baseUrl + path)
            val manga = EHParser.parseDetails(response).manga
            return MangasPage(listOf(manga), false)
        }

        val onlyChinese = filters.filterIsInstance<ChineseOnlyFilter>().firstOrNull()?.state ?: chineseOnly()
        var modifiedQuery = query.trim()
        if (onlyChinese) modifiedQuery = listOf(modifiedQuery, "language:chinese").filter(String::isNotBlank).joinToString(" ")

        filters.filterIsInstance<TagTextFilter>().forEach { filter ->
            filter.state.split(',').map(String::trim).filter(String::isNotBlank).forEach { raw ->
                val exclude = raw.startsWith('-')
                val name = raw.removePrefix("-").trim().lowercase(Locale.ROOT)
                val fragment = "${filter.namespace}:\"$name\""
                modifiedQuery += if (exclude) " -$fragment" else " $fragment"
            }
        }

        val baseSearch = "$baseUrl/?f_apply=Apply+Filter&f_search=${enc(modifiedQuery)}"
        val uri = Uri.parse(baseSearch).buildUpon()
        filters.forEach { if (it is UriFilter) it.addToUri(uri) }

        if (uri.toString().contains("f_spf") || uri.toString().contains("f_spt")) {
            if (page > 1 && lastGalleryId.isNotBlank()) uri.appendQueryParameter("from", lastGalleryId)
        }

        return fetchList(exGetRequest(uri.toString(), page), onlyChinese)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host !in setOf("e-hentai.org", "exhentai.org") || url.pathSegments.firstOrNull() != "g") return null
        return EHParser.parseDetails(client.get(url)).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)
        val fresh = detail(manga)
        val chapterList = if (fetchChapters) {
            listOf(
                SChapter.create().apply {
                    url = readerChapterUrl(fresh.manga.url)
                    name = fresh.metadata.length?.let { "画廊正文 · $it 页" } ?: "画廊正文"
                    chapter_number = 1f
                    date_upload = fresh.metadata.datePosted ?: 0L
                },
            )
        } else {
            chapters
        }
        return SMangaUpdate(if (fetchDetails) fresh.manga else manga, chapterList)
    }

    private suspend fun detail(manga: SManga): DetailBundle {
        val key = normalizeGalleryUrl(manga.url) ?: manga.url
        detailCache[key]?.takeIf { System.currentTimeMillis() - it.time < DETAIL_CACHE_MS }?.let { return it.value }
        val value = EHParser.parseDetails(client.get(getMangaUrl(manga)))
        detailCache[key] = CachedDetail(System.currentTimeMillis(), value)
        return value
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val viewers = mutableListOf<String>()
        var next: String? = getChapterUrl(chapter)
        var guard = 0
        while (!next.isNullOrBlank() && guard++ < 80) {
            val doc = client.get(next).asJsoup()
            viewers += EHParser.parseChapterPage(doc)
            next = EHParser.nextGalleryPage(doc)
        }
        if (viewers.isEmpty()) throw IOException("没有解析到正文页面；如果使用里站，请先验证登录状态")
        return viewers.distinct().mapIndexed { index, viewerUrl ->
            Page(index, viewerUrl)
        }
    }

    override suspend fun getImageUrl(page: Page): String {
        imageUrlCache[page.url]
            ?.takeIf { System.currentTimeMillis() - it.time < IMAGE_URL_CACHE_MS }
            ?.let { return it.url }

        val request = Request.Builder()
            .url(page.url)
            .headers(headers)
            .build()
        val imageUrl = client.newCall(request).awaitSuccess().use { response ->
            decorateImageUrl(EHParser.parseImageInfo(response, originalImages()).imageUrl)
        }
        imageUrlCache[page.url] = CachedImageUrl(System.currentTimeMillis(), imageUrl)
        return imageUrl
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: throw IllegalStateException("正文图片地址尚未解析")
        return buildImageRequest(imageUrl, page.url)
    }

    private fun buildImageRequest(imageUrl: String, referer: String): Request = buildRawImageRequest(networkImageUrl(imageUrl), referer)
        .newBuilder()
        .header(IMAGE_REQUEST_HEADER, "1")
        .header(IMAGE_VIEWER_HEADER, referer)
        .build()

    private fun buildRawImageRequest(imageUrl: String, referer: String): Request = Request.Builder()
        .url(imageUrl)
        .header("Referer", referer)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        .apply {
            cookieHeader().takeIf(String::isNotBlank)?.let { header("Cookie", it) }
        }
        .build()

    private fun decorateImageUrl(raw: String): String {
        val url = raw.toHttpUrlOrNull() ?: return raw
        return url.newBuilder().fragment("mxeh-$READER_REVISION").build().toString()
    }

    private fun networkImageUrl(raw: String): String {
        val url = raw.toHttpUrlOrNull() ?: return raw
        return url.newBuilder().fragment(null).build().toString()
    }

    private fun readerChapterUrl(raw: String): String = Uri.parse(raw)
        .buildUpon()
        .appendQueryParameter("mx_reader", READER_REVISION)
        .build()
        .toString()

    private fun exGetRequest(url: String, page: Int): Request {
        val finalUrl = if (page > 1 && lastGalleryId.isNotBlank()) {
            Uri.parse(url).buildUpon().appendQueryParameter("next", lastGalleryId).toString()
        } else {
            url
        }
        return Request.Builder().url(finalUrl).headers(headers).build()
    }

    override suspend fun getMangaDetailInfo(manga: SManga): MangaDetailInfo {
        val bundle = detail(manga)
        val m = bundle.metadata
        val translated = translateTags(m.tags)
        val fields = buildList {
            m.altTitle?.takeIf { it != m.title }?.let { add(textField("原文标题", it)) }
            m.category?.let { add(textField("分类", categoryZh(it))) }
            m.uploader?.let { uploader ->
                add(
                    MangaDetailField(
                        "上传者",
                        listOf(MangaDetailValue(uploader, MangaDetailAction(MangaDetailActionType.SOURCE_SEARCH, "uploader:\"$uploader\""))),
                    ),
                )
            }
            m.language?.let { add(textField("语言", languageZh(it) + if (m.translated) " · 已翻译" else "")) }
            m.datePosted?.let {
                val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneOffset.UTC).format(EH_DATE_FORMATTER)
                add(textField("发布时间", "$time UTC"))
            }
            m.visible?.let { add(textField("可见性", it)) }
            m.size?.let { add(textField("文件大小", humanReadableByteCount(it, true))) }
            m.length?.let { add(textField("页数", it.toString())) }
            m.favorites?.let { add(textField("收藏次数", it.toString())) }
            m.averageRating?.let { rating -> add(textField("评分", "$rating${m.ratingCount?.let { " · $it 人评分" }.orEmpty()}")) }
            add(textField("画廊 ID", galleryId(m.url).orEmpty()))
            add(textField("当前站点", if (baseUrl == EX_URL) "ExHentai（里站）" else "E-Hentai（表站）"))

            m.tags.forEach { (namespace, tags) ->
                val values = tags.map { tag ->
                    val rawKey = "$namespace:${tag.name.lowercase(Locale.ROOT)}"
                    val display = translated[rawKey] ?: tag.name
                    val suffix = when {
                        tag.weak -> " ⚠"
                        tag.light -> " ◇"
                        else -> ""
                    }
                    MangaDetailValue(
                        display + suffix,
                        MangaDetailAction(MangaDetailActionType.SOURCE_SEARCH, "$namespace:\"${tag.name}\""),
                    )
                }
                if (values.isNotEmpty()) add(MangaDetailField(namespaceZh(namespace), values))
            }

            m.parent?.let { parent ->
                add(MangaDetailField("父版本", listOf(MangaDetailValue(parent.title, MangaDetailAction(MangaDetailActionType.WEB_URL, absoluteGalleryUrl(parent.url))))))
            }
            if (m.newerVersions.isNotEmpty()) {
                add(
                    MangaDetailField(
                        "更新版本",
                        m.newerVersions.map { related -> MangaDetailValue(related.title, MangaDetailAction(MangaDetailActionType.WEB_URL, absoluteGalleryUrl(related.url))) },
                    ),
                )
            }
        }
        return MangaDetailInfo(fields = fields, replaceDefaultFields = true)
    }

    private fun textField(label: String, value: String) = MangaDetailField(label, listOf(MangaDetailValue(value)))

    private fun absoluteGalleryUrl(raw: String): String {
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        return baseUrl + (normalizeGalleryUrl(raw) ?: raw)
    }

    private suspend fun translateTags(tags: Map<String, List<EHTag>>): Map<String, String> {
        if (!translateTagsEnabled()) return emptyMap()
        val keys = tags.flatMap { (ns, list) -> list.map { "$ns:${it.name.lowercase(Locale.ROOT)}" } }.distinct()
        val result = mutableMapOf<String, String>()
        val missing = mutableListOf<String>()
        keys.forEach { key ->
            preferences.getString(PREF_TRANSLATION_CACHE_PREFIX + key, null)?.let { result[key] = it } ?: missing.add(key)
        }
        if (missing.isEmpty()) return result

        ensureTranslationDatabase()
        val editor = preferences.edit()
        missing.forEach { key ->
            translationMap[key]?.takeIf(String::isNotBlank)?.let { value ->
                result[key] = value
                editor.putString(PREF_TRANSLATION_CACHE_PREFIX + key, value)
            }
        }
        editor.apply()
        return result
    }

    private suspend fun ensureTranslationDatabase() {
        if (translationLoadAttempted) return
        translationMutex.withLock {
            if (translationLoadAttempted) return@withLock
            translationLoadAttempted = true
            runCatching {
                val request = Request.Builder().url(TAG_TRANSLATION_URL).header("User-Agent", USER_AGENT).build()
                val raw = client.newCall(request).awaitSuccess().use { it.body.string() }
                val root = JSONObject(raw)
                val namespaces = root.getJSONArray("data")
                for (i in 0 until namespaces.length()) {
                    val block = namespaces.optJSONObject(i) ?: continue
                    val namespace = block.optString("namespace").lowercase(Locale.ROOT)
                    val data = block.optJSONObject("data") ?: continue
                    val iterator = data.keys()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        val item = data.optJSONObject(key) ?: continue
                        val rawName = item.optString("name")
                        val name = Regex(">([^<>]+)<").findAll(rawName).lastOrNull()?.groupValues?.getOrNull(1)
                            ?.let { Jsoup.parseBodyFragment(it).text() }
                            ?.trim()
                            ?.takeIf(String::isNotBlank)
                            ?: Jsoup.parseBodyFragment(rawName).text().trim().takeIf(String::isNotBlank)
                            ?: continue
                        translationMap["$namespace:${key.lowercase(Locale.ROOT)}"] = name
                    }
                }
            }
        }
    }

    override val commentCapabilities: CommentCapabilities
        get() = CommentCapabilities(
            supportsMangaComments = true,
            supportsChapterComments = false,
            canPost = sessionValidated(),
            canReply = false,
            canLike = false,
            requiresLoginToPost = false,
        )

    override suspend fun getMangaCommentTarget(manga: SManga): CommentTarget = CommentTarget(
        id = normalizeGalleryUrl(manga.url) ?: manga.url,
        url = getMangaUrl(manga),
        kind = CommentTargetKind.MANGA,
    )

    override suspend fun getComments(target: CommentTarget, page: Int): CommentPage {
        if (page > 1) return CommentPage(emptyList(), false, 0)
        val base = target.url ?: absoluteGalleryUrl(target.id)
        val url = base.toHttpUrlOrNull()?.newBuilder()?.setQueryParameter("hc", if (showAllComments()) "1" else "0")?.build()?.toString() ?: base
        val doc = client.get(url).asJsoup()
        val comments = EHParser.parseComments(doc).map { parsed ->
            Comment(
                id = parsed.id,
                author = CommentAuthor(
                    id = parsed.userId,
                    name = parsed.author,
                    profileUrl = parsed.userId?.let { "$FORUMS_URL/index.php?showuser=$it" },
                ),
                content = parsed.content,
                createdAt = parsed.createdAt,
                displayTime = parsed.displayTime,
                likeCount = parsed.score,
            )
        }
        return CommentPage(comments, false, comments.size.toLong())
    }

    override suspend fun postComment(target: CommentTarget, content: String): Comment {
        if (!sessionValidated()) throw IOException("请先在 E-Hentai Plus 设置中登录并验证账号")
        val text = content.trim()
        if (text.isBlank()) throw IOException("评论内容不能为空")
        val url = target.url ?: absoluteGalleryUrl(target.id)
        val request = Request.Builder()
            .url(url)
            .post(FormBody.Builder().add("commenttext_new", text).build())
            .header("Referer", url)
            .build()
        val response = client.newCall(request).awaitSuccess()
        val doc = response.asJsoup()
        doc.selectFirst("p.br")?.text()?.trim()?.takeIf(String::isNotBlank)?.let { throw IOException(it) }

        val fresh = runCatching { getComments(target, 1).comments.firstOrNull { it.content == text } }.getOrNull()
        return fresh ?: Comment(
            id = "pending-${System.currentTimeMillis()}",
            author = currentCommentAuthor(),
            content = text,
            createdAt = System.currentTimeMillis(),
        )
    }

    private suspend fun currentCommentAuthor(): CommentAuthor {
        val account = runCatching { getSourceAccount() }.getOrNull()
        return CommentAuthor(account?.id, account?.name ?: "我", account?.avatarUrl, account?.profileUrl)
    }

    override suspend fun getSourceAccount(): SourceAccount? {
        syncWebViewCookies(save = true)
        val id = memberId()
        if (id.isBlank() || passHash().isBlank()) return null
        if (!sessionValidated()) {
            runCatching { validateSession(syncFromWebView = false) }.getOrElse { return null }
        }
        val cachedName = preferences.getString(PREF_ACCOUNT_NAME, "").orEmpty()
        val cachedAvatar = preferences.getString(PREF_ACCOUNT_AVATAR, "").orEmpty().takeIf(String::isNotBlank)
        if (cachedName.isNotBlank()) {
            return SourceAccount(id, cachedName, cachedAvatar, "$FORUMS_URL/index.php?showuser=$id")
        }
        return runCatching { fetchForumProfile(id, true) }.getOrElse {
            SourceAccount(id, "EH 用户 #$id", null, "$FORUMS_URL/index.php?showuser=$id")
        }
    }

    private suspend fun fetchForumProfile(id: String, persist: Boolean): SourceAccount {
        val url = "$FORUMS_URL/index.php?showuser=${enc(id)}"
        val doc = client.get(url).asJsoup()
        val name = doc.selectFirst(".home > b > a")?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: doc.selectFirst("#profilename")?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: "EH 用户 #$id"
        val avatar = doc.selectFirst("#profilename")?.parent()?.selectFirst("img[src]")?.attr("abs:src")?.takeIf(String::isNotBlank)
        if (persist) preferences.edit().putString(PREF_ACCOUNT_NAME, name).putString(PREF_ACCOUNT_AVATAR, avatar.orEmpty()).apply()
        return SourceAccount(id, name, avatar, url)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val accountStatusPreference = Preference(screen.context).apply {
            key = "ehp_account_status"
            title = accountStatusTitle()
            summary = accountStatusSummary()
        }.also(screen::addPreference)
        accountStatusPreference.setOnPreferenceClickListener {
            refreshAccountFromWebView(screen, accountStatusPreference, showToastOnResult = true)
            true
        }

        Preference(screen.context).apply {
            key = "ehp_web_login"
            title = "网页登录（推荐）"
            summary = "打开宿主内置 WebView 的 E-Hentai 论坛登录页；登录成功后自动获取 Cookie、验证账号并刷新上方状态"
            setOnPreferenceClickListener {
                openWebLogin(screen, accountStatusPreference)
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SITE_MODE
            title = "站点模式"
            entries = arrayOf("自动（有里站权限时优先 ExHentai）", "E-Hentai 表站", "ExHentai 里站")
            entryValues = arrayOf("auto", "eh", "ex")
            setDefaultValue("auto")
            summary = "%s"
        }.also(screen::addPreference)

        CheckBoxPreference(screen.context).apply {
            key = PREF_CHINESE_ONLY
            title = "默认仅中文内容"
            summary = "浏览和搜索默认加入 language:chinese；搜索筛选中可临时关闭"
            setDefaultValue(true)
        }.also(screen::addPreference)

        CheckBoxPreference(screen.context).apply {
            key = PREF_TRANSLATE_TAGS
            title = "详情页标签中文翻译"
            summary = "默认开启；首次遇到未缓存标签时读取 EhTagTranslation 数据库，失败会保留英文原标签"
            setDefaultValue(true)
        }.also(screen::addPreference)

        CheckBoxPreference(screen.context).apply {
            key = PREF_ALL_COMMENTS
            title = "评论页读取全部评论"
            summary = "开启后使用 hc=1；否则站点默认只返回高分评论和少量最新评论"
            setDefaultValue(true)
        }.also(screen::addPreference)

        CheckBoxPreference(screen.context).apply {
            key = PREF_ORIGINAL_IMAGE
            title = "优先原图"
            summary = "账号有权限时读取原图；会增加配额消耗和加载时间"
            setDefaultValue(false)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_USERNAME
            title = "论坛账号"
            summary = "用于密码登录；仅保存在本机"
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_PASSWORD
            title = "论坛密码"
            summary = "仅用于本次登录；登录成功后自动清除"
            setOnBindEditTextListener { it.inputType = 0x00000081 }
        }.also(screen::addPreference)

        actionPreference(screen, "ehp_login", "账号密码登录", "按 JHenTai 的官方论坛登录流程获取 ipb_member_id / ipb_pass_hash，并检测里站权限") {
            val result = runCatching { loginWithPassword() }
            showResult(screen, result, "登录成功")
            updateAccountStatusPreference(accountStatusPreference)
        }

        EditTextPreference(screen.context).apply {
            key = PREF_MEMBER_ID
            title = "ipb_member_id"
            summary = "也可直接粘贴 Cookie 值；填写完整后会自动同步到网页并尝试验证"
            installManualCookieListener(screen, accountStatusPreference)
        }.also(screen::addPreference)
        EditTextPreference(screen.context).apply {
            key = PREF_PASS_HASH
            title = "ipb_pass_hash"
            summary = "也可直接粘贴 Cookie 值；填写完整后会自动同步到网页并尝试验证"
            installManualCookieListener(screen, accountStatusPreference)
        }.also(screen::addPreference)
        EditTextPreference(screen.context).apply {
            key = PREF_IGNEOUS
            title = "igneous"
            summary = "里站常用 Cookie；修改后自动同步到宿主 WebView"
            installManualCookieListener(screen, accountStatusPreference)
        }.also(screen::addPreference)
        EditTextPreference(screen.context).apply {
            key = PREF_STAR
            title = "star"
            summary = "可选 Cookie，兼容 Venera / JHenTai 登录数据；修改后自动同步"
            installManualCookieListener(screen, accountStatusPreference)
        }.also(screen::addPreference)

        actionPreference(screen, "ehp_sync_webview", "重新读取网页登录 Cookie", "强制从宿主 WebView 覆盖同步 Cookie，并自动验证账号状态") {
            val result = runCatching { importAndValidateWebViewCookies(force = true) }
            showResult(screen, result, "网页登录检测完成")
            updateAccountStatusPreference(accountStatusPreference)
        }
        actionPreference(screen, "ehp_validate", "验证账号与里站权限", "读取论坛账号，并检测 ExHentai 是否可用") {
            val result = runCatching { validateSession() }
            showResult(screen, result, "验证完成")
            updateAccountStatusPreference(accountStatusPreference)
        }
        actionPreference(screen, "ehp_retry_translation", "重新加载中文标签库", "清除本次运行的标签库加载状态；下次进入详情页重新尝试") {
            translationMap.clear()
            translationLoadAttempted = false
            showToast(screen, "已重置；下次详情页会重新加载")
        }
        actionPreference(screen, "ehp_logout", "退出登录", "清除扩展保存的账号 Cookie 和登录状态") {
            logout()
            preferences.edit().putString(PREF_AUTH_RESULT, "已退出登录").apply()
            updateAccountStatusPreference(accountStatusPreference)
            showToast(screen, "已退出登录")
        }

        refreshAccountFromWebView(screen, accountStatusPreference, showToastOnResult = false)
    }

    private fun openWebLogin(screen: PreferenceScreen, accountStatusPreference: Preference) {
        val context = screen.context
        val intent = Intent().apply {
            setClassName(context.packageName, HOST_WEBVIEW_ACTIVITY)
            putExtra(HOST_WEBVIEW_URL_KEY, FORUM_LOGIN_URL)
            putExtra(HOST_WEBVIEW_SOURCE_KEY, id)
            putExtra(HOST_WEBVIEW_TITLE_KEY, "E-Hentai 账号登录")
        }
        val opened = runCatching { context.startActivity(intent) }.isSuccess
        if (!opened) {
            val text = "当前宿主无法从扩展直接打开内置 WebView。请回到漫画源页面点击“网页”，未登录时会直接进入论坛登录页；登录完成后再点本页登录状态刷新。"
            preferences.edit().putString(PREF_AUTH_RESULT, text).apply()
            updateAccountStatusPreference(accountStatusPreference)
            showToast(screen, text)
            return
        }
        preferences.edit()
            .putBoolean(PREF_SESSION_VALIDATED, false)
            .putString(PREF_AUTH_RESULT, "已打开网页登录，正在等待 Cookie…")
            .apply()
        updateAccountStatusPreference(accountStatusPreference)
        startWebLoginCookieWatch(screen, accountStatusPreference)
    }

    private fun startWebLoginCookieWatch(screen: PreferenceScreen, accountStatusPreference: Preference) {
        val generation = ++webLoginWatchGeneration
        var lastAttemptSignature = Int.MIN_VALUE
        var validationRunning = false

        fun poll(attempt: Int) {
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    if (generation != webLoginWatchGeneration) return@postDelayed
                    val webCookies = readWebViewAuthCookies()
                    val member = webCookies["ipb_member_id"].orEmpty()
                    val hash = webCookies["ipb_pass_hash"].orEmpty()
                    val signature = webCookies.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" }.hashCode()
                    if (member.isNotBlank() && hash.isNotBlank() && signature != lastAttemptSignature && !validationRunning) {
                        lastAttemptSignature = signature
                        saveWebViewAuthCookies(webCookies, onlyMissing = false)
                        preferences.edit()
                            .putBoolean(PREF_SESSION_VALIDATED, false)
                            .putString(PREF_AUTH_RESULT, "已自动获取网页登录 Cookie，正在验证账号…")
                            .apply()
                        updateAccountStatusPreference(accountStatusPreference)
                        validationRunning = true
                        client.dispatcher.executorService.execute {
                            val result = runCatching { validateSession(syncFromWebView = false) }
                            if (result.isSuccess) {
                                ++webLoginWatchGeneration
                                showResult(screen, result, "网页登录成功")
                                updateAccountStatusPreference(accountStatusPreference)
                            } else {
                                validationRunning = false
                                showResult(screen, result, "网页登录尚未完成")
                                updateAccountStatusPreference(accountStatusPreference)
                                if (generation == webLoginWatchGeneration && attempt < WEB_LOGIN_MAX_POLLS) poll(attempt + 1)
                            }
                        }
                    } else if (attempt < WEB_LOGIN_MAX_POLLS) {
                        poll(attempt + 1)
                    } else {
                        val text = if (member.isBlank() || hash.isBlank()) {
                            "网页登录等待超时，尚未检测到完整登录 Cookie；登录完成后点击上方“登录状态”即可重新检测。"
                        } else {
                            "检测到 Cookie，但自动验证未完成；点击上方“登录状态”可重新验证。"
                        }
                        preferences.edit().putString(PREF_AUTH_RESULT, text).apply()
                        updateAccountStatusPreference(accountStatusPreference)
                    }
                },
                WEB_LOGIN_POLL_MS,
            )
        }
        poll(0)
    }

    private fun refreshAccountFromWebView(
        screen: PreferenceScreen,
        accountStatusPreference: Preference,
        showToastOnResult: Boolean,
    ) {
        val webCookies = readWebViewAuthCookies()
        if (webCookies.isNotEmpty()) saveWebViewAuthCookies(webCookies, onlyMissing = false)
        updateAccountStatusPreference(accountStatusPreference)
        if (!isLoggedIn()) {
            if (showToastOnResult) showToast(screen, "未检测到完整登录 Cookie")
            return
        }
        client.dispatcher.executorService.execute {
            val result = runCatching { validateSession(syncFromWebView = false) }
            val prefix = if (showToastOnResult) "登录状态刷新" else "自动检测网页登录"
            val text = result.fold({ "$prefix：$it" }, { "${prefix}失败：${it.message}" })
            preferences.edit().putString(PREF_AUTH_RESULT, text).apply()
            updateAccountStatusPreference(accountStatusPreference)
            if (showToastOnResult) showToast(screen, text)
        }
    }

    private fun EditTextPreference.installManualCookieListener(screen: PreferenceScreen, accountStatusPreference: Preference) {
        setOnPreferenceChangeListener { _, _ ->
            Handler(Looper.getMainLooper()).post {
                promoteManualCookies()
                syncStoredCookiesToWebView()
                preferences.edit()
                    .putBoolean(PREF_SESSION_VALIDATED, false)
                    .putString(PREF_AUTH_RESULT, "手动 Cookie 已更新并同步到网页")
                    .apply()
                updateAccountStatusPreference(accountStatusPreference)
                if (isLoggedIn()) {
                    client.dispatcher.executorService.execute {
                        val result = runCatching { validateSession(syncFromWebView = false) }
                        showResult(screen, result, "手动 Cookie 自动验证")
                        updateAccountStatusPreference(accountStatusPreference)
                    }
                }
            }
            true
        }
    }

    private fun importAndValidateWebViewCookies(force: Boolean): String {
        val webCookies = readWebViewAuthCookies()
        if (webCookies.isEmpty()) throw IOException("没有检测到宿主 WebView Cookie")
        saveWebViewAuthCookies(webCookies, onlyMissing = !force)
        if (memberId().isBlank() || passHash().isBlank()) throw IOException("已读取 WebView Cookie，但缺少 ipb_member_id / ipb_pass_hash")
        preferences.edit().putBoolean(PREF_SESSION_VALIDATED, false).apply()
        return validateSession(syncFromWebView = false)
    }

    private fun loginWithPassword(): String {
        val username = preferences.getString(PREF_USERNAME, "").orEmpty().trim()
        val password = preferences.getString(PREF_PASSWORD, "").orEmpty()
        if (username.isBlank() || password.isBlank()) throw IOException("请输入论坛账号和密码")

        clearAuthCookies(clearManual = true)
        val form = FormBody.Builder()
            .add("referer", "$FORUMS_URL/index.php?")
            .add("b", "")
            .add("bt", "")
            .add("UserName", username)
            .add("PassWord", password)
            .add("CookieDate", "365")
            .build()
        val request = Request.Builder()
            .url("$FORUMS_URL/index.php?act=Login&CODE=01")
            .header("Referer", "$FORUMS_URL/index.php?act=Login&CODE=00")
            .post(form)
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw IOException("论坛登录 HTTP ${response.code}")
            if (memberId().isBlank() || passHash().isBlank()) {
                val error = Jsoup.parse(body).selectFirst(".errorwrap,.message,.maintitle + div")?.text()?.trim()
                throw IOException(error?.takeIf(String::isNotBlank) ?: "账号或密码错误，未获取到登录 Cookie")
            }
        }
        preferences.edit().remove(PREF_PASSWORD).apply()
        val result = validateSession(syncFromWebView = false)
        syncStoredCookiesToWebView()
        return result
    }

    private fun validateSession(syncFromWebView: Boolean = true): String {
        if (syncFromWebView) {
            promoteManualCookies()
            syncWebViewCookies(save = true, onlyMissing = true)
        }
        val id = memberId()
        if (id.isBlank() || passHash().isBlank()) {
            preferences.edit().putBoolean(PREF_SESSION_VALIDATED, false).apply()
            throw IOException("缺少 ipb_member_id / ipb_pass_hash")
        }

        val name = runCatching {
            val profileRequest = Request.Builder().url("$FORUMS_URL/index.php?showuser=${enc(id)}").header("Referer", "$FORUMS_URL/").build()
            client.newCall(profileRequest).execute().use { response ->
                if (!response.isSuccessful) throw IOException("论坛验证 HTTP ${response.code}")
                val doc = Jsoup.parse(response.body.string(), FORUMS_URL)
                doc.selectFirst(".home > b > a")?.text()?.trim()?.takeIf(String::isNotBlank)
                    ?: doc.selectFirst("#profilename")?.text()?.trim()?.takeIf(String::isNotBlank)
                    ?: throw IOException("Cookie 无效或登录已过期")
            }
        }.getOrElse { error ->
            preferences.edit().putBoolean(PREF_SESSION_VALIDATED, false).apply()
            throw error
        }
        preferences.edit().putString(PREF_ACCOUNT_NAME, name).apply()

        val exOk = runCatching {
            val request = Request.Builder().url("$EX_URL/").header("Referer", "$EH_URL/").header("Cache-Control", "no-cache").build()
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                response.isSuccessful && response.request.url.host == "exhentai.org" && body.trimStart().startsWith("<") && body.contains("itg")
            }
        }.getOrDefault(false)
        preferences.edit()
            .putBoolean(PREF_SESSION_VALIDATED, true)
            .putBoolean(PREF_EX_ACCESS, exOk)
            .apply()
        syncStoredCookiesToWebView()
        return "$name · E-Hentai 已验证 · ${if (exOk) "ExHentai 里站可用" else "里站无权限或未通过验证"}"
    }

    private fun logout() {
        clearAuthCookies(clearManual = true)
        preferences.edit()
            .remove(PREF_ACCOUNT_NAME)
            .remove(PREF_ACCOUNT_AVATAR)
            .remove(PREF_EX_ACCESS)
            .remove(PREF_PASSWORD)
            .putBoolean(PREF_SESSION_VALIDATED, false)
            .apply()
        listOf(EH_URL, EX_URL, FORUMS_URL).forEach { url ->
            AUTH_COOKIE_KEYS.forEach { key -> webViewCookies.setCookie(url, "$key=; Max-Age=0; path=/") }
        }
        webViewCookies.flush()
    }

    private fun clearAuthCookies(clearManual: Boolean = false) {
        val editor = preferences.edit()
        AUTH_COOKIE_KEYS.forEach { editor.remove(cookiePref(it)) }
        if (clearManual) {
            listOf(PREF_MEMBER_ID, PREF_PASS_HASH, PREF_IGNEOUS, PREF_STAR).forEach(editor::remove)
        }
        editor.putBoolean(PREF_SESSION_VALIDATED, false).apply()
    }

    private fun readWebViewAuthCookies(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        listOf(FORUMS_URL, EH_URL, EX_URL).forEach { url ->
            webViewCookies.getCookie(url)?.split(';')?.forEach { raw ->
                val part = raw.trim()
                val index = part.indexOf('=')
                if (index <= 0) return@forEach
                val name = part.substring(0, index)
                val value = part.substring(index + 1)
                if (name in AUTH_COOKIE_KEYS && value.isNotBlank()) result[name] = value
            }
        }
        return result
    }

    private fun saveWebViewAuthCookies(cookies: Map<String, String>, onlyMissing: Boolean) {
        if (cookies.isEmpty()) return
        val editor = preferences.edit()
        cookies.forEach { (name, value) ->
            if (!onlyMissing || authCookieValue(name).isBlank()) editor.putString(cookiePref(name), value)
        }
        editor.apply()
    }

    private fun syncWebViewCookies(save: Boolean, onlyMissing: Boolean = false): Int {
        val cookies = readWebViewAuthCookies()
        if (save) saveWebViewAuthCookies(cookies, onlyMissing)
        return cookies.size
    }

    private fun syncStoredCookiesToWebView() {
        listOf(EH_URL, EX_URL, FORUMS_URL).forEach { url ->
            AUTH_COOKIE_KEYS.forEach { key ->
                val value = authCookieValue(key)
                if (value.isNotBlank()) webViewCookies.setCookie(url, "$key=$value; path=/")
            }
        }
        webViewCookies.flush()
    }

    private fun captureSetCookies(response: Response) {
        val editor = preferences.edit()
        var changed = false
        response.headers.values("Set-Cookie").forEach { raw ->
            val pair = raw.substringBefore(';')
            val index = pair.indexOf('=')
            if (index <= 0) return@forEach
            val name = pair.substring(0, index).trim()
            val value = pair.substring(index + 1).trim()
            if (name in AUTH_COOKIE_KEYS) {
                if (value.isBlank() || value.equals("deleted", true)) editor.remove(cookiePref(name)) else editor.putString(cookiePref(name), value)
                changed = true
            }
        }
        if (changed) editor.apply()
    }

    private fun cookieHeader(): String = buildList {
        add("nw=1")
        add("uconfig=${uconfigValue()}")
        AUTH_COOKIE_KEYS.forEach { key ->
            authCookieValue(key).takeIf(String::isNotBlank)?.let { add("$key=$it") }
        }
    }.joinToString("; ")

    private fun promoteManualCookies() {
        val editor = preferences.edit()
        mapOf(
            "ipb_member_id" to PREF_MEMBER_ID,
            "ipb_pass_hash" to PREF_PASS_HASH,
            "igneous" to PREF_IGNEOUS,
            "star" to PREF_STAR,
        ).forEach { (cookie, pref) ->
            preferences.getString(pref, "").orEmpty().trim().takeIf(String::isNotBlank)?.let { editor.putString(cookiePref(cookie), it) }
        }
        editor.apply()
    }

    private fun authCookieValue(name: String): String {
        preferences.getString(cookiePref(name), "").orEmpty().takeIf(String::isNotBlank)?.let { return it }
        val manualPref = when (name) {
            "ipb_member_id" -> PREF_MEMBER_ID
            "ipb_pass_hash" -> PREF_PASS_HASH
            "igneous" -> PREF_IGNEOUS
            "star" -> PREF_STAR
            else -> null
        }
        return manualPref?.let { preferences.getString(it, "").orEmpty().trim() }.orEmpty()
    }

    private fun uconfigValue(): String {
        if (!chineseOnly()) return "prn_n"
        val excluded = LANGUAGE_MAPPINGS.filterKeys { it != "chinese" }.values.flatten().joinToString("x")
        return "prn_n-xl_$excluded"
    }

    private fun memberId() = authCookieValue("ipb_member_id")
    private fun passHash() = authCookieValue("ipb_pass_hash")
    private fun igneous() = authCookieValue("igneous")
    private fun star() = authCookieValue("star")

    private fun isLoggedIn() = memberId().isNotBlank() && passHash().isNotBlank()
    private fun sessionValidated() = isLoggedIn() && preferences.getBoolean(PREF_SESSION_VALIDATED, false)
    private fun siteMode() = preferences.getString(PREF_SITE_MODE, "auto") ?: "auto"
    private fun chineseOnly() = preferences.getBoolean(PREF_CHINESE_ONLY, true)
    private fun originalImages() = preferences.getBoolean(PREF_ORIGINAL_IMAGE, false)
    private fun translateTagsEnabled() = preferences.getBoolean(PREF_TRANSLATE_TAGS, true)
    private fun showAllComments() = preferences.getBoolean(PREF_ALL_COMMENTS, true)

    private fun accountStatusTitle(): String = when {
        sessionValidated() -> "✅ 登录状态：已登录"
        isLoggedIn() -> "⚠️ 登录状态：已获取 Cookie，待验证"
        else -> "❌ 登录状态：未登录"
    }

    private fun accountStatusSummary(): String {
        val state = when {
            sessionValidated() -> {
                val name = preferences.getString(PREF_ACCOUNT_NAME, "").orEmpty().ifBlank { "E-Hentai 用户 #${memberId()}" }
                val exStatus = if (preferences.getBoolean(PREF_EX_ACCESS, false)) "ExHentai：可用" else "ExHentai：无权限或未通过验证"
                "账号：$name\nE-Hentai：已验证 · $exStatus\n点击此项可重新读取网页登录 Cookie 并验证"
            }
            isLoggedIn() -> "已检测到 ipb_member_id / ipb_pass_hash，但还没有完成有效性验证。点击此项立即验证。"
            else -> "未登录。推荐点击下一项“网页登录（推荐）”；也支持账号密码或手动 Cookie。"
        }
        val lastResult = preferences.getString(PREF_AUTH_RESULT, "").orEmpty()
        return if (lastResult.isBlank()) state else "$state\n最近操作：$lastResult"
    }

    private fun accountFilterStatus(): String = when {
        sessionValidated() -> {
            val name = preferences.getString(PREF_ACCOUNT_NAME, "").orEmpty().ifBlank { "EH 用户 #${memberId()}" }
            "账号：已登录 · $name · ${if (preferences.getBoolean(PREF_EX_ACCESS, false)) "里站可用" else "仅表站/里站未验证"}"
        }
        isLoggedIn() -> "账号：已获取 Cookie，待验证"
        else -> "账号：未登录 · 可在漫画源设置中使用网页登录"
    }

    private fun updateAccountStatusPreference(preference: Preference) {
        Handler(Looper.getMainLooper()).post {
            preference.title = accountStatusTitle()
            preference.summary = accountStatusSummary()
        }
    }

    private fun actionPreference(screen: PreferenceScreen, keyName: String, titleText: String, summaryText: String, action: () -> Unit) {
        Preference(screen.context).apply {
            key = keyName
            title = titleText
            summary = summaryText
            setOnPreferenceClickListener {
                client.dispatcher.executorService.execute(action)
                true
            }
        }.also(screen::addPreference)
    }

    private fun showResult(screen: PreferenceScreen, result: Result<String>, prefix: String) {
        val text = result.fold({ "$prefix：$it" }, { "操作失败：${it.message}" })
        preferences.edit().putString(PREF_AUTH_RESULT, text).apply()
        showToast(screen, text)
    }

    private fun showToast(screen: PreferenceScreen, text: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(screen.context, text, Toast.LENGTH_LONG).show() }
    }

    private fun directGalleryPath(query: String): String? {
        val trimmed = query.trim()
        normalizeGalleryUrl(trimmed)?.let { return it }
        if (trimmed.startsWith("id:", true)) normalizeGalleryUrl(trimmed.substringAfter(':'))?.let { return it }
        val pair = Regex("(?:id:)?(\\d+)[/:]([0-9a-zA-Z]+)").matchEntire(trimmed)?.groupValues
        if (pair != null) return "/g/${pair[1]}/${pair[2]}/?nw=always"
        return null
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun cookiePref(name: String) = "ehp.cookie.$name"

    private data class CachedDetail(val time: Long, val value: DetailBundle)
    private data class CachedImageUrl(val time: Long, val url: String)

    companion object {
        private const val EH_URL = "https://e-hentai.org"
        private const val EX_URL = "https://exhentai.org"
        private const val FORUMS_URL = "https://forums.e-hentai.org"
        private const val FORUM_LOGIN_URL = "$FORUMS_URL/index.php?act=Login&CODE=00"
        private const val DETAIL_CACHE_MS = 5 * 60 * 1000L
        private const val IMAGE_URL_CACHE_MS = 30 * 60 * 1000L
        private const val READER_REVISION = "4"
        private const val HOST_WEBVIEW_ACTIVITY = "eu.kanade.tachiyomi.ui.webview.WebViewActivity"
        private const val HOST_WEBVIEW_URL_KEY = "url_key"
        private const val HOST_WEBVIEW_SOURCE_KEY = "source_key"
        private const val HOST_WEBVIEW_TITLE_KEY = "title_key"
        private const val WEB_LOGIN_POLL_MS = 1500L
        private const val WEB_LOGIN_MAX_POLLS = 120
        private const val IMAGE_REQUEST_HEADER = "X-MX-EH-Image"
        private const val IMAGE_VIEWER_HEADER = "X-MX-EH-Viewer"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
        private const val TAG_TRANSLATION_URL = "https://fastly.jsdelivr.net/gh/EhTagTranslation/DatabaseReleases/db.html.json"

        private const val PREF_SITE_MODE = "ehp.site_mode"
        private const val PREF_CHINESE_ONLY = "ehp.chinese_only"
        private const val PREF_ORIGINAL_IMAGE = "ehp.original_image"
        private const val PREF_TRANSLATE_TAGS = "ehp.translate_tags"
        private const val PREF_ALL_COMMENTS = "ehp.all_comments"
        private const val PREF_USERNAME = "ehp.username"
        private const val PREF_PASSWORD = "ehp.password"
        private const val PREF_MEMBER_ID = "ehp.member_id"
        private const val PREF_PASS_HASH = "ehp.pass_hash"
        private const val PREF_IGNEOUS = "ehp.igneous"
        private const val PREF_STAR = "ehp.star"
        private const val PREF_EX_ACCESS = "ehp.ex_access"
        private const val PREF_ACCOUNT_NAME = "ehp.account_name"
        private const val PREF_ACCOUNT_AVATAR = "ehp.account_avatar"
        private const val PREF_AUTH_RESULT = "ehp.auth_result"
        private const val PREF_SESSION_VALIDATED = "ehp.session_validated"
        private const val PREF_TRANSLATION_CACHE_PREFIX = "ehp.zh."

        private val EH_COOKIE_HOSTS = setOf("e-hentai.org", "forums.e-hentai.org", "api.e-hentai.org", "exhentai.org")
        private val AUTH_COOKIE_KEYS = setOf("ipb_member_id", "ipb_pass_hash", "igneous", "star", "sk", "ipb_session_id")

        private val LANGUAGE_MAPPINGS = mapOf(
            "japanese" to listOf("0", "1024", "2048"),
            "english" to listOf("1", "1025", "2049"),
            "chinese" to listOf("10", "1034", "2058"),
            "dutch" to listOf("20", "1044", "2068"),
            "french" to listOf("30", "1054", "2078"),
            "german" to listOf("40", "1064", "2088"),
            "hungarian" to listOf("50", "1074", "2098"),
            "italian" to listOf("60", "1084", "2108"),
            "korean" to listOf("70", "1094", "2118"),
            "polish" to listOf("80", "1104", "2128"),
            "portuguese" to listOf("90", "1114", "2138"),
            "russian" to listOf("100", "1124", "2148"),
            "spanish" to listOf("110", "1134", "2158"),
            "thai" to listOf("120", "1144", "2168"),
            "vietnamese" to listOf("130", "1154", "2178"),
        )
    }
}
