package eu.kanade.tachiyomi.extension.zh.tencentcomics

import android.content.ComponentName
import android.content.Intent
import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
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
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@Source
abstract class TencentComics :
    HttpSource(),
    ConfigurableSource,
    CommentSource,
    SortableCommentSource,
    AccountSource,
    ChapterContentReplacementSource {

    // its easier to parse the mobile version of the website

    private val desktopUrl = "https://ac.qq.com"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36")

    override fun chapterListRequest(manga: SManga): Request = GET("$desktopUrl/Comic/comicInfo/" + manga.url.substringAfter("/index/"), headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select(".works-chapter-item").map { element ->
            SChapter.create().apply {
                setUrlWithoutDomain(element.select("a").attr("abs:href"))
                name = (if (element.isLockedChapter()) "\uD83D\uDD12 " else "") + element.text()
            }
        }.reversed()
    }

    private fun Element.isLockedChapter(): Boolean = selectFirst(".ui-icon-pay") != null

    override fun popularMangaRequest(page: Int): Request = GET("$desktopUrl/Comic/all/search/hot/page/$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parsePopularMangaPage(response.asJsoup())

    override fun latestUpdatesRequest(page: Int): Request = GET("$desktopUrl/Comic/all/search/time/page/$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // desktop version of the site has more info
    override fun mangaDetailsRequest(manga: SManga): Request = GET("$desktopUrl/Comic/comicInfo/" + manga.url.substringAfter("/index/"), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            thumbnail_url = document.select("div.works-cover.ui-left > a > img").attr("src")
            title = document.select("h2.works-intro-title.ui-left > strong").text()
            description = document.select("p.works-intro-short").text()
            author = document.select("p.works-intro-digi > span > em").text()
            status = when (document.select("label.works-intro-status").text()) {
                "连载中" -> SManga.ONGOING
                "已完结" -> SManga.COMPLETED
                "連載中" -> SManga.ONGOING
                "已完結" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // convert url to desktop since some chapters are blocked on mobile
    override fun pageListRequest(chapter: SChapter): Request = GET(desktopUrl + chapter.url, headers)

    private val dataRegex = Regex("^'|',\$")

    private val jsDecodeFunction = """
        raw = raw.split('');
        nonce = nonce.match(/\d+[a-zA-Z]+/g);
        var len = nonce.length;
        while (len--) {
            var offset = parseInt(nonce[len]) & 255;
            var noise = nonce[len].replace(/\d+/g, '');
            raw.splice(offset, noise.length);
        }
        raw.join('');
    """

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        var html = document.html()

        // Sometimes the nonce has commands that are unrunnable, just reload and hope
        var nonce = html.substringAfterLast("window[").substringAfter("] = ").substringBefore("</script>").trim()

        while (nonce.contains("document") || nonce.contains("window")) {
            html = client.newCall(GET(desktopUrl + document.select("li.now-reading > a").attr("href"), headers)).execute().use { it.body.string() }
            nonce = html.substringAfterLast("window[").substringAfter("] = ").substringBefore("</script>").trim()
        }

        return decodeChapterData(html, nonce).toPageList()
    }

    private fun decodeChapterData(html: String, nonce: String): ChapterData {
        val raw = html.substringAfterLast("var DATA =").substringBefore("PRELOAD_NUM").trim().replace(dataRegex, "")
        val decodePrefix = "var raw = \"$raw\"; var nonce = $nonce"
        val full = QuickJs.create().use { it.evaluate(decodePrefix + jsDecodeFunction).toString() }
        return String(Base64.decode(full, Base64.DEFAULT)).parseAs()
    }

    private fun loadChapterData(path: String): ChapterData {
        val response = client.newCall(GET(desktopUrl + path, headers)).execute()
        return response.use {
            val document = it.asJsoup()
            var html = document.html()
            var nonce = html.substringAfterLast("window[").substringAfter("] = ").substringBefore("</script>").trim()
            while (nonce.contains("document") || nonce.contains("window")) {
                val reloadPath = document.select("li.now-reading > a").attr("href").ifBlank { path }
                html = client.newCall(GET(desktopUrl + reloadPath, headers)).execute().use { r -> r.body.string() }
                nonce = html.substringAfterLast("window[").substringAfter("] = ").substringBefore("</script>").trim()
            }
            decodeChapterData(html, nonce)
        }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val id = url.pathSegments[3]
            return fetchSearchManga(page, "$ID_SEARCH_PREFIX$id", filters)
        }
        return if (query.startsWith(ID_SEARCH_PREFIX)) {
            val id = query.removePrefix(ID_SEARCH_PREFIX)
            client.newCall(searchMangaByIdRequest(id))
                .asObservableSuccess()
                .map { response -> searchMangaByIdParse(response, id) }
        } else {
            super.fetchSearchManga(page, query, filters)
        }
    }

    private fun searchMangaByIdRequest(id: String) = GET("$desktopUrl/Comic/comicInfo/id/$id", headers)

    private fun searchMangaByIdParse(response: Response, id: String): MangasPage {
        val sManga = mangaDetailsParse(response)
        sManga.url = "/comic/index/id/$id"
        return MangasPage(listOf(sManga), false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // The old mobile /search/result endpoint is no longer reliable. The PC
        // search page remains available and is also consistent with our PC
        // details/chapter parsing.
        return if (query.isNotEmpty()) {
            val url = "$desktopUrl/Comic/searchList".toHttpUrl().newBuilder()
                .addQueryParameter("search", query)
                .build()
            GET(url, headers)
        } else {
            lateinit var genre: String
            lateinit var status: String
            lateinit var popularity: String
            lateinit var vip: String
            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> {
                        genre = filter.toUriPart()
                        if (genre.isNotEmpty()) genre = "theme/$genre/"
                    }

                    is StatusFilter -> {
                        status = filter.toUriPart()
                    }

                    is PopularityFilter -> {
                        popularity = filter.toUriPart()
                    }

                    is VipFilter -> {
                        vip = filter.toUriPart()
                    }

                    else -> {}
                }
            }
            GET("$desktopUrl/Comic/all/$genre${status}search/$popularity${vip}page/$page")
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        return if (response.request.url.encodedPath.equals("/Comic/searchList", ignoreCase = true)) {
            val items = document.select(
                "ul.ret-search-list.clearfix > li, ul.ret-search-list > li, .mod_book_list > li, ol > li",
            )
            val mangas = items.mapNotNull(::parsePcSearchMangaElement)
                .distinctBy { it.url }
            MangasPage(mangas, false)
        } else {
            parsePopularMangaPage(document)
        }
    }

    override fun getFilterList() = FilterList(
        Filter.Header("注意：不影響按標題搜索"),
        PopularityFilter(),
        VipFilter(),
        StatusFilter(),
        GenreFilter(),
    )

    private fun parsePopularMangaPage(document: Document): MangasPage {
        val mangas = document.select("ul.ret-search-list.clearfix > li").map { parsePopularMangaElement(it) }
        // next page buttons do not exist
        // even if the total searches happen to be 12 the website fills the next page anyway
        return MangasPage(mangas, mangas.size == 12)
    }

    private fun parsePopularMangaElement(element: Element): SManga = SManga.create().apply {
        url = "/comic/index/" + element.select("div > a").attr("href").substringAfter("/Comic/comicInfo/")
        title = element.select("div > a").attr("title").trim()
        thumbnail_url = element.select("div > a > img").attr("data-original")
        author = element.select("div > p.ret-works-author").text()
        description = element.select("div > p.ret-works-decs").text()
    }

    private fun parsePcSearchMangaElement(element: Element): SManga? {
        val anchor = element.selectFirst(
            "a[href*=/Comic/comicInfo/id/], a[href*=/Comic/ComicInfo/id/], a[href*=/comic/index/id/]",
        ) ?: return null
        val href = anchor.attr("href")
        val comicId = PC_COMIC_ID.find(href)?.groupValues?.getOrNull(1) ?: return null
        val title = anchor.attr("title")
            .ifBlank { element.selectFirst("h4 a, h3 a, .ret-works-title a, a[title]")?.text().orEmpty() }
            .trim()
            .ifBlank { return null }
        val image = element.selectFirst("img")
        val thumbnail = normalizeUrl(
            image?.attr("data-original")
                ?.ifBlank { image.attr("data-src") }
                ?.ifBlank { image.attr("src") },
        )

        return SManga.create().apply {
            url = "/comic/index/id/$comicId"
            this.title = title
            thumbnail_url = thumbnail
            author = firstText(element, ".ret-works-author", ".mod_book_author", "[class*=author]").orEmpty()
            description = firstText(element, ".ret-works-decs", ".mod_book_intro", "[class*=desc]").orEmpty()
            genre = firstText(element, ".comic-tag", "[class*=tag]").orEmpty().replace(" ", ", ")
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

    @Volatile
    private var commentSortTargetKind = CommentTargetKind.CHAPTER

    private val mangaTopicPageCursors = ConcurrentHashMap<String, ConcurrentHashMap<Int, String>>()
    private val mangaTopicTagIds = ConcurrentHashMap<String, String>()
    private val mangaTopicTotals = ConcurrentHashMap<String, Long>()

    override suspend fun getMangaCommentTarget(manga: SManga): CommentTarget {
        commentSortTargetKind = CommentTargetKind.MANGA
        val comicId = manga.url.substringAfterLast("/").substringBefore("?")
        return CommentTarget(comicId, "$desktopUrl/Comic/comicInfo/id/$comicId", CommentTargetKind.MANGA)
    }

    override suspend fun getChapterCommentTarget(manga: SManga, chapter: SChapter): CommentTarget {
        commentSortTargetKind = CommentTargetKind.CHAPTER
        val ids = CHAPTER_IDS.find(chapter.url) ?: throw IllegalArgumentException("无法解析腾讯动漫章节 ID")
        return CommentTarget(ids.groupValues[1] + ":" + ids.groupValues[2], chapter.url, CommentTargetKind.CHAPTER)
    }

    override val commentSortOptions: List<CommentSortOption>
        get() = when (commentSortTargetKind) {
            CommentTargetKind.MANGA -> listOf(
                CommentSortOption(SORT_DEFAULT, "精华"),
                CommentSortOption(SORT_LATEST, "最新"),
            )

            CommentTargetKind.CHAPTER -> listOf(
                CommentSortOption(SORT_DEFAULT, "默认"),
                CommentSortOption(SORT_LATEST, "最新"),
            )
        }

    override val defaultCommentSortId = SORT_DEFAULT

    override suspend fun getComments(
        target: CommentTarget,
        page: Int,
    ): CommentPage = getComments(target, page, SORT_DEFAULT)

    override suspend fun getComments(
        target: CommentTarget,
        page: Int,
        sortId: String,
    ): CommentPage = when (target.kind) {
        CommentTargetKind.MANGA -> getMangaTopicsAppFirst(
            comicId = target.id,
            page = page.coerceAtLeast(1),
            sortId = sortId,
        )

        CommentTargetKind.CHAPTER -> getChapterTopicsFromApp(
            target = target,
            page = page.coerceAtLeast(1),
            sortId = sortId,
        )
    }

    override suspend fun getCommentReplies(
        target: CommentTarget,
        comment: Comment,
        page: Int,
    ): CommentPage = when (target.kind) {
        CommentTargetKind.CHAPTER -> getChapterTopicRepliesFromApp(
            target = target,
            topic = comment,
            page = page.coerceAtLeast(1),
        )

        CommentTargetKind.MANGA -> getChapterTopicRepliesFromApp(
            target = CommentTarget(
                id = "${target.id}:0",
                url = target.url,
                kind = CommentTargetKind.CHAPTER,
            ),
            topic = comment,
            page = page.coerceAtLeast(1),
        )
    }

    private fun getMangaTopicsAppFirst(
        comicId: String,
        page: Int,
        sortId: String,
    ): CommentPage = runCatching {
        getMangaTopicsFromApp(
            comicId = comicId,
            page = page,
            sortId = sortId,
        )
    }.getOrElse {
        getMangaTopicsFromPc(comicId, page)
    }

    private fun getMangaTopicsFromApp(
        comicId: String,
        page: Int,
        sortId: String,
    ): CommentPage {
        val feed = mangaTopicFeed(sortId)
        val cacheKey = "$comicId:$feed"
        val cursors = mangaTopicPageCursors.getOrPut(cacheKey) { ConcurrentHashMap() }

        if (page == 1) {
            val detail = requestMangaTagDetailData(comicId)
            val baseInfo = detail.obj("base_info") ?: detail.obj("baseInfo")
            val tagId = baseInfo?.string("tag_id")
                ?: baseInfo?.string("tagId")
                ?: throw IllegalStateException("腾讯动漫 APP 书评响应缺少 tag_id")
            mangaTopicTagIds[comicId] = tagId

            val total = baseInfo?.long("topic_count") ?: baseInfo?.long("topicCount")
            if (total != null) mangaTopicTotals[comicId] = total

            val topicList = detail.obj("topic_list") ?: detail.obj("topicList")
                ?: throw IllegalStateException("腾讯动漫 APP 书评响应缺少 topic_list")
            val bucket = topicList.obj(feed)
                ?: throw IllegalStateException("腾讯动漫 APP 书评响应缺少 $feed")

            cursors.clear()
            bucket.nextPageId()?.let { cursors[2] = it }
            val leadingBuckets = if (sortId == SORT_DEFAULT) {
                listOfNotNull(topicList.obj(APP_MANGA_TOPIC_RECOMMEND))
            } else {
                emptyList()
            }
            return mangaTopicBucketToPage(bucket, total, leadingBuckets)
        }

        var tagId = mangaTopicTagIds[comicId]
        if (tagId == null || cursors[2] == null) {
            val detail = requestMangaTagDetailData(comicId)
            val baseInfo = detail.obj("base_info") ?: detail.obj("baseInfo")
            tagId = baseInfo?.string("tag_id")
                ?: baseInfo?.string("tagId")
                ?: throw IllegalStateException("腾讯动漫 APP 书评响应缺少 tag_id")
            mangaTopicTagIds[comicId] = tagId

            val total = baseInfo?.long("topic_count") ?: baseInfo?.long("topicCount")
            if (total != null) mangaTopicTotals[comicId] = total

            val firstBucket = (detail.obj("topic_list") ?: detail.obj("topicList"))
                ?.obj(feed)
                ?: throw IllegalStateException("腾讯动漫 APP 书评响应缺少 $feed")
            cursors.clear()
            firstBucket.nextPageId()?.let { cursors[2] = it }
        }

        val resolvedTagId = tagId
            ?: throw IllegalStateException("腾讯动漫 APP 书评响应缺少 tag_id")

        var currentPage = 2
        while (currentPage < page) {
            val currentCursor = cursors[currentPage]
                ?: return CommentPage(emptyList(), false, mangaTopicTotals[comicId])
            val intermediate = requestMangaTagTopicListData(
                comicId = comicId,
                tagId = resolvedTagId,
                feed = feed,
                pageId = currentCursor,
            )
            val bucket = intermediate.obj(feed)
                ?: throw IllegalStateException("腾讯动漫 APP 书评分页响应缺少 $feed")
            val nextCursor = bucket.nextPageId()
                ?: return CommentPage(emptyList(), false, mangaTopicTotals[comicId])
            cursors[currentPage + 1] = nextCursor
            currentPage++
        }

        val cursor = cursors[page]
            ?: return CommentPage(emptyList(), false, mangaTopicTotals[comicId])
        val data = requestMangaTagTopicListData(
            comicId = comicId,
            tagId = resolvedTagId,
            feed = feed,
            pageId = cursor,
        )
        val bucket = data.obj(feed)
            ?: throw IllegalStateException("腾讯动漫 APP 书评分页响应缺少 $feed")
        bucket.nextPageId()?.let { cursors[page + 1] = it }
        return mangaTopicBucketToPage(bucket, mangaTopicTotals[comicId])
    }

    private fun mangaTopicBucketToPage(
        bucket: JsonObject,
        total: Long?,
        leadingBuckets: List<JsonObject> = emptyList(),
    ): CommentPage {
        val topics = (leadingBuckets + bucket)
            .flatMap { sourceBucket ->
                (sourceBucket["list"] as? JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    .orEmpty()
            }
            .mapNotNull(::communityTopicToComment)
            .distinctBy(Comment::id)

        return CommentPage(
            comments = topics,
            hasNextPage = bucket.nextPageId() != null,
            totalCount = total,
        )
    }

    private fun requestMangaTagDetailData(comicId: String): JsonObject {
        val failures = mutableListOf<String>()
        return APP_API_HOSTS.firstNotNullOfOrNull { host ->
            runCatching {
                val url = (
                    "$host/$APP_VERSION/CommunityTag/detailPage" +
                        "/target_type/$APP_MANGA_TAG_TARGET_TYPE" +
                        "/target_id/$comicId" +
                        "/comic_id/$comicId"
                    ).toHttpUrl()
                requestAppData(url, "书评")
            }.onFailure { error ->
                failures += "${host.toHttpUrl().host}: " + (error.message ?: error::class.java.simpleName)
            }.getOrNull()
        } ?: throw IllegalStateException(
            "腾讯动漫 APP 书评接口暂时不可用：" + failures.joinToString("；"),
        )
    }

    private fun requestMangaTagTopicListData(
        comicId: String,
        tagId: String,
        feed: String,
        pageId: String,
    ): JsonObject {
        val failures = mutableListOf<String>()
        return APP_API_HOSTS.firstNotNullOfOrNull { host ->
            runCatching {
                val url = (
                    "$host/$APP_VERSION/CommunityTag/topicList" +
                        "/tag_id/$tagId" +
                        "/type/$feed" +
                        "/page_id/$pageId" +
                        "/comic_id/$comicId"
                    ).toHttpUrl()
                requestAppData(url, "书评分页")
            }.onFailure { error ->
                failures += "${host.toHttpUrl().host}: " + (error.message ?: error::class.java.simpleName)
            }.getOrNull()
        } ?: throw IllegalStateException(
            "腾讯动漫 APP 书评分页接口暂时不可用：" + failures.joinToString("；"),
        )
    }

    private fun requestAppData(
        url: okhttp3.HttpUrl,
        label: String,
    ): JsonObject {
        val response = client.newCall(GET(url, appApiHeaders(url))).execute()
        return response.use { result ->
            if (!result.isSuccessful) {
                throw IllegalStateException("腾讯动漫 APP $label 请求失败：HTTP " + result.code)
            }

            val body = result.body.string()
            val decodedBody = runCatching { decodeAppApiResponse(body) }
                .getOrElse { error ->
                    throw IllegalStateException(
                        "腾讯动漫 APP $label 响应解密失败：" +
                            (error.message ?: error::class.java.simpleName),
                    )
                }

            val root = try {
                JSON.parseToJsonElement(decodedBody).jsonObject
            } catch (_: Exception) {
                throw IllegalStateException("腾讯动漫 APP $label 解密后仍不是 JSON")
            }

            val errorCode = root.long("errorCode") ?: root.long("error_code")
            if (errorCode != null && errorCode != APP_SUCCESS_CODE) {
                val message = root.string("msg")
                    ?: root.string("message")
                    ?: root.string("errorMsg")
                    ?: "errorCode=$errorCode"
                throw IllegalStateException("腾讯动漫 APP $label 请求失败：$message")
            }

            root["data"] as? JsonObject
                ?: throw IllegalStateException("腾讯动漫 APP $label 响应缺少 data")
        }
    }

    private fun getMangaTopicsFromPc(comicId: String, page: Int): CommentPage {
        val url = "$desktopUrl/Community/topicList?targetId=$comicId&page=$page&_=" + System.currentTimeMillis()
        val requestHeaders = headers.newBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", "$desktopUrl/Comic/comicInfo/id/$comicId")
            .build()
        val document = client.newCall(GET(url, requestHeaders)).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("腾讯动漫作品评论请求失败：HTTP ${response.code}")
            response.asJsoup()
        }

        // Current PC Community markup (verified against the live endpoint):
        // .comment-list-content-wr carries topic/user metadata, while child elements
        // carry avatar, body, likes and two .comment-time nodes (platform + timestamp).
        val items = document.select(".comment-list-content-wr")
        val comments = items.mapNotNull { item ->
            val detail = item.selectFirst(".comment-content-detail") ?: return@mapNotNull null
            val textContent = decodeCommentText(detail.html()).takeIf(String::isNotBlank)
            val imageUrls = detail.select("img")
                .mapNotNull(::imageUrlFromElement)
                .distinct()
            val content = commentContentWithImages(listOfNotNull(textContent), imageUrls)
                ?: return@mapNotNull null
            val topicId = item.attr("data-c").ifBlank {
                TOPIC_ID.find(detail.attr("href"))?.groupValues?.getOrNull(1).orEmpty()
            }.ifBlank { return@mapNotNull null }
            val authorId = item.attr("data-u").ifBlank { null }
            val authorName = item.attr("data-nick")
                .ifBlank { item.selectFirst(".comment-content-name")?.text().orEmpty() }
                .ifBlank { item.selectFirst(".com-user-name")?.attr("title").orEmpty() }
                .ifBlank { "腾讯动漫用户" }
            val avatar = item.selectFirst(".comment-userhead-wr img")
                ?.let(::imageUrlFromElement)
            val times = item.select(".comment-time").map { it.text().trim() }
            val displayTime = times.lastOrNull { PC_COMMENT_TIME.matches(it) }
                ?: times.lastOrNull()
            val likeCount = item.selectFirst(".comment-zan-num")
                ?.text()
                ?.replace(",", "")
                ?.trim()
                ?.toLongOrNull()
                ?: 0L
            val replyCount = item.selectFirst("[class*=reply]")
                ?.text()
                ?.replace(",", "")
                ?.let { REPLY_COUNT.find(it)?.groupValues?.getOrNull(1)?.toLongOrNull() }
                ?: 0L

            Comment(
                id = topicId,
                author = CommentAuthor(
                    id = authorId,
                    name = authorName,
                    avatarUrl = avatar,
                ),
                content = content,
                createdAt = 0L,
                displayTime = displayTime,
                likeCount = likeCount,
                replyCount = replyCount,
            )
        }.distinctBy(Comment::id)

        val total = document.selectFirst(".commen-ft-ts")
            ?.text()
            ?.replace(",", "")
            ?.filter(Char::isDigit)
            ?.toLongOrNull()

        return CommentPage(
            comments = comments,
            hasNextPage = comments.isNotEmpty() && (total == null || page.toLong() * TOPIC_PAGE_SIZE < total),
            totalCount = total,
        )
    }

    private fun getChapterTopicsFromApp(
        target: CommentTarget,
        page: Int,
        sortId: String,
    ): CommentPage {
        val ids = target.id.split(':', limit = 2)
        if (ids.size != 2) throw IllegalArgumentException("无法解析腾讯动漫章节评论目标")
        val comicId = ids[0]
        val chapterId = ids[1]

        val failures = mutableListOf<String>()
        val data = APP_API_HOSTS.firstNotNullOfOrNull { host ->
            runCatching {
                requestChapterTopicData(
                    apiHost = host,
                    comicId = comicId,
                    chapterId = chapterId,
                    page = page,
                    topicType = chapterTopicType(sortId),
                )
            }.onFailure { error ->
                failures += "${host.toHttpUrl().host}: ${error.message ?: error::class.java.simpleName}"
            }.getOrNull()
        } ?: throw IllegalStateException(
            "腾讯动漫 APP 章节评论接口暂时不可用：${failures.joinToString("；")}",
        )

        val topics = ((data["topicList"] ?: data["topic_list"]) as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
            .mapNotNull(::topicToComment)
            .distinctBy(Comment::id)

        val total = data.long("totalCount") ?: data.long("total_count")
        val endOfList = data.long("endOfList") ?: data.long("end_of_list")
        val hasNext = when {
            endOfList != null -> endOfList != APP_END_OF_LIST
            total != null && topics.isNotEmpty() -> page.toLong() * topics.size < total
            else -> topics.isNotEmpty()
        }

        return CommentPage(
            comments = topics,
            hasNextPage = hasNext,
            totalCount = total,
        )
    }

    private fun getChapterTopicRepliesFromApp(
        target: CommentTarget,
        topic: Comment,
        page: Int,
    ): CommentPage {
        val ids = target.id.split(':', limit = 2)
        if (ids.size != 2) throw IllegalArgumentException("无法解析腾讯动漫章节评论目标")
        val comicId = ids[0]

        val failures = mutableListOf<String>()
        val data = APP_API_HOSTS.firstNotNullOfOrNull { host ->
            runCatching {
                requestChapterTopicReplyData(
                    apiHost = host,
                    comicId = comicId,
                    topicId = topic.id,
                    page = page,
                )
            }.onFailure { error ->
                failures += "${host.toHttpUrl().host}: ${error.message ?: error::class.java.simpleName}"
            }.getOrNull()
        } ?: throw IllegalStateException(
            "腾讯动漫 APP 章节评论回复接口暂时不可用：${failures.joinToString("；")}",
        )

        val replies = (data["list"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
            .mapNotNull { commentInfoToComment(it, topic.id) }
            .distinctBy(Comment::id)

        val total = data.long("commentCount")
            ?: data.long("comment_count")
            ?: data.long("total")
            ?: topic.replyCount
        val endOfList = data.long("endOfList") ?: data.long("end_of_list")
        val hasNext = when {
            endOfList != null -> endOfList != APP_END_OF_LIST
            total != null && replies.isNotEmpty() -> page.toLong() * APP_TOPIC_COMMENT_PAGE_SIZE < total
            else -> replies.isNotEmpty()
        }

        return CommentPage(
            comments = replies,
            hasNextPage = hasNext,
            totalCount = total,
        )
    }

    private fun requestChapterTopicReplyData(
        apiHost: String,
        comicId: String,
        topicId: String,
        page: Int,
    ): JsonObject {
        // Official 12.19.9 q3.z() request parameters:
        // topic_id, target_type, page, listcnt and optional comic_id.
        val url = (
            "$apiHost/$APP_VERSION/Comment/getTopicCommentList" +
                "/topic_id/$topicId" +
                "/target_type/$APP_CHAPTER_TARGET_TYPE" +
                "/page/$page" +
                "/listcnt/$APP_TOPIC_COMMENT_PAGE_SIZE" +
                "/comic_id/$comicId"
            ).toHttpUrl()

        val response = client.newCall(GET(url, appApiHeaders(url))).execute()
        return response.use { result ->
            if (!result.isSuccessful) {
                throw IllegalStateException("腾讯动漫 APP 章节评论回复请求失败：HTTP ${result.code}")
            }

            val body = result.body.string()
            val decodedBody = runCatching { decodeAppApiResponse(body) }
                .getOrElse { error ->
                    val contentType = result.header("Content-Type").orEmpty().ifBlank { "unknown" }
                    throw IllegalStateException(
                        "腾讯动漫 APP 章节评论回复响应解密失败（Content-Type=$contentType，长度=${body.length}）：" +
                            (error.message ?: error::class.java.simpleName),
                    )
                }

            val root = try {
                JSON.parseToJsonElement(decodedBody).jsonObject
            } catch (_: Exception) {
                throw IllegalStateException(
                    "腾讯动漫 APP 章节评论回复解密后仍不是 JSON（长度=${decodedBody.length}）",
                )
            }

            val errorCode = root.long("errorCode") ?: root.long("error_code")
            if (errorCode != null && errorCode != APP_SUCCESS_CODE) {
                val message = root.string("msg")
                    ?: root.string("message")
                    ?: root.string("errorMsg")
                    ?: "errorCode=$errorCode"
                throw IllegalStateException("腾讯动漫 APP 章节评论回复请求失败：$message")
            }

            root["data"] as? JsonObject
                ?: throw IllegalStateException("腾讯动漫 APP 章节评论回复响应缺少 data")
        }
    }

    private fun commentInfoToComment(info: JsonObject, parentTopicId: String): Comment? {
        val textContent = info.string("content")
            ?.let(::decodeCommentText)
            ?.takeIf(String::isNotBlank)
        val content = commentContentWithImages(
            textParts = listOfNotNull(textContent),
            imageUrls = topicImageUrls(info),
        ) ?: return null
        val commentId = info.string("commentId")
            ?: info.string("comment_id")
            ?: return null

        return Comment(
            id = commentId,
            author = CommentAuthor(
                id = info.string("hostQq") ?: info.string("host_qq"),
                name = info.string("nickName")
                    ?: info.string("nick_name")
                    ?: "腾讯动漫用户",
                avatarUrl = normalizeUrl(info.string("qqHead") ?: info.string("qq_head")),
            ),
            content = content,
            createdAt = 0L,
            displayTime = info.string("date"),
            likeCount = info.long("goodCount") ?: info.long("good_count") ?: 0L,
            replyCount = info.long("replyCount") ?: info.long("reply_count") ?: 0L,
            parentId = parentTopicId,
        )
    }

    private fun requestChapterTopicData(
        apiHost: String,
        comicId: String,
        chapterId: String,
        page: Int,
        topicType: Int,
    ): JsonObject {
        // Official 12.19.9 RequestHelper uses a versioned path API and appends
        // parameters as /key/value path segments instead of a conventional query string.
        val url = (
            "$apiHost/$APP_VERSION/Community/getChapterTopicList" +
                "/comic_id/$comicId" +
                "/chapter_id/$chapterId" +
                "/page/$page" +
                "/type/$topicType"
            ).toHttpUrl()

        val response = client.newCall(GET(url, appApiHeaders(url))).execute()
        return response.use { result ->
            if (!result.isSuccessful) {
                throw IllegalStateException("腾讯动漫 APP 章节评论请求失败：HTTP ${result.code}")
            }

            val body = result.body.string()
            val decodedBody = runCatching { decodeAppApiResponse(body) }
                .getOrElse { error ->
                    val contentType = result.header("Content-Type").orEmpty().ifBlank { "unknown" }
                    throw IllegalStateException(
                        "腾讯动漫 APP 章节评论响应解密失败（Content-Type=$contentType，长度=${body.length}）：" +
                            (error.message ?: error::class.java.simpleName),
                    )
                }
            val root = try {
                JSON.parseToJsonElement(decodedBody).jsonObject
            } catch (_: Exception) {
                throw IllegalStateException(
                    "腾讯动漫 APP 章节评论解密后仍不是 JSON（长度=${decodedBody.length}）",
                )
            }
            val errorCode = root.long("errorCode") ?: root.long("error_code")
            if (errorCode != null && errorCode != APP_SUCCESS_CODE) {
                val message = root.string("msg")
                    ?: root.string("message")
                    ?: root.string("errorMsg")
                    ?: "errorCode=$errorCode"
                throw IllegalStateException("腾讯动漫 APP 章节评论请求失败：$message")
            }

            root["data"] as? JsonObject
                ?: throw IllegalStateException("腾讯动漫 APP 章节评论响应缺少 data")
        }
    }

    private fun chapterTopicType(sortId: String): Int = when (sortId) {
        SORT_LATEST -> APP_CHAPTER_TOPIC_TYPE_NEW
        else -> APP_CHAPTER_TOPIC_TYPE_DEFAULT
    }

    private fun mangaTopicFeed(sortId: String): String = when (sortId) {
        SORT_LATEST -> APP_MANGA_TOPIC_NEW_PUBLISH
        else -> APP_MANGA_TOPIC_NEW_ACTIVITY
    }

    private fun decodeAppApiResponse(body: String): String {
        val trimmed = body.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed

        // Tencent Comics 12.19.9 decrypts responses from a.ac.qq.com /
        // android.ac.qq.com inside its OkHttp interceptor before Gson sees them.
        // Native chain recovered from libcryptutils.so:
        // Base64Decoder -> DES-EDE3/ECB -> PKCS padding -> plaintext JSON.
        val encrypted = Base64.decode(trimmed, Base64.DEFAULT)
        val cipher = Cipher.getInstance("DESede/ECB/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(APP_RESPONSE_DES_EDE3_KEY.toByteArray(Charsets.UTF_8), "DESede"),
        )
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8).trim()
    }

    private fun appApiHeaders(url: okhttp3.HttpUrl): okhttp3.Headers {
        val localtime = System.currentTimeMillis().toString()
        val fakeduin = "0"
        val qimei = ""

        // Tencent Comics 12.19.9 com.network.m.e() signs GET requests as:
        // decoded normalized path + host + fakeduin + qimei + localtime + app suffix.
        val decodedPath = URLDecoder.decode(
            url.encodedPath.replace("//", "/"),
            Charsets.UTF_8.name(),
        )
        val sc = md5Hex(
            decodedPath +
                url.host +
                fakeduin +
                qimei +
                localtime +
                APP_SC_SUFFIX,
        )

        return headers.newBuilder()
            .set("Accept", "application/json, text/plain, */*")
            .set("User-Agent", APP_USER_AGENT)
            .set("version", APP_VERSION)
            .set("channel", APP_CHANNEL)
            .set("fakeduin", fakeduin)
            .set("localtime", localtime)
            .set("userstate", APP_ANONYMOUS_USER_STATE)
            .set("oversea", APP_OVERSEA_STATE)
            .set("sc", sc)
            .build()
    }

    private fun md5Hex(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun communityTopicToComment(topic: JsonObject): Comment? {
        val title = topic.string("title")
            ?.let(::decodeCommentText)
            ?.takeIf(String::isNotBlank)
        val body = topic.string("content")
            ?.let(::decodeCommentText)
            ?.takeIf(String::isNotBlank)
        val content = commentContentWithImages(
            textParts = listOfNotNull(title, body).distinct(),
            imageUrls = topicImageUrls(topic),
        ) ?: return null
        val topicId = topic.string("topicId")
            ?: topic.string("topic_id")
            ?: return null
        val authorId = topic.string("hostQq") ?: topic.string("host_qq")
        val authorName = topic.string("nickName")
            ?: topic.string("nick_name")
            ?: "腾讯动漫用户"
        val avatar = normalizeUrl(topic.string("qqHead") ?: topic.string("qq_head"))

        return Comment(
            id = topicId,
            author = CommentAuthor(
                id = authorId,
                name = authorName,
                avatarUrl = avatar,
            ),
            content = content,
            createdAt = 0L,
            displayTime = topic.string("date"),
            likeCount = topic.long("goodCount") ?: topic.long("good_count") ?: 0L,
            replyCount = topic.long("commentCount") ?: topic.long("comment_count") ?: 0L,
        )
    }

    private fun topicToComment(topic: JsonObject): Comment? {
        val textContent = topic.string("content")
            ?.let(::decodeCommentText)
            ?.takeIf(String::isNotBlank)
        val content = commentContentWithImages(
            textParts = listOfNotNull(textContent),
            imageUrls = topicImageUrls(topic),
        ) ?: return null
        val topicId = topic.string("topicId")
            ?: topic.string("topic_id")
            ?: return null
        val authorId = topic.string("hostQq") ?: topic.string("host_qq")
        val authorName = topic.string("nickName")
            ?: topic.string("nick_name")
            ?: "腾讯动漫用户"
        val avatar = normalizeUrl(topic.string("qqHead") ?: topic.string("qq_head"))

        return Comment(
            id = topicId,
            author = CommentAuthor(
                id = authorId,
                name = authorName,
                avatarUrl = avatar,
            ),
            content = content,
            createdAt = 0L,
            displayTime = topic.string("date"),
            likeCount = topic.long("goodCount") ?: topic.long("good_count") ?: 0L,
            replyCount = topic.long("commentCount") ?: topic.long("comment_count") ?: 0L,
        )
    }

    private fun topicImageUrls(topic: JsonObject): List<String> = (topic["attach"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        .orEmpty()
        .mapNotNull { attachment ->
            normalizeUrl(attachment.string("picUrl") ?: attachment.string("pic_url"))
        }
        .distinct()

    private fun markdownCommentImage(url: String): String = "![]($url)"

    private fun commentContentWithImages(textParts: List<String>, imageUrls: List<String>): String? = (
        textParts.map(String::trim).filter(String::isNotBlank).distinct() +
            imageUrls.filter(String::isNotBlank).distinct().map(::markdownCommentImage)
        )
        .joinToString("\n\n")
        .takeIf(String::isNotBlank)

    override suspend fun getSourceAccount(): SourceAccount? {
        val root = runCatching { getUserBaseInfo() }.getOrNull() ?: return null
        if (root.long("status") != 2L) return null
        val result = root["result"] as? JsonObject ?: return SourceAccount(name = "腾讯动漫账号")
        return SourceAccount(
            id = result.string("uin") ?: result.long("uin")?.toString(),
            name = result.string("nick") ?: result.string("nickname") ?: result.string("name") ?: "腾讯动漫账号",
            avatarUrl = normalizeUrl(result.string("avatar") ?: result.string("head")),
            profileUrl = "$desktopUrl/Home",
        )
    }

    private fun getUserBaseInfo(): JsonObject {
        val url = "$desktopUrl/Ajax/getUserBaseInfo?" + System.currentTimeMillis()
        return client.newCall(GET(url, headers)).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("腾讯动漫账号状态请求失败：HTTP ${response.code}")
            JSON.parseToJsonElement(response.body.string()).jsonObject
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val signedIn = runCatching { getUserBaseInfo().long("status") == 2L }.getOrDefault(false)
        EditTextPreference(screen.context).apply {
            key = "tencent_account_login"
            title = if (signedIn) "腾讯动漫账号：已登录" else "登录腾讯动漫账号"
            summary = if (signedIn) {
                "已检测到腾讯动漫 PC 登录会话；付费章节按当前账号官方权限读取。"
            } else {
                "点击打开腾讯动漫 PC 个人中心，使用 QQ/微信官方登录；登录后返回并刷新源。"
            }
            setOnPreferenceClickListener {
                screen.context.startActivity(
                    Intent().apply {
                        component = ComponentName(screen.context, "eu.kanade.tachiyomi.ui.webview.WebViewActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("url_key", ACCOUNT_LOGIN_URL)
                        putExtra("source_key", id)
                        putExtra("title_key", "腾讯动漫账号登录")
                    },
                )
                true
            }
        }.also(screen::addPreference)
    }

    private fun firstText(element: Element, vararg selectors: String): String? = selectors.firstNotNullOfOrNull { selector -> element.selectFirst(selector)?.text()?.trim()?.takeIf(String::isNotBlank) }

    private fun firstNumber(element: Element, vararg selectors: String): Long = selectors.firstNotNullOfOrNull { selector ->
        element.selectFirst(selector)?.text()?.replace(",", "")?.filter(Char::isDigit)?.toLongOrNull()
    } ?: 0L

    private fun normalizeUrl(value: String?): String? = value?.trim()?.takeIf(String::isNotBlank)?.let {
        when {
            it.startsWith("https:///") -> "https://" + it.removePrefix("https:///")
            it.startsWith("//") -> "https:$it"
            it.startsWith("http://") -> "https://" + it.removePrefix("http://")
            else -> it
        }
    }

    private fun decodeCommentText(value: String): String {
        val text = Jsoup.parse(value).text().trim()
        return TENCENT_EMOJI_CODE.replace(text) { match ->
            val code = match.groupValues[1].toIntOrNull()
            if (code == null) match.value else TENCENT_EMOJI_TEXT[code] ?: match.value
        }
    }

    private fun imageUrlFromElement(image: Element): String? = normalizeUrl(
        image.attr("src")
            .ifBlank { image.attr("data-src") }
            .ifBlank { image.attr("data-original") }
            .ifBlank { image.attr("data-url") },
    )

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.nextPageId(): String? = (
        string("next_page_id") ?: string("nextPageId")
        )
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "-1" && it != "0" }

    companion object {
        const val ID_SEARCH_PREFIX = "id:"
        const val ACCOUNT_LOGIN_URL = "https://ac.qq.com/Home"
        const val TOPIC_PAGE_SIZE = 10
        const val APP_VERSION = "12.19.9"
        const val APP_CHANNEL = "dm306015002"
        const val APP_USER_AGENT = "okhttp/4.10.0"
        const val APP_ANONYMOUS_USER_STATE = "1"
        const val APP_OVERSEA_STATE = "1"
        const val APP_SUCCESS_CODE = 2L
        const val APP_END_OF_LIST = 2L
        const val SORT_DEFAULT = "default"
        const val SORT_LATEST = "latest"
        const val APP_CHAPTER_TOPIC_TYPE_NEW = 2
        const val APP_CHAPTER_TOPIC_TYPE_DEFAULT = 1
        const val APP_CHAPTER_TARGET_TYPE = 3
        const val APP_MANGA_TAG_TARGET_TYPE = 1
        const val APP_MANGA_TOPIC_RECOMMEND = "recommend_topic"
        const val APP_MANGA_TOPIC_NEW_ACTIVITY = "new_topic"
        const val APP_MANGA_TOPIC_NEW_PUBLISH = "new_publish_topic"
        const val APP_TOPIC_COMMENT_PAGE_SIZE = 20
        const val APP_RESPONSE_DES_EDE3_KEY = "42e0d587d5bda41326c2000d"
        const val APP_SC_SUFFIX = "4jo2YHMm0d2VGt59tVYndX9P7eFcw8TvRv5lMqFP1TT"
        val APP_API_HOSTS = listOf("https://a.ac.qq.com", "https://android.ac.qq.com")
        val CHAPTER_IDS = Regex("/ComicView/(?:index/)?id/(\\d+)/cid/(\\d+)")
        val TOPIC_ID = Regex("/Community/topic/topic_id/(\\d+)")
        val PC_COMIC_ID = Regex("/id/(\\d+)", RegexOption.IGNORE_CASE)
        val PC_COMMENT_TIME = Regex("\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}")
        val REPLY_COUNT = Regex("(\\d+)")
        val TENCENT_EMOJI_CODE = Regex("\\[:(\\d{1,3}):]")
        val TENCENT_EMOJI_TEXT = mapOf(
            0 to "🙂",
            1 to "😃",
            2 to "😉",
            3 to "😆",
            4 to "😊",
            5 to "😁",
            6 to "😄",
            7 to "😓",
            8 to "😱",
            9 to "😢",
            10 to "😎",
            11 to "😰",
            12 to "😍",
            13 to "😄",
            14 to "😘",
            15 to "😭",
            16 to "😂",
            17 to "😴",
            18 to "😷",
            19 to "😨",
            20 to "😬",
            21 to "😠",
            22 to "😮",
            23 to "🙄",
            24 to "🤩",
            25 to "🤐",
            26 to "🤔",
            27 to "🙃",
            28 to "😒",
            29 to "🤪",
            30 to "😏",
            31 to "👌",
            32 to "💩",
            33 to "👾",
            34 to "😈",
            35 to "👿",
            36 to "👻",
            37 to "🐷",
            38 to "🐮",
            39 to "🤖",
            40 to "💀",
            41 to "💣",
            42 to "☕",
            43 to "🎂",
            44 to "🍺",
            45 to "🌸",
            46 to "🍉",
            47 to "💰",
            48 to "❤️",
            49 to "🌙",
            50 to "☀️",
            51 to "⭐",
            52 to "🧧",
            53 to "🎉",
            54 to "🧧",
            55 to "🀄",
            56 to "👏",
            57 to "🚫",
            58 to "🔥666",
            59 to "🎶857",
            60 to "🍦",
            61 to "👍",
        )
        val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
