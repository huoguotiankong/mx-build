package eu.kanade.tachiyomi.extension.zh.jmcomicplus

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
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
import eu.kanade.tachiyomi.source.mx.MangaDetailAction
import eu.kanade.tachiyomi.source.mx.MangaDetailActionType
import eu.kanade.tachiyomi.source.mx.MangaDetailField
import eu.kanade.tachiyomi.source.mx.MangaDetailInfo
import eu.kanade.tachiyomi.source.mx.MangaDetailSource
import eu.kanade.tachiyomi.source.mx.MangaDetailValue
import eu.kanade.tachiyomi.source.mx.SourceAccount
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@Source
abstract class JmComicPlus :
    KeiSource(),
    ConfigurableSource,
    CommentSource,
    AccountSource,
    MangaDetailSource {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val preferences = getPreferences()
    private val replyCache = ConcurrentHashMap<String, List<Comment>>()
    private val detailCache = ConcurrentHashMap<String, CachedDetail>()

    override fun Headers.Builder.configureHeaders() = apply {
        set("User-Agent", UA_WEB)
        set("Accept-Language", "zh-CN,zh;q=0.9,zh-TW;q=0.8")
        set("Referer", "${cachedWebBase()}/")
    }

    override fun OkHttpClient.Builder.configureClient() = connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor(ScrambledImageInterceptor())

    override fun getHomeUrl(): String = cachedWebBase()

    override suspend fun getPopularManga(page: Int): MangasPage = dual(
        app = { appList("search?main_tag=0&search_query=&page=$page&o=mv&t=a", page) },
        web = { webList("albums?o=mv&page=$page", page) },
    )

    override suspend fun getLatestUpdates(page: Int): MangasPage = dual(
        app = {
            runCatching { appList("latest?page=$page", page) }
                .getOrElse { appList("search?main_tag=0&search_query=&page=$page&o=mr&t=a", page) }
        },
        web = { webList("albums?o=mr&page=$page", page) },
    )

    override fun getFilterList(data: JsonElement?) = FilterList(JmSortFilter(), JmCategoryFilter())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.filterIsInstance<JmSortFilter>().firstOrNull()?.value() ?: "mr"
        val category = filters.filterIsInstance<JmCategoryFilter>().firstOrNull()?.value() ?: "all"
        val q = query.trim()
        return dual(
            app = {
                if (q.isNotBlank()) {
                    appList("search?main_tag=0&search_query=${enc(q)}&page=$page&o=${enc(sort)}&t=a", page)
                } else if (category != "all") {
                    appList("categories/filter?page=$page&order=&c=${enc(category)}&o=${enc(sort)}", page)
                } else {
                    appList("search?main_tag=0&search_query=&page=$page&o=${enc(sort)}&t=a", page)
                }
            },
            web = {
                val path = if (q.isNotBlank()) {
                    "search/photos?search_query=${enc(q)}&search-type=photos&main_tag=0&o=${enc(sort)}&page=$page"
                } else if (category != "all") {
                    "albums/${enc(category)}?o=${enc(sort)}&page=$page"
                } else {
                    "search/photos?search_query=&search-type=photos&main_tag=0&o=${enc(sort)}&page=$page"
                }
                webList(path, page)
            },
        )
    }

    private fun appList(path: String, page: Int): MangasPage {
        val data = apiRequest(path)
        val list = data.asArrayLike("content", "list", "data", "items")
        val mangas = list.mapNotNull { it as? JsonObject }.mapNotNull(::mangaFromApi).distinctBy { it.url }
        return MangasPage(mangas, mangas.size >= API_PAGE_HINT || (page == 1 && mangas.size >= 30))
    }

    private fun mangaFromApi(o: JsonObject): SManga? {
        val id = o.string("id", "aid", "album_id") ?: return null
        val tags = o.stringList("tags")
        val authors = o.stringList("author")
        return SManga.create().apply {
            url = "/album/$id"
            title = o.string("name", "title").orEmpty().ifBlank { "JM$id" }
            author = authors.joinToString(" / ").takeIf(String::isNotBlank)
            thumbnail_url = coverUrl(o, id)
            genre = tags.distinct().joinToString(", ")
            description = o.string("description", "intro", "update_at")
            status = statusFrom(o.string("status", "finished"))
            initialized = false
        }
    }

    private fun webList(path: String, page: Int): MangasPage {
        val html = webRequest(path)
        val base = cachedWebBase()
        val doc = Jsoup.parse(html, base)
        val seen = HashSet<String>()
        val mangas = doc.select("a[href*=/album/]").mapNotNull { a ->
            val id = ALBUM_ID.find(a.attr("href"))?.groupValues?.getOrNull(1) ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            val root = a.closest(".thumb-overlay-albums,.video-item,.album-item,.row") ?: a.parent() ?: a
            val img = root.selectFirst("img") ?: a.selectFirst("img")
            val title = root.selectFirst(".video-title,.title-truncate,.image-item-text")?.text()
                ?.takeIf(String::isNotBlank)
                ?: a.attr("title").takeIf(String::isNotBlank)
                ?: img?.attr("alt")?.takeIf(String::isNotBlank)
                ?: a.text().trim().takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            SManga.create().apply {
                url = "/album/$id"
                this.title = title
                thumbnail_url = img?.let { absoluteUrl(base, it.attr("data-original").ifBlank { it.attr("data-src").ifBlank { it.attr("src") } }) }
                description = root.text().replace(title, "").trim().takeIf(String::isNotBlank)
                status = SManga.UNKNOWN
                initialized = false
            }
        }
        val hasNext = doc.selectFirst("a[rel=next],.pagination a:matchesOwn(下一|Next|›|»)") != null || mangas.size >= 20
        return MangasPage(mangas, hasNext && mangas.isNotEmpty())
    }

    override fun getMangaUrl(manga: SManga): String = "${cachedWebBase()}/album/${albumId(manga.url)}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val id = ALBUM_ID.find(url.encodedPath)?.groupValues?.getOrNull(1) ?: return null
        return detail(id).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)
        val fresh = detail(albumId(manga.url))
        return SMangaUpdate(if (fetchDetails) fresh.manga else manga, if (fetchChapters) fresh.chapters else chapters)
    }

    private fun detail(id: String): DetailBundle {
        detailCache[id]?.takeIf { System.currentTimeMillis() - it.time < DETAIL_CACHE_MS }?.let { return it.value }
        val value = dual(
            app = { appDetail(id) },
            web = { webDetail(id) },
        )
        detailCache[id] = CachedDetail(System.currentTimeMillis(), value)
        return value
    }

    private fun appDetail(id: String): DetailBundle {
        val a = apiRequest("album?id=${enc(id)}").asObjectLike()
        val authors = a.stringList("author")
        val tags = a.stringList("tags")
        val category = a.obj("category")?.string("title", "name") ?: a.string("category")
        val chapters = a.array("series").orEmptyObjects().mapIndexedNotNull { index, p ->
            val pid = p.string("id", "photo_id", "PID") ?: return@mapIndexedNotNull null
            val sort = p.long("sort", "sort_order", "episode")?.toFloat() ?: (index + 1).toFloat()
            SChapter.create().apply {
                url = "/photo/$pid?jm_album=$id"
                name = p.string("name", "title").orEmpty().ifBlank { "第 ${index + 1} 话" }
                chapter_number = sort
                date_upload = parseDate(p.string("addtime", "update_at", "created_at"))
            }
        }.sortedByDescending { it.chapter_number }
        val manga = SManga.create().apply {
            url = "/album/$id"
            title = a.string("name", "title").orEmpty().ifBlank { "JM$id" }
            author = authors.joinToString(" / ").takeIf(String::isNotBlank)
            artist = a.stringList("works", "work").joinToString(" / ").takeIf(String::isNotBlank)
            genre = (listOfNotNull(category) + tags).distinct().joinToString(", ")
            description = a.string("description", "intro")
            thumbnail_url = coverUrl(a, id)
            status = statusFrom(a.string("status", "finished"))
            initialized = true
        }
        return DetailBundle(manga, chapters, a, "APP/API")
    }

    private fun webDetail(id: String): DetailBundle {
        val html = webRequest("album/$id")
        val base = cachedWebBase()
        val doc = parseWebDocument(html, base)
        val title = doc.selectFirst("h1")?.text().orEmpty().ifBlank { "JM$id" }
        val image = doc.selectFirst(".thumb-overlay img,img")?.let { absoluteUrl(base, it.attr("data-original").ifBlank { it.attr("src") }) }
        val authors = (doc.select("a.web-author-tag").ifEmpty { doc.select("div.panel-body div.tag-block:eq(3) .btn-primary") }).map { it.text().trim() }.filter(String::isNotBlank).distinct()
        val tags = doc.select("#intro-block [data-type=tags] a,[data-type=tags] a,.tag a,span[itemprop=genre] a").map { it.text().trim() }.filter(String::isNotBlank).filterNot { it in setOf("連載中", "连载中", "完結", "完结") }.distinct()
        val description = doc.selectFirst(".intro-collapse-content,#intro-block .p-t-5.p-b-5")?.text()?.substringAfter("敘述：")?.trim()
        val chapterLinks = doc.select("#episode-block a[href^=/photo/],a[href^=/photo/]")
        val chapters = chapterLinks.mapIndexedNotNull { index, a ->
            val pid = PHOTO_ID.find(a.attr("href"))?.groupValues?.getOrNull(1) ?: return@mapIndexedNotNull null
            SChapter.create().apply {
                url = "/photo/$pid?jm_album=$id"
                name = a.selectFirst("li h3")?.ownText()?.trim().orEmpty()
                    .ifBlank { a.text().trim().ifBlank { a.attr("title").ifBlank { "第 ${index + 1} 话" } } }
                chapter_number = (index + 1).toFloat()
            }
        }.distinctBy { it.url }.asReversed().ifEmpty {
            doc.selectFirst("#album_photo_cover a[href^=/photo/]")?.let { a ->
                PHOTO_ID.find(a.attr("href"))?.groupValues?.getOrNull(1)?.let { pid ->
                    listOf(
                        SChapter.create().apply {
                            url = "/photo/$pid?jm_album=$id"
                            name = "单章节"
                            chapter_number = 1f
                        },
                    )
                }
            }.orEmpty()
        }
        val meta = JsonObject(
            mapOf(
                "id" to JsonPrimitive(id),
                "name" to JsonPrimitive(title),
                "author" to JsonArray(authors.map(::JsonPrimitive)),
                "tags" to JsonArray(tags.map(::JsonPrimitive)),
                "description" to JsonPrimitive(description.orEmpty()),
                "image" to JsonPrimitive(image.orEmpty()),
            ),
        )
        val manga = SManga.create().apply {
            url = "/album/$id"
            this.title = title
            author = authors.joinToString(" / ").takeIf(String::isNotBlank)
            genre = tags.joinToString(", ")
            this.description = description
            thumbnail_url = image
            status = SManga.UNKNOWN
            initialized = true
        }
        return DetailBundle(manga, chapters, meta, "网页")
    }

    override suspend fun getMangaDetailInfo(manga: SManga): MangaDetailInfo {
        val id = albumId(manga.url)
        val d = detail(id)
        val a = d.meta
        val authors = a.stringList("author")
        val works = a.stringList("works", "work")
        val actors = a.stringList("actors", "actor")
        val tags = a.stringList("tags")
        val category = a.obj("category")?.string("title", "name") ?: a.string("category")
        val fields = buildList {
            addClickable("作者", authors, MangaDetailActionType.SOURCE_SEARCH)
            addClickable("作品", works, MangaDetailActionType.SOURCE_SEARCH)
            addClickable("登场人物", actors, MangaDetailActionType.SOURCE_SEARCH)
            addClickable("分类", listOfNotNull(category), MangaDetailActionType.SOURCE_GENRE)
            addClickable("标签", tags, MangaDetailActionType.SOURCE_GENRE)
            add(MangaDetailField("JM 编号", listOf(MangaDetailValue(id))))
            add(MangaDetailField("章节", listOf(MangaDetailValue(d.chapters.size.toString()))))
            a.long("total_views", "views", "view_count")?.let { add(MangaDetailField("浏览", listOf(MangaDetailValue(it.toString())))) }
            a.long("likes", "like", "like_count")?.let { add(MangaDetailField("点赞", listOf(MangaDetailValue(it.toString())))) }
            a.long("comment_total", "commentCount", "comment_count")?.let { add(MangaDetailField("评论", listOf(MangaDetailValue(it.toString())))) }
            a.string("update_at", "updated_at", "addtime")?.takeIf(String::isNotBlank)?.let { add(MangaDetailField("更新时间", listOf(MangaDetailValue(it)))) }
            add(MangaDetailField("当前线路", listOf(MangaDetailValue(d.route))))
        }
        return MangaDetailInfo(fields = fields, replaceDefaultFields = true)
    }

    private fun MutableList<MangaDetailField>.addClickable(label: String, values: List<String>, action: MangaDetailActionType) {
        val clean = values.map(String::trim).filter(String::isNotBlank).distinct()
        if (clean.isEmpty()) return
        add(MangaDetailField(label, clean.map { MangaDetailValue(it, MangaDetailAction(action, it)) }))
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val pid = photoId(chapter.url)
        return "${cachedWebBase()}/photo/$pid/?shunt=${imageShunt()}"
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pid = photoId(chapter.url)
        val data = dual(app = { appPages(pid) }, web = { webPages(pid) })
        if (data.images.isEmpty()) throw IOException("本章没有返回正文图片")
        return data.images.distinct().mapIndexed { index, image ->
            val file = image.substringAfterLast('/').substringBefore('?')
            Page(index, getChapterUrl(chapter), image.toHttpUrlWithMarkers(pid, data.scrambleId, file))
        }
    }

    private fun appPages(pid: String): PageBundle {
        val d = apiRequest("chapter?id=${enc(pid)}").asObjectLike()
        val images = d.array("images").orEmpty().mapNotNull { e ->
            when (e) {
                is JsonPrimitive -> e.contentOrNull
                is JsonObject -> e.string("image", "name", "filename", "url")
                else -> null
            }
        }.mapNotNull { f ->
            f.takeIf(String::isNotBlank)?.let {
                if (it.startsWith("http://") || it.startsWith("https://")) it else "https://${imageHost()}/media/photos/$pid/${it.trimStart('/')}"
            }
        }
        return PageBundle(images, scrambleId(pid))
    }

    private fun webPages(pid: String): PageBundle {
        val images = mutableListOf<String>()
        var next: String? = "photo/$pid/?shunt=${imageShunt()}"
        var guard = 0
        while (!next.isNullOrBlank() && guard++ < MAX_WEB_PAGE_REQUESTS) {
            val html = webRequest(next)
            val base = cachedWebBase()
            val doc = parseWebDocument(html, base)
            doc.select("div.center.scramble-page.spnotice_chk[id*=0] img,.scramble-page img,img[data-original*=/media/photos/]").forEach { img ->
                val raw = img.attr("data-original").ifBlank { img.attr("data-src").ifBlank { img.attr("data-cfsrc").ifBlank { img.attr("src") } } }
                val value = absoluteUrl(base, raw).substringBefore('?')
                if (value.contains("/media/photos/") && !value.contains("blank.jpg")) images += value
            }
            next = doc.selectFirst("a.prevnext")?.attr("abs:href")?.takeIf(String::isNotBlank)
        }
        val scramble = runCatching { scrambleId(pid) }.getOrDefault(DEFAULT_SCRAMBLE_ID)
        return PageBundle(images.distinct(), scramble)
    }

    override val commentCapabilities = CommentCapabilities(true, true, true, true, false, true)

    override suspend fun getMangaCommentTarget(manga: SManga) = CommentTarget(albumId(manga.url), getMangaUrl(manga), CommentTargetKind.MANGA)

    override suspend fun getChapterCommentTarget(manga: SManga, chapter: SChapter) = CommentTarget(photoId(chapter.url), getChapterUrl(chapter), CommentTargetKind.CHAPTER)

    override suspend fun getComments(target: CommentTarget, page: Int): CommentPage = dual(
        app = { apiComments(target, page.coerceAtLeast(1)) },
        web = { webComments(target, page.coerceAtLeast(1)) },
    )

    private fun apiComments(target: CommentTarget, page: Int): CommentPage {
        val d = apiRequest("forum?aid=${enc(target.id)}&mode=all&page=$page")
        val box = d.asObjectLikeOrNull()
        val arr = when (d) {
            is JsonArray -> d
            is JsonObject -> d.array("content", "list", "comments", "data") ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        val comments = arr.mapNotNull { it as? JsonObject }.mapNotNull { commentFromJson(it, null, target.id) }
        val total = box?.long("total", "count", "total_count") ?: comments.size.toLong()
        return CommentPage(comments, comments.isNotEmpty() && (total > page * comments.size || comments.size >= COMMENT_PAGE_HINT), total)
    }

    private fun webComments(target: CommentTarget, page: Int): CommentPage {
        val raw = webRequest("ajax/album_pagination", "POST", mapOf("video_id" to target.id, "page" to page.toString(), "series" to "1", "with_ad_wcm" to "1"))
        val maybeJson = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        val html = (maybeJson as? JsonObject)?.string("html", "content", "data", "result", "msg")?.takeIf { it.contains('<') } ?: raw
        val doc = Jsoup.parse(html, cachedWebBase())
        val comments = doc.select("[id^=comment_],.forum-comment,.comment-list .media,.well").mapIndexedNotNull { index, e ->
            val id = (e.attr("data-id").ifBlank { e.id() }).replace(Regex("\\D+"), "").ifBlank { "w${page}_$index" }
            val name = e.selectFirst(".username,.author,.media-heading,strong")?.text()?.trim().orEmpty().ifBlank { "JM用户" }
            val body = e.selectFirst(".comment-content,.content,.forum-content,.media-body p")?.text()?.trim() ?: e.text().replace(name, "").trim()
            if (body.isBlank()) return@mapIndexedNotNull null
            Comment(id, CommentAuthor(name = name), body, 0L)
        }.distinctBy(Comment::id)
        return CommentPage(comments, comments.isNotEmpty(), comments.size.toLong())
    }

    private fun commentFromJson(o: JsonObject, parentId: String?, targetId: String): Comment? {
        val id = o.string("CID", "id", "cid", "comment_id") ?: return null
        val u = o.obj("user", "member") ?: JsonObject(emptyMap())
        val name = o.string("username", "nickname", "name") ?: u.string("username", "nickname", "name") ?: "JM用户"
        val rawContent = o.string("content", "comment", "message").orEmpty()
        val content = if (rawContent.contains('<') || rawContent.contains('&')) Jsoup.parseBodyFragment(rawContent).text() else rawContent
        val photo = o.string("photo", "avatar") ?: u.string("photo", "avatar")
        val rawReplies = o.element("replys", "replies", "reply", "children")
        val replyArray = when (rawReplies) {
            is JsonArray -> rawReplies
            is JsonObject -> rawReplies.array("list", "data", "content") ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        val replies = replyArray.mapNotNull { it as? JsonObject }.mapNotNull { commentFromJson(it, id, targetId) }
        replyCache["$targetId:$id"] = replies
        val time = o.string("addtime", "update_at", "created_at", "createdAt", "time")
        return Comment(
            id = id,
            author = CommentAuthor(o.string("UID", "uid", "user_id") ?: u.string("id", "uid"), name, photo?.let(::userAvatarUrl)),
            content = content.trim(),
            createdAt = parseDate(time),
            displayTime = time,
            likeCount = o.long("likes", "like", "like_count", "vote_up") ?: 0,
            replyCount = maxOf(o.long("reply_count", "replyCount") ?: 0, replies.size.toLong()),
            likedByMe = false,
            parentId = parentId,
        )
    }

    override suspend fun getCommentReplies(target: CommentTarget, comment: Comment, page: Int): CommentPage {
        if (page > 1) return CommentPage(emptyList(), false, comment.replyCount)
        replyCache["${target.id}:${comment.id}"]?.let { return CommentPage(it, false, comment.replyCount) }
        runCatching { getComments(target, 1) }
        val replies = replyCache["${target.id}:${comment.id}"].orEmpty()
        return CommentPage(replies, false, comment.replyCount.takeIf { it > 0 } ?: replies.size.toLong())
    }

    override suspend fun postComment(target: CommentTarget, content: String): Comment = postInternal(target, null, content)

    override suspend fun postCommentReply(target: CommentTarget, parent: Comment, content: String): Comment = postInternal(target, parent, content)

    private suspend fun postInternal(target: CommentTarget, parent: Comment?, content: String): Comment {
        val text = content.trim()
        if (text.isBlank()) throw IOException("评论内容不能为空")
        if (!hasCredentials() && storedAvs().isBlank()) throw IOException("请先在扩展设置中填写账号密码并登录")
        val errors = mutableListOf<String>()
        val webOk = runCatching {
            ensureWebLogin()
            val form = linkedMapOf("video_id" to target.id, "comment" to text, "originator" to "")
            if (parent == null) {
                form["status"] = "true"
            } else {
                form["comment_id"] = parent.id
                form["is_reply"] = "1"
                form["forum_subject"] = "1"
            }
            val raw = webRequest("ajax/album_comment", "POST", form)
            val o = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            if (o?.boolLike("err") == true || o?.string("status") in setOf("0", "false")) throw IOException(o.string("msg", "error") ?: "发表评论失败")
        }.onFailure { errors += "Web: ${it.message}" }.isSuccess
        if (!webOk) {
            runCatching { apiRequest("comment", "POST", mapOf("aid" to target.id, "comment" to text, "comment_id" to (parent?.id ?: "0"))) }
                .onFailure { errors += "APP/API: ${it.message}" }
                .getOrElse { throw IOException("发送失败：${errors.joinToString(" | ")}") }
        }
        val fresh = if (parent == null) {
            runCatching { getComments(target, 1).comments.firstOrNull { it.content == text } }.getOrNull()
        } else {
            runCatching { getCommentReplies(target, parent, 1).comments.firstOrNull { it.content == text } }.getOrNull()
        }
        return fresh ?: Comment("pending-${System.currentTimeMillis()}", currentAuthor(), text, System.currentTimeMillis(), parentId = parent?.id)
    }

    override suspend fun getSourceAccount(): SourceAccount? {
        val profile = preferences.getString(PREF_PROFILE, "").orEmpty().takeIf(String::isNotBlank)?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val username = profile?.string("username", "name") ?: preferences.getString(PREF_USERNAME, "").orEmpty()
        if (username.isBlank() || (storedAvs().isBlank() && preferences.getString(PREF_WEB_USER, "").isNullOrBlank())) return null
        return SourceAccount(profile?.string("uid", "id"), username, profile?.string("photo", "avatar")?.let(::userAvatarUrl))
    }

    private suspend fun currentAuthor(): CommentAuthor {
        val a = runCatching { getSourceAccount() }.getOrNull()
        return CommentAuthor(a?.id, a?.name ?: "我", a?.avatarUrl)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_ROUTE
            title = "线路模式"
            entries = arrayOf("自动（推荐）", "APP/API", "网页")
            entryValues = arrayOf("auto", "app", "web")
            setDefaultValue("auto")
            summary = "%s"
        }.also(screen::addPreference)
        ListPreference(screen.context).apply {
            key = PREF_SHUNT
            title = "图片分流"
            entries = arrayOf("1（推荐）", "2", "3", "4")
            entryValues = arrayOf("1", "2", "3", "4")
            setDefaultValue("1")
            summary = "%s"
        }.also(screen::addPreference)
        EditTextPreference(screen.context).apply {
            key = PREF_MANUAL_WEB
            title = "手动网页域名（可选）"
            summary = "留空自动更新；填写完整 https:// 域名可固定网页线路"
        }.also(screen::addPreference)
        EditTextPreference(screen.context).apply {
            key = PREF_USERNAME
            title = "账号"
            summary = "仅保存在本机"
        }.also(screen::addPreference)
        EditTextPreference(screen.context).apply {
            key = PREF_PASSWORD
            title = "密码"
            summary = "仅保存在本机，用于 APP/API AVS 与网页登录"
            setOnBindEditTextListener { it.inputType = 0x00000081 }
        }.also(screen::addPreference)
        actionPreference(screen, "jm_login_action", "登录 / 刷新登录", "同时尝试 APP/API 与网页；任一路成功即保留") {
            val result = runCatching { loginBoth() }
            showToast(screen, result.fold({ "登录成功：${it.joinToString(" + ")}" }, { "登录失败：${it.message}" }))
        }
        actionPreference(screen, "jm_logout_action", "退出登录", "清除本机 AVS 与扩展登录状态") {
            preferences.edit().remove(PREF_AVS).remove(PREF_PROFILE).remove(PREF_WEB_USER).remove(PREF_API_USER).apply()
            showToast(screen, "已清除扩展登录状态")
        }
        actionPreference(screen, "jm_refresh_domains", "立即刷新动态域名", "重新发现 APP/API 节点和网页域名") {
            val result = runCatching {
                val api = refreshApiDomains(true)
                val web = refreshWebDomains(true)
                "API ${api.firstOrNull().orEmpty()} · Web ${web.firstOrNull().orEmpty()}"
            }
            showToast(screen, result.fold({ "刷新完成：$it" }, { "刷新失败：${it.message}" }))
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

    private fun showToast(screen: PreferenceScreen, text: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(screen.context, text, Toast.LENGTH_LONG).show() }
    }

    private fun loginBoth(): List<String> {
        val user = preferences.getString(PREF_USERNAME, "").orEmpty().trim()
        val password = preferences.getString(PREF_PASSWORD, "").orEmpty()
        if (user.isBlank() || password.isBlank()) throw IOException("请输入账号和密码")
        val ok = mutableListOf<String>()
        val errors = mutableListOf<String>()
        runCatching { apiLogin(user, password) }.onSuccess { ok += "APP/API" }.onFailure { errors += "APP: ${it.message}" }
        runCatching { webLogin(user, password) }.onSuccess { ok += "网页" }.onFailure { errors += "Web: ${it.message}" }
        if (ok.isEmpty()) throw IOException(errors.joinToString(" | "))
        return ok
    }

    private fun apiLogin(user: String, password: String) {
        val errors = mutableListOf<String>()
        for (version in APP_VERSIONS) {
            val result = runCatching { apiRequest("login", "POST", mapOf("username" to user, "password" to password), version = version, noAvs = true).asObjectLike() }
            val d = result.getOrNull()
            if (d != null && listOf(d.string("uid"), d.string("username"), d.string("s")).any { !it.isNullOrBlank() }) {
                d.string("s")?.takeIf(String::isNotBlank)?.let { preferences.edit().putString(PREF_AVS, it).apply() }
                preferences.edit().putString(PREF_PROFILE, d.toString()).putString(PREF_API_USER, user).putString(PREF_LOGIN_VERSION, version).apply()
                return
            }
            errors += "$version: ${result.exceptionOrNull()?.message ?: "登录响应缺少用户字段"}"
        }
        throw IOException("APP/API 登录失败：${errors.joinToString(" | ")}")
    }

    private fun webLogin(user: String, password: String) {
        val raw = webRequest("login", "POST", mapOf("username" to user, "password" to password, "id_remember" to "on", "login_remember" to "on", "submit_login" to ""))
        if (Regex("帳號.*密碼|账号.*密码|登入失敗|登录失败|incorrect|invalid password|alert-danger", RegexOption.IGNORE_CASE).containsMatchIn(raw)) throw IOException("网页端账号或密码错误")
        preferences.edit().putString(PREF_WEB_USER, user).apply()
    }

    private fun ensureWebLogin() {
        if (!preferences.getString(PREF_WEB_USER, "").isNullOrBlank()) return
        val user = preferences.getString(PREF_USERNAME, "").orEmpty().trim()
        val pass = preferences.getString(PREF_PASSWORD, "").orEmpty()
        if (user.isBlank() || pass.isBlank()) throw IOException("网页登录需要账号密码")
        webLogin(user, pass)
    }

    private fun hasCredentials() = preferences.getString(PREF_USERNAME, "").orEmpty().isNotBlank() && preferences.getString(PREF_PASSWORD, "").orEmpty().isNotBlank()
    private fun storedAvs() = preferences.getString(PREF_AVS, "").orEmpty()

    private fun <T> dual(app: () -> T, web: () -> T): T {
        val mode = preferences.getString(PREF_ROUTE, "auto") ?: "auto"
        val last = preferences.getString(PREF_LAST_ROUTE, "app") ?: "app"
        val order = when (mode) {
            "app" -> listOf("app")
            "web" -> listOf("web")
            else -> if (last == "web") listOf("web", "app") else listOf("app", "web")
        }
        val errors = mutableListOf<String>()
        for (route in order) {
            val r = runCatching { if (route == "app") app() else web() }
            if (r.isSuccess) {
                preferences.edit().putString(PREF_LAST_ROUTE, route).apply()
                return r.getOrThrow()
            }
            errors += "${if (route == "app") "APP/API" else "网页"}: ${r.exceptionOrNull()?.message}"
        }
        throw IOException(errors.joinToString(" | "))
    }

    private fun apiRequest(path: String, method: String = "GET", form: Map<String, String> = emptyMap(), version: String? = null, noAvs: Boolean = false): JsonElement {
        val hosts = refreshApiDomains(false).let { cached ->
            val last = preferences.getString(PREF_API_HOST, "").orEmpty()
            (listOf(last) + cached).filter(String::isNotBlank).distinct()
        }
        val errors = mutableListOf<String>()
        for (host in hosts) {
            for (secret in TOKEN_SECRETS) {
                val result = runCatching {
                    val ts = (System.currentTimeMillis() / 1000L).toString()
                    val req = Request.Builder().url("https://${normalizeHost(host)}/${path.trimStart('/')}").headers(apiHeaders(ts, secret, version, noAvs)).apply {
                        if (method.equals("GET", true)) get() else post(FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build())
                    }.build()
                    client.newCall(req).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val raw = response.body.string().trimStart('\uFEFF')
                        if (raw.firstOrNull { !it.isWhitespace() } != '{') {
                            throw IOException("API 节点返回非 JSON，切换节点重试")
                        }
                        val value = decodeEnvelope(raw, ts)
                        preferences.edit().putString(PREF_API_HOST, normalizeHost(host)).putString(PREF_LAST_ROUTE, "app").apply()
                        value
                    }
                }
                result.onSuccess { return it }.onFailure { errors += "${normalizeHost(host)}: ${it.message}" }
            }
        }
        throw IOException("APP/API 线路失败：${errors.takeLast(6).joinToString(" | ")}")
    }

    private fun apiHeaders(ts: String, secret: String, version: String?, noAvs: Boolean): Headers = Headers.Builder().apply {
        set("User-Agent", UA_APP)
        set("Accept", "application/json, text/plain, */*")
        set("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
        set("X-Requested-With", "com.JMComic3.app")
        set("Referer", "https://${preferences.getString(PREF_API_HOST, API_FALLBACK.first()).orEmpty().ifBlank { API_FALLBACK.first() }}/")
        set("token", md5Hex(ts + secret))
        set("tokenparam", "$ts,${version ?: preferences.getString(PREF_APP_VERSION, APP_VERSION).orEmpty().ifBlank { APP_VERSION }}")
        val avs = if (noAvs) "" else storedAvs()
        set("Cookie", API_BOOTSTRAP_COOKIE + avs.takeIf(String::isNotBlank)?.let { "; AVS=$it" }.orEmpty())
    }.build()

    private fun decodeEnvelope(raw: String, ts: String): JsonElement {
        val box = json.parseToJsonElement(raw) as? JsonObject ?: throw IOException("API 返回不是 JSON")
        val code = box.long("code") ?: -1
        if (code != 200L) throw IOException("API code=$code ${box.string("errorMsg", "msg").orEmpty()}")
        val data = box["data"] ?: JsonNull
        if (data is JsonNull || data is JsonArray || data is JsonObject) return if (data is JsonNull) JsonArray(emptyList()) else data
        val enc = (data as? JsonPrimitive)?.contentOrNull ?: return data
        val decoded = aesDecode(enc, ts, DATA_SECRET)
        return runCatching { json.parseToJsonElement(decoded) }.getOrElse { JsonPrimitive(decoded) }
    }

    private fun refreshApiDomains(force: Boolean): List<String> {
        val now = System.currentTimeMillis()
        if (!force) {
            val cached = readStringList(PREF_API_DOMAINS)
            val at = preferences.getLong(PREF_API_DOMAINS_AT, 0L)
            if (cached.isNotEmpty() && now - at < API_DOMAIN_TTL) return cached
        }
        for (url in API_DOMAIN_SERVERS) {
            val result = runCatching {
                val req = Request.Builder().url(url).header("User-Agent", UA_WEB).build()
                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val raw = response.body.string().trim().replace(Regex("^[^A-Za-z0-9+/=]+"), "")
                    val decoded = aesDecode(raw, "", DOMAIN_SECRET)
                    val obj = json.parseToJsonElement(decoded) as? JsonObject ?: throw IOException("动态域名配置不是 JSON")
                    val element = obj["Server"] ?: obj["server"] ?: obj["data"]
                    val list = when (element) {
                        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        is JsonPrimitive -> element.contentOrNull.orEmpty().split(Regex("[\\s,]+"))
                        else -> emptyList()
                    }.map(::normalizeHost).filter(String::isNotBlank).distinct()
                    if (list.isEmpty()) throw IOException("动态域名列表为空")
                    list
                }
            }.getOrNull()
            if (!result.isNullOrEmpty()) {
                writeStringList(PREF_API_DOMAINS, result)
                preferences.edit().putLong(PREF_API_DOMAINS_AT, now).apply()
                return result
            }
        }
        return (readStringList(PREF_API_DOMAINS) + API_FALLBACK).map(::normalizeHost).filter(String::isNotBlank).distinct()
    }

    private fun webRequest(path: String, method: String = "GET", form: Map<String, String> = emptyMap()): String {
        fun attempt(candidates: List<String>): String? {
            for (base in candidates.filterNot(::isLegacyWebBase).distinct()) {
                val result = runCatching {
                    val url = if (path.startsWith("http://") || path.startsWith("https://")) path else "${base.trimEnd('/')}/${path.trimStart('/')}"
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", UA_WEB)
                        .header("Referer", "${base.trimEnd('/')}/")
                        .header("Accept-Language", "zh-CN,zh;q=0.9")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                        .header("Cache-Control", "no-cache")
                        .header("Pragma", "no-cache")
                        .apply {
                            if (method.equals("GET", true)) get() else post(FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build())
                        }
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val body = response.body.string()
                        if (body.length < 100) throw IOException("网页响应过短")
                        val finalBase = "${response.request.url.scheme}://${response.request.url.host}"
                        preferences.edit().putString(PREF_WEB_BASE, finalBase).putString(PREF_LAST_ROUTE, "web").apply()
                        body
                    }
                }
                if (result.isSuccess) return result.getOrThrow()
            }
            return null
        }

        val quick = (listOf(cachedWebBase()) + refreshWebDomains(false) + WEB_FALLBACK).distinct()
        attempt(quick)?.let { return it }
        val refreshed = refreshWebDomains(true)
        attempt(refreshed + WEB_FALLBACK)?.let { return it }
        throw IOException("网页线路失败：当前镜像均不可用，请稍后重试或在设置中手动指定域名")
    }

    private fun refreshWebDomains(force: Boolean): List<String> {
        val manual = preferences.getString(PREF_MANUAL_WEB, "").orEmpty().trim().trimEnd('/')
        if (manual.startsWith("http://") || manual.startsWith("https://")) return listOf(manual)
        val cached = readStringList(PREF_WEB_DOMAINS).filterNot(::isLegacyWebBase)
        if (!force && cached.isNotEmpty()) return cached
        if (!force) return WEB_FALLBACK

        val out = linkedSetOf<String>()
        out += WEB_FALLBACK
        runCatching {
            val req = Request.Builder().url(WEB_DOMAIN_FEED).header("User-Agent", UA_WEB).build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    response.body.string().lineSequence()
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .map { if (it.startsWith("http")) it.trimEnd('/') else "https://${normalizeHost(it)}" }
                        .filterNot(::isLegacyWebBase)
                        .forEach(out::add)
                }
            }
        }
        runCatching {
            val req = Request.Builder().url(WEB_REDIRECT).header("User-Agent", UA_WEB).build()
            client.newCall(req).execute().use { response ->
                "${response.request.url.scheme}://${response.request.url.host}".takeUnless(::isLegacyWebBase)?.let(out::add)
            }
        }
        for (pub in WEB_FALLBACK.take(4)) {
            runCatching {
                val req = Request.Builder().url(pub).header("User-Agent", UA_WEB).build()
                client.newCall(req).execute().use { response ->
                    val html = response.body.string()
                    DOMAIN_REGEX.findAll(html).map { it.value.trimEnd('/', '\\', '"') }.filterNot(::isLegacyWebBase).forEach(out::add)
                    HREF_REGEX.findAll(html).mapNotNull { it.groupValues.getOrNull(1) }.filterNot(::isLegacyWebBase).forEach(out::add)
                    BARE_DOMAIN_REGEX.findAll(html)
                        .mapNotNull { it.groupValues.getOrNull(1) }
                        .map { "https://${it.lowercase(Locale.ROOT)}" }
                        .filterNot(::isLegacyWebBase)
                        .forEach(out::add)
                }
            }
        }
        val result = out.toList().take(MAX_WEB_DOMAINS)
        writeStringList(PREF_WEB_DOMAINS, result)
        preferences.edit().putLong(PREF_WEB_DOMAINS_AT, System.currentTimeMillis()).apply()
        return result
    }

    private fun probeWeb(base: String): Boolean = runCatching {
        val req = Request.Builder().url("${base.trimEnd('/')}/").header("User-Agent", UA_WEB).build()
        client.newCall(req).execute().use { it.isSuccessful && it.body.string().length > 200 }
    }.getOrDefault(false)

    private fun cachedWebBase(): String {
        val manual = preferences.getString(PREF_MANUAL_WEB, "").orEmpty().trim().trimEnd('/')
        if (manual.startsWith("http://") || manual.startsWith("https://")) return manual
        val cached = preferences.getString(PREF_WEB_BASE, WEB_PUBLIC).orEmpty().ifBlank { WEB_PUBLIC }.trimEnd('/')
        return if (isLegacyWebBase(cached)) WEB_PUBLIC else cached
    }

    private fun isLegacyWebBase(value: String): Boolean {
        val host = hostOf(value).lowercase(Locale.ROOT)
        return host == "jmcomicgo.org" || host == "jmcomicgo.me"
    }

    private fun parseWebDocument(html: String, base: String): Document {
        val doc = Jsoup.parse(html, base)
        doc.select("#wrapper > script").forEach { script ->
            val code = script.html()
            if (!code.contains("base64DecodeUtf8") || !code.contains("document.write")) return@forEach
            DETAIL_BASE64_REGEX.findAll(code).forEach { match ->
                runCatching { String(Base64.decode(match.groupValues[1], Base64.DEFAULT), Charsets.UTF_8) }
                    .getOrNull()
                    ?.takeIf(String::isNotBlank)
                    ?.let { doc.body().append(it) }
            }
        }
        return doc
    }

    private fun imageHost(): String {
        preferences.getString(PREF_IMAGE_HOST, "").orEmpty().takeIf(String::isNotBlank)?.let { return normalizeHost(it) }
        runCatching {
            val d = apiRequest("setting").asObjectLike()
            d.string("jm3_version", "version")?.takeIf(String::isNotBlank)?.let { preferences.edit().putString(PREF_APP_VERSION, it).apply() }
            d.string("img_host")?.takeIf(String::isNotBlank)?.let {
                val host = normalizeHost(it)
                preferences.edit().putString(PREF_IMAGE_HOST, host).apply()
                return host
            }
        }
        return IMG_FALLBACK.first()
    }

    private fun scrambleId(pid: String): Int {
        val hosts = refreshApiDomains(false).let { (listOf(preferences.getString(PREF_API_HOST, "").orEmpty()) + it).filter(String::isNotBlank).distinct() }
        for (host in hosts) {
            val result = runCatching {
                val ts = (System.currentTimeMillis() / 1000L).toString()
                val req = Request.Builder().url("https://${normalizeHost(host)}/chapter_view_template?id=${enc(pid)}&mode=vertical&page=0&app_img_shunt=${imageShunt()}&express=off&v=$ts").headers(apiHeaders(ts, CONTENT_SECRET, "", false)).build()
                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    SCRAMBLE_REGEX.find(response.body.string())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: throw IOException("未找到 scramble_id")
                }
            }.getOrNull()
            if (result != null) return result
        }
        return DEFAULT_SCRAMBLE_ID
    }

    private fun imageShunt() = preferences.getString(PREF_SHUNT, "1").orEmpty().ifBlank { "1" }

    private fun coverUrl(o: JsonObject, id: String): String {
        val raw = o.string("image", "pic_s", "cover", "thumb").orEmpty()
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val host = preferences.getString(PREF_IMAGE_HOST, "").orEmpty().ifBlank { IMG_FALLBACK.first() }
        if (raw.isNotBlank()) return if (raw.contains("/media/")) "https://${normalizeHost(host)}/${raw.trimStart('/')}" else "https://${normalizeHost(host)}/media/albums/${raw.trimStart('/')}"
        return "https://${normalizeHost(host)}/media/albums/${id}_3x4.jpg"
    }

    private fun userAvatarUrl(raw: String): String {
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val host = preferences.getString(PREF_IMAGE_HOST, "").orEmpty().ifBlank { IMG_FALLBACK.first() }
        val path = raw.trimStart('/').let { if (it.startsWith("media/users/")) it else "media/users/$it" }
        return "https://${normalizeHost(host)}/$path"
    }

    private fun String.toHttpUrlWithMarkers(pid: String, scramble: Int, file: String): String {
        val sep = if (contains('?')) '&' else '?'
        return this + sep + "jm_pid=${enc(pid)}&jm_scramble=$scramble&jm_file=${enc(file)}"
    }

    private fun readStringList(key: String): List<String> {
        val raw = preferences.getString(key, "[]").orEmpty()
        return runCatching { (json.parseToJsonElement(raw) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }.getOrNull().orEmpty()
    }

    private fun writeStringList(key: String, values: List<String>) {
        preferences.edit().putString(key, JsonArray(values.distinct().map(::JsonPrimitive)).toString()).apply()
    }

    private fun aesDecode(encoded: String, ts: String, secret: String): String {
        val key = md5Hex(ts + secret).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(Base64.decode(encoded.trim(), Base64.DEFAULT)).toString(Charsets.UTF_8)
    }

    private fun md5Hex(text: String): String = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    private fun normalizeHost(value: String): String = value.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').trim()

    private fun absoluteUrl(base: String, value: String): String {
        val u = value.trim()
        if (u.isBlank()) return ""
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (u.startsWith("//")) return "https:$u"
        return "${base.trimEnd('/')}/${u.trimStart('/')}"
    }

    private fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: url
    private fun albumId(url: String): String = ALBUM_ID.find(url)?.groupValues?.getOrNull(1) ?: Regex("[?&](?:id|aid|jm_album)=(\\d+)").find(url)?.groupValues?.getOrNull(1) ?: throw IOException("无法识别漫画 ID")
    private fun photoId(url: String): String = PHOTO_ID.find(url)?.groupValues?.getOrNull(1) ?: Regex("[?&](?:pid|photo)=(\\d+)").find(url)?.groupValues?.getOrNull(1) ?: throw IOException("无法识别章节 ID")

    private fun statusFrom(value: String?): Int = when {
        value.isNullOrBlank() -> SManga.UNKNOWN
        Regex("完结|完結|completed|finished|1", RegexOption.IGNORE_CASE).containsMatchIn(value) -> SManga.COMPLETED
        else -> SManga.ONGOING
    }

    private fun parseDate(value: String?): Long {
        val s = value?.trim().orEmpty()
        if (s.isBlank()) return 0L
        s.toLongOrNull()?.let { return if (it in 1..9_999_999_999L) it * 1000L else it }
        for (p in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd", "yyyy/MM/dd HH:mm:ss")) {
            val time = runCatching { SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Taipei") }.parse(s)?.time }.getOrNull()
            if (time != null) return time
        }
        return 0L
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private data class DetailBundle(val manga: SManga, val chapters: List<SChapter>, val meta: JsonObject, val route: String)
    private data class CachedDetail(val time: Long, val value: DetailBundle)
    private data class PageBundle(val images: List<String>, val scrambleId: Int)

    companion object {
        private const val UA_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val UA_APP = "Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.114 Safari/537.36"
        private const val WEB_PUBLIC = "https://18comic.vip"
        private const val WEB_PUBLIC_OLD = "https://18comic.ink"
        private const val WEB_REDIRECT = "https://jm365.work/3YeBdF"
        private const val APP_VERSION = "2.1.2"
        private val APP_VERSIONS = listOf("2.1.2", "2.0.20")
        private const val DATA_SECRET = "185Hcomic3PAPP7R"
        private val TOKEN_SECRETS = listOf("185Hcomic3PAPP7R")
        private const val CONTENT_SECRET = "18comicAPPContent"
        private const val DOMAIN_SECRET = "diosfjckwpqpdfjkvnqQjsik"
        private val API_DOMAIN_SERVERS = listOf("https://rup4a04-c01.tos-ap-southeast-1.bytepluses.com/newsvr-2025.txt", "https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt", "https://rup4a04-c03.tos-cn-beijing.bytepluses.com.cn/newsvr-2025.txt")
        private val API_FALLBACK = listOf("www.cdnhjk.net", "www.cdngwc.cc", "www.cdngwc.net", "www.cdngwc.club", "www.cdnaspa.vip", "www.cdnaspa.club", "www.cdnplaystation6.org", "www.cdnplaystation6.vip", "www.cdnplaystation6.cc")
        private val IMG_FALLBACK = listOf("cdn-msp.jmapiproxy1.cc", "cdn-msp.jmapiproxy2.cc", "cdn-msp2.jmapiproxy2.cc", "cdn-msp3.jmapiproxy2.cc", "cdn-msp.jmapinodeudzn.net", "cdn-msp3.jmapinodeudzn.net")
        private val WEB_FALLBACK = listOf(
            WEB_PUBLIC,
            "https://18comic.ink",
            "https://jmcomic-zzz.one",
            "https://jmcomic-zzz.org",
            "https://18comic-ive.club",
            "https://18comic-aspa.org",
            "https://18comic-wantgo.cc",
        )
        private val ALBUM_ID = Regex("/album/(\\d+)", RegexOption.IGNORE_CASE)
        private val PHOTO_ID = Regex("/photo/(\\d+)", RegexOption.IGNORE_CASE)
        private val SCRAMBLE_REGEX = Regex("var\\s+scramble_id\\s*=\\s*(\\d+)")
        private val DOMAIN_REGEX = Regex("https?://(?:18comic|jmcomic|jm-comic|jm365)[^<\\\"'\\s]+", RegexOption.IGNORE_CASE)
        private val HREF_REGEX = Regex("href=[\\\"'](https?://[^\\\"'<> ]+)[\\\"']", RegexOption.IGNORE_CASE)
        private val BARE_DOMAIN_REGEX = Regex("(?<![A-Za-z0-9.-])((?:18comic|jmcomic|jm-comic)(?:-[A-Za-z0-9-]+)?(?:\\.[A-Za-z0-9-]+)+)(?![A-Za-z0-9.-])", RegexOption.IGNORE_CASE)
        private val DETAIL_BASE64_REGEX = Regex("(?:const|let|var)\\s+html\\s*=\\s*base64DecodeUtf8\\([\\\"']([^\\\"']+)[\\\"']\\)")
        private const val DEFAULT_SCRAMBLE_ID = 220980
        private const val WEB_DOMAIN_FEED = "https://stevenyomi.github.io/source-domains/jmcomic.txt"
        private const val API_BOOTSTRAP_COOKIE = "ipcountry=TW; theme=light"
        private const val MAX_WEB_DOMAINS = 16
        private const val MAX_WEB_PAGE_REQUESTS = 40
        private const val API_PAGE_HINT = 20
        private const val COMMENT_PAGE_HINT = 20
        private const val DETAIL_CACHE_MS = 30_000L
        private const val API_DOMAIN_TTL = 6 * 60 * 60 * 1000L
        private const val WEB_DOMAIN_TTL = 3 * 60 * 60 * 1000L
        private const val PREF_ROUTE = "jm_route_mode"
        private const val PREF_LAST_ROUTE = "jm_last_route"
        private const val PREF_SHUNT = "jm_shunt"
        private const val PREF_MANUAL_WEB = "jm_manual_web"
        private const val PREF_WEB_BASE = "jm_web_base"
        private const val PREF_WEB_DOMAINS = "jm_web_domains"
        private const val PREF_WEB_DOMAINS_AT = "jm_web_domains_at"
        private const val PREF_API_DOMAINS = "jm_api_domains"
        private const val PREF_API_DOMAINS_AT = "jm_api_domains_at"
        private const val PREF_API_HOST = "jm_api_host"
        private const val PREF_IMAGE_HOST = "jm_img_host"
        private const val PREF_APP_VERSION = "jm_app_version"
        private const val PREF_USERNAME = "jm_username"
        private const val PREF_PASSWORD = "jm_password"
        private const val PREF_AVS = "jm_avs"
        private const val PREF_PROFILE = "jm_profile"
        private const val PREF_API_USER = "jm_api_user"
        private const val PREF_WEB_USER = "jm_web_user"
        private const val PREF_LOGIN_VERSION = "jm_login_version"
    }
}

private fun JsonElement.asObjectLike(): JsonObject = this as? JsonObject ?: throw IOException("接口返回对象格式异常")
private fun JsonElement.asObjectLikeOrNull(): JsonObject? = this as? JsonObject
private fun JsonElement.asArrayLike(vararg keys: String): JsonArray = when (this) {
    is JsonArray -> this
    is JsonObject -> keys.firstNotNullOfOrNull { this[it] as? JsonArray } ?: JsonArray(emptyList())
    else -> JsonArray(emptyList())
}
private fun JsonObject.element(vararg keys: String): JsonElement? = keys.firstNotNullOfOrNull { this[it] }
private fun JsonObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }
private fun JsonObject.long(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
    val p = this[key] as? JsonPrimitive
    p?.longOrNull ?: p?.contentOrNull?.toLongOrNull()
}
private fun JsonObject.obj(vararg keys: String): JsonObject? = keys.firstNotNullOfOrNull { this[it] as? JsonObject }
private fun JsonObject.array(vararg keys: String): JsonArray? = keys.firstNotNullOfOrNull { this[it] as? JsonArray }
private fun JsonArray?.orEmptyObjects(): List<JsonObject> = this?.mapNotNull { it as? JsonObject }.orEmpty()
private fun JsonObject.stringList(vararg keys: String): List<String> {
    for (key in keys) {
        when (val e = this[key]) {
            is JsonArray -> return e.mapNotNull { item ->
                when (item) {
                    is JsonPrimitive -> item.contentOrNull
                    is JsonObject -> item.string("name", "title", "value")
                    else -> null
                }
            }.flatMap { it.split(Regex("[,/|]")) }.map(String::trim).filter(String::isNotBlank).distinct()
            is JsonPrimitive -> return e.contentOrNull.orEmpty().split(Regex("[,/|]")).map(String::trim).filter(String::isNotBlank).distinct()
            else -> Unit
        }
    }
    return emptyList()
}
private fun JsonObject.boolLike(key: String): Boolean? = when ((this[key] as? JsonPrimitive)?.contentOrNull?.lowercase(Locale.ROOT)) {
    "true", "1", "yes" -> true
    "false", "0", "no" -> false
    else -> null
}
