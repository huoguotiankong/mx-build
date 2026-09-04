package eu.kanade.tachiyomi.extension.zh.copymangaplus

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
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
import eu.kanade.tachiyomi.source.mx.CommentSortOption
import eu.kanade.tachiyomi.source.mx.CommentSource
import eu.kanade.tachiyomi.source.mx.CommentTarget
import eu.kanade.tachiyomi.source.mx.CommentTargetKind
import eu.kanade.tachiyomi.source.mx.MangaDetailAction
import eu.kanade.tachiyomi.source.mx.MangaDetailActionType
import eu.kanade.tachiyomi.source.mx.MangaDetailField
import eu.kanade.tachiyomi.source.mx.MangaDetailInfo
import eu.kanade.tachiyomi.source.mx.MangaDetailSource
import eu.kanade.tachiyomi.source.mx.MangaDetailValue
import eu.kanade.tachiyomi.source.mx.SortableCommentSource
import eu.kanade.tachiyomi.source.mx.SourceAccount
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Request
import openccjava.OpenCC
import java.io.IOException
import java.net.URLEncoder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Source
abstract class CopyMangaPlus :
    KeiSource(),
    ConfigurableSource,
    CommentSource,
    SortableCommentSource,
    AccountSource,
    MangaDetailSource {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val preferences = getPreferences()
    private val random = SecureRandom()
    private val authorWords = ConcurrentHashMap<String, String>()
    private val themeWords = ConcurrentHashMap<String, String>()

    override fun Headers.Builder.configureHeaders() = apply {
        set("User-Agent", COPY_UA)
        set("Accept", "application/json")
        set("Referer", "$baseUrl/")
    }

    override suspend fun getPopularManga(page: Int): MangasPage = listManga(page, ordering = "-popular")

    override suspend fun getLatestUpdates(page: Int): MangasPage = listManga(page, ordering = "-datetime_updated")

    override fun getFilterList(data: JsonElement?) = FilterList(
        SortFilter(),
        RegionFilter(),
        ThemeFilter(),
        RankFilter(),
    )

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val cleanQuery = query.trim()
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.value() ?: "-datetime_updated"
        val region = filters.filterIsInstance<RegionFilter>().firstOrNull()?.value().orEmpty()
        val theme = filters.filterIsInstance<ThemeFilter>().firstOrNull()?.value().orEmpty()
        val rank = filters.filterIsInstance<RankFilter>().firstOrNull()?.value().orEmpty()

        if (rank.isNotBlank()) {
            val offset = (page.coerceAtLeast(1) - 1) * PAGE_SIZE
            val root = requestJson(
                "/api/v3/ranks?limit=$PAGE_SIZE&offset=$offset&type=1&date_type=${enc(rank)}&_update=true",
            )
            return mangaPage(root.results(), page, PAGE_SIZE)
        }

        if (cleanQuery.startsWith(AUTHOR_PREFIX)) {
            val name = cleanQuery.removePrefix(AUTHOR_PREFIX).trim()
            val authorWord = authorWords[name]
            if (!authorWord.isNullOrBlank()) {
                return listManga(page, sort, theme, region, authorWord)
            }
        }
        if (cleanQuery.startsWith(TAG_PREFIX)) {
            val name = cleanQuery.removePrefix(TAG_PREFIX).trim()
            val tagWord = themeWords[name]
            if (!tagWord.isNullOrBlank()) {
                return listManga(page, sort, tagWord, region)
            }
        }

        if (cleanQuery.isBlank()) {
            return listManga(page, sort, theme, region)
        }

        val offset = (page.coerceAtLeast(1) - 1) * PAGE_SIZE
        val root = requestJson(
            "/api/v3/search/comic?platform=3&q=${enc(cleanQuery)}&limit=$PAGE_SIZE&offset=$offset&_update=true",
        )
        return mangaPage(root.results(), page, PAGE_SIZE)
    }

    private fun listManga(
        page: Int,
        ordering: String,
        theme: String = "",
        region: String = "",
        author: String = "",
    ): MangasPage {
        val p = page.coerceAtLeast(1)
        val offset = (p - 1) * PAGE_SIZE
        val query = buildList {
            add("limit=$PAGE_SIZE")
            add("offset=$offset")
            add("ordering=${enc(ordering)}")
            add("_update=true")
            if (theme.isNotBlank()) add("theme=${enc(theme)}")
            if (region.isNotBlank()) add("region=${enc(region)}")
            if (author.isNotBlank()) add("author=${enc(author)}")
        }.joinToString("&")
        return mangaPage(requestJson("/api/v3/comics?$query").results(), p, PAGE_SIZE)
    }

    private fun mangaPage(results: JsonObject?, page: Int, limit: Int): MangasPage {
        val list = results?.array("list") ?: JsonArray(emptyList())
        val mangas = list.mapNotNull { it as? JsonObject }.mapNotNull(::mangaFromJson).distinctBy { it.url }
        val total = results?.long("total")
        val offset = results?.int("offset") ?: ((page - 1) * limit)
        val hasNext = total?.let { offset + list.size < it } ?: (list.size >= limit)
        return MangasPage(mangas, hasNext)
    }

    private fun mangaFromJson(raw: JsonObject): SManga? {
        val o = raw.obj("comic") ?: raw
        val pathWord = o.string("path_word") ?: return null
        val authors = objectNames(o.array("author"), authorWords)
        val themes = objectNames(o.array("theme"), themeWords)
        return SManga.create().apply {
            url = "/comic/$pathWord"
            title = simplify(o.string("name")).orEmpty()
            author = authors.joinToString(", ").takeIf(String::isNotBlank)
            thumbnail_url = o.string("cover")
            genre = themes.distinct().joinToString(", ")
            status = statusOf(o)
            description = simplify(o.string("brief"))
            initialized = false
        }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/comic/${pathWord(manga)}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = COMIC_PATH.find(url.encodedPath)?.groupValues?.getOrNull(1) ?: return null
        return loadDetail(path).first
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)
        val (freshManga, freshChapters) = loadDetail(pathWord(manga))
        return SMangaUpdate(
            if (fetchDetails) freshManga else manga,
            if (fetchChapters) freshChapters else chapters,
        )
    }

    private fun loadDetail(pathWord: String): Pair<SManga, List<SChapter>> {
        val root = requestDetailJson(pathWord)
        val results = root.results() ?: throw IOException("拷贝漫画详情为空")
        val comic = results.obj("comic") ?: throw IOException("拷贝漫画作品信息为空")
        val authors = objectNames(comic.array("author"), authorWords)
        val themes = objectNames(comic.array("theme"), themeWords)

        val manga = SManga.create().apply {
            url = "/comic/$pathWord"
            title = simplify(comic.string("name")).orEmpty()
            author = authors.joinToString(", ").takeIf(String::isNotBlank)
            thumbnail_url = comic.string("cover")
            genre = themes.distinct().joinToString(", ")
            status = statusOf(comic)
            description = buildString {
                simplify(comic.string("brief"))?.takeIf(String::isNotBlank)?.let(::append)
                simplify(comic.string("alias"))?.takeIf(String::isNotBlank)?.let {
                    if (isNotEmpty()) append("\n\n")
                    append("别名：").append(it)
                }
            }
            initialized = true
        }

        val chapters = mutableListOf<SChapter>()
        val groups = results.obj("groups")
        val groupEntries = groups?.entries.orEmpty()
        if (groupEntries.isEmpty()) {
            chapters += loadGroupChapters(pathWord, "default", "")
        } else {
            groupEntries.forEach { (key, value) ->
                val group = value as? JsonObject
                val groupWord = group?.string("path_word")?.ifBlank { key } ?: key
                val groupName = simplify(group?.string("name")).orEmpty()
                chapters += loadGroupChapters(pathWord, groupWord, groupName)
            }
        }
        return manga to chapters.distinctBy { it.url }.sortedWith(
            compareByDescending<SChapter> { it.date_upload }.thenByDescending { it.chapter_number },
        )
    }

    private fun loadGroupChapters(pathWord: String, groupWord: String, groupName: String): List<SChapter> {
        val result = mutableListOf<SChapter>()
        var offset = 0
        var loops = 0
        while (loops++ < 30) {
            val root = requestJson(
                "/api/v3/comic/${enc(pathWord)}/group/${enc(groupWord)}/chapters?limit=$CHAPTER_PAGE&offset=$offset&platform=3",
            )
            val box = root.results() ?: break
            val list = box.array("list") ?: break
            if (list.isEmpty()) break
            list.mapNotNull { it as? JsonObject }.forEach { o ->
                val id = o.string("uuid") ?: return@forEach
                val rawName = simplify(o.string("name")).orEmpty().ifBlank { "章节" }
                result += SChapter.create().apply {
                    url = "/comic/$pathWord/chapter/$id"
                    name = if (groupName.isBlank() || groupName == "默认") rawName else "[$groupName] $rawName"
                    chapter_number = chapterNumber(rawName)
                    date_upload = parseDate(o.string("datetime_updated") ?: o.string("datetime_created"))
                }
            }
            offset += list.size
            val total = box.int("total") ?: offset
            if (offset >= total || list.size < CHAPTER_PAGE) break
        }
        return result.reversed()
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val match = CHAPTER_PATH.find(chapter.url) ?: throw IOException("章节地址无效")
        val comic = match.groupValues[1]
        val chapterId = match.groupValues[2]
        val result = runCatching {
            requestJson("/api/v3/comic/${enc(comic)}/chapter/${enc(chapterId)}?platform=3&_update=true")
        }.getOrElse {
            requestJson("/api/v3/comic/${enc(comic)}/chapter2/${enc(chapterId)}?platform=3&_update=true")
        }.results()?.obj("chapter") ?: throw IOException("章节正文为空")

        val contents = result.array("contents")?.mapNotNull { (it as? JsonObject)?.string("url") }.orEmpty()
        if (contents.isEmpty()) throw IOException("本章没有返回图片")
        val words = result.array("words")?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
        val ordered = if (words.size == contents.size && words.isNotEmpty()) {
            MutableList<String?>(contents.size) { null }.apply {
                words.forEachIndexed { index, position ->
                    if (position in indices) this[position] = contents[index]
                }
            }.mapIndexed { index, value -> value ?: contents[index] }
        } else {
            contents
        }
        val quality = preferences.getString(PREF_QUALITY, "1500") ?: "1500"
        return ordered.distinct().mapIndexed { index, image ->
            Page(index, getChapterUrl(chapter), applyQuality(image, quality))
        }
    }

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .header("User-Agent", COPY_UA)
        .header("Referer", "$baseUrl/")
        .get()
        .build()

    override suspend fun getMangaDetailInfo(manga: SManga): MangaDetailInfo {
        val path = pathWord(manga)
        val results = requestDetailJson(path).results()
            ?: throw IOException("拷贝漫画详情信息为空")
        val comic = results.obj("comic") ?: throw IOException("拷贝漫画作品信息为空")
        val authors = objectNames(comic.array("author"), authorWords)
        val themes = objectNames(comic.array("theme"), themeWords)
        val fields = buildList {
            if (authors.isNotEmpty()) {
                add(
                    MangaDetailField(
                        "作者",
                        authors.distinct().map { name ->
                            MangaDetailValue(
                                name,
                                MangaDetailAction(MangaDetailActionType.SOURCE_SEARCH, "$AUTHOR_PREFIX$name"),
                            )
                        },
                    ),
                )
            }
            if (themes.isNotEmpty()) {
                add(
                    MangaDetailField(
                        "标签",
                        themes.distinct().map { tag ->
                            MangaDetailValue(
                                tag,
                                MangaDetailAction(MangaDetailActionType.SOURCE_SEARCH, "$TAG_PREFIX$tag"),
                            )
                        },
                    ),
                )
            }
            simplify(comic.string("alias"))?.takeIf(String::isNotBlank)?.let {
                add(MangaDetailField("别名", listOf(MangaDetailValue(it))))
            }
            displayObject(comic.obj("region"))?.let {
                add(MangaDetailField("地区", listOf(MangaDetailValue(it))))
            }
            displayObject(comic.obj("status"))?.let {
                add(MangaDetailField("状态", listOf(MangaDetailValue(it))))
            }
            displayObject(comic.obj("free_type"))?.let {
                add(MangaDetailField("类型", listOf(MangaDetailValue(it))))
            }
            comic.string("datetime_updated")?.takeIf(String::isNotBlank)?.let {
                add(MangaDetailField("更新时间", listOf(MangaDetailValue(it.take(19)))))
            }
            val popular = comic.long("popular") ?: results.long("popular")
            popular?.let {
                add(MangaDetailField("人气", listOf(MangaDetailValue(it.toString()))))
            }
            simplify(comic.obj("last_chapter")?.string("name"))?.takeIf(String::isNotBlank)?.let {
                add(MangaDetailField("最新章节", listOf(MangaDetailValue(it))))
            }
        }
        return MangaDetailInfo(fields, replaceDefaultFields = true)
    }

    override val commentCapabilities = CommentCapabilities(
        supportsMangaComments = true,
        supportsChapterComments = true,
        canPost = true,
        canReply = true,
        canLike = false,
        requiresLoginToPost = true,
    )

    override val commentSortOptions = listOf(
        CommentSortOption(SORT_DEFAULT, "默认"),
        CommentSortOption(SORT_HOT, "最热"),
        CommentSortOption(SORT_LATEST, "最新"),
    )

    override val defaultCommentSortId = SORT_DEFAULT

    override suspend fun getMangaCommentTarget(manga: SManga): CommentTarget {
        val path = pathWord(manga)
        val comic = requestDetailJson(path).results()?.obj("comic")
        val id = comic?.string("uuid") ?: path
        return CommentTarget(id, getMangaUrl(manga), CommentTargetKind.MANGA)
    }

    override suspend fun getChapterCommentTarget(manga: SManga, chapter: SChapter): CommentTarget {
        val id = CHAPTER_PATH.find(chapter.url)?.groupValues?.getOrNull(2)
            ?: throw IOException("章节评论目标无效")
        return CommentTarget(id, getChapterUrl(chapter), CommentTargetKind.CHAPTER)
    }

    override suspend fun getComments(target: CommentTarget, page: Int): CommentPage = getComments(target, page, SORT_DEFAULT)

    override suspend fun getComments(target: CommentTarget, page: Int, sortId: String): CommentPage {
        val p = page.coerceAtLeast(1)
        val offset = (p - 1) * COMMENT_PAGE
        val path = if (target.kind == CommentTargetKind.CHAPTER) {
            "/api/v3/roasts?chapter_id=${enc(target.id)}&limit=$COMMENT_PAGE&offset=$offset&_update=true"
        } else {
            "/api/v3/comments?comic_id=${enc(target.id)}&limit=$COMMENT_PAGE&offset=$offset&_update=true"
        }
        val root = if (target.kind == CommentTargetKind.CHAPTER) {
            requestChapterCommentJson(path)
        } else {
            requestCommentJson(path)
        }
        val box = root.results() ?: return CommentPage(emptyList(), false, 0)
        val list = box.array("list")?.mapNotNull { it as? JsonObject }.orEmpty()
        var comments = if (target.kind == CommentTargetKind.CHAPTER) {
            list.mapIndexedNotNull { index, item -> roastFromJson(item, offset + index) }.distinctBy(Comment::id)
        } else {
            list.mapNotNull { commentFromJson(it, null) }.distinctBy(Comment::id)
        }
        comments = when (sortId) {
            SORT_HOT -> comments.sortedWith(compareByDescending<Comment> { it.replyCount }.thenByDescending { it.likeCount })
            SORT_LATEST -> comments.sortedByDescending { it.createdAt }
            else -> comments
        }
        val total = box.long("total") ?: comments.size.toLong()
        return CommentPage(comments, offset + list.size < total, total)
    }

    override suspend fun getCommentReplies(
        target: CommentTarget,
        comment: Comment,
        page: Int,
    ): CommentPage {
        if (target.kind == CommentTargetKind.CHAPTER) return CommentPage(emptyList(), false, 0)
        val p = page.coerceAtLeast(1)
        val offset = (p - 1) * COMMENT_PAGE
        val root = requestCommentJson(
            "/api/v3/comments?comic_id=${enc(target.id)}&reply_id=${enc(comment.id)}&limit=$COMMENT_PAGE&offset=$offset&_update=true",
        )
        val box = root.results() ?: return CommentPage(emptyList(), false, 0)
        val list = box.array("list")?.mapNotNull { it as? JsonObject }.orEmpty()
        val comments = list.mapNotNull { commentFromJson(it, comment.id) }
        val total = box.long("total") ?: comments.size.toLong()
        return CommentPage(comments, offset + list.size < total, total)
    }

    override suspend fun postComment(target: CommentTarget, content: String): Comment = postCommentInternal(target, null, content)

    override suspend fun postCommentReply(
        target: CommentTarget,
        parent: Comment,
        content: String,
    ): Comment = postCommentInternal(target, parent, content)

    private suspend fun postCommentInternal(target: CommentTarget, parent: Comment?, content: String): Comment {
        val text = content.trim()
        if (text.isBlank()) throw IOException("评论内容不能为空")
        requireLogin()

        if (target.kind == CommentTargetKind.CHAPTER) {
            if (parent != null) throw IOException("章评暂不支持回复")
            val body = FormBody.Builder()
                .add("chapter_id", target.id)
                .add("roast", text)
                .add("_update", "true")
                .build()
            requestChapterCommentJson("/api/v3/member/roast", method = "POST", body = body)
            val recent = runCatching { getComments(target, 1).comments }
                .getOrNull()?.firstOrNull { it.content == text }
            return recent ?: Comment(
                id = "pending-${System.currentTimeMillis()}",
                author = currentAuthor(),
                content = text,
                createdAt = System.currentTimeMillis(),
            )
        }

        val body = FormBody.Builder()
            .add("comic_id", target.id)
            .add("comment", text)
            .add("reply_id", parent?.id.orEmpty())
            .build()
        val root = requestCommentJson("/api/v3/member/comment", method = "POST", body = body)
        val candidate = root.results()?.obj("comment") ?: root.results()
        commentFromJson(candidate ?: JsonObject(emptyMap()), parent?.id)?.let { return it }
        val recent = runCatching {
            if (parent == null) getComments(target, 1).comments else getCommentReplies(target, parent, 1).comments
        }.getOrNull()?.firstOrNull { it.content == text }
        return recent ?: Comment(
            id = "pending-${System.currentTimeMillis()}",
            author = currentAuthor(),
            content = text,
            createdAt = System.currentTimeMillis(),
            parentId = parent?.id,
        )
    }

    private fun commentFromJson(o: JsonObject, parentId: String?): Comment? {
        val id = o.string("id") ?: o.string("uuid") ?: return null
        val user = o.obj("user")
        val authorName = simplify(o.string("user_name") ?: user?.string("nickname") ?: user?.string("username") ?: "拷贝用户").orEmpty()
        val avatar = o.string("user_avatar") ?: user?.string("avatar")
        return Comment(
            id = id,
            author = CommentAuthor(user?.string("user_id") ?: o.string("user_id"), authorName, avatar),
            content = simplify(o.string("comment") ?: o.string("content")).orEmpty(),
            createdAt = parseDate(o.string("create_at") ?: o.string("datetime_created") ?: o.string("created_at")),
            displayTime = o.string("create_at") ?: o.string("datetime_created"),
            likeCount = o.long("likes") ?: o.long("like_count") ?: 0,
            replyCount = o.long("count") ?: o.long("reply_count") ?: 0,
            parentId = parentId,
        )
    }

    private fun roastFromJson(o: JsonObject, fallbackIndex: Int): Comment? {
        val content = simplify(o.string("comment") ?: o.string("roast")) ?: return null
        val user = o.obj("user")
        val userId = user?.string("user_id") ?: o.string("user_id")
        val authorName = simplify(o.string("user_name") ?: user?.string("nickname") ?: user?.string("username") ?: "拷贝用户").orEmpty()
        val avatar = o.string("user_avatar") ?: user?.string("avatar")
        val rawTime = o.string("create_at") ?: o.string("datetime_created") ?: o.string("created_at")
        val id = o.string("id") ?: o.string("uuid")
            ?: "roast-${userId.orEmpty()}-${rawTime.orEmpty()}-${content.hashCode()}-$fallbackIndex"
        return Comment(
            id = id,
            author = CommentAuthor(userId, authorName, avatar),
            content = content,
            createdAt = parseDate(rawTime),
            displayTime = rawTime,
            likeCount = o.long("likes") ?: o.long("like_count") ?: 0,
            replyCount = 0,
        )
    }

    override suspend fun getSourceAccount(): SourceAccount? {
        if (token().isBlank()) return null
        val results = runCatching { requestCommentJson("/api/v3/member/info").results() }.getOrNull() ?: return null
        val name = simplify(results.string("nickname") ?: results.string("username")) ?: return null
        return SourceAccount(
            id = results.string("user_id"),
            name = name,
            avatarUrl = results.string("avatar"),
        )
    }

    private suspend fun currentAuthor(): CommentAuthor {
        val account = runCatching { getSourceAccount() }.getOrNull()
        return CommentAuthor(account?.id, account?.name ?: "我", account?.avatarUrl)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_ROUTE
            title = "线路"
            entries = arrayOf(
                "自动（拷贝优先，失败切热辣）",
                "拷贝漫画·动态线路",
                "拷贝漫画·api.copy2000.online",
                "拷贝漫画·api.copy-manga.com",
                "拷贝漫画·api.2026copy.com",
                "拷贝漫画·api.copy4000.com",
                "拷贝漫画·api.mangacopy.com",
                "拷贝漫画·api.copy3000.com",
                "拷贝漫画·mapi.copy20.com",
                "热辣漫画·动态线路",
                "热辣漫画·api.2024manga.com",
                "热辣漫画·新加坡",
                "热辣漫画·线路 D",
                "热辣漫画·线路 F",
            )
            entryValues = arrayOf(
                ROUTE_AUTO,
                ROUTE_COPY_AUTO,
                "copy:api.copy2000.online",
                "copy:api.copy-manga.com",
                "copy:api.2026copy.com",
                "copy:api.copy4000.com",
                "copy:api.mangacopy.com",
                "copy:api.copy3000.com",
                "copy:mapi.copy20.com",
                ROUTE_HOT_AUTO,
                "hot:api.2024manga.com",
                "hot:mapi.hotmangasg.com",
                "hot:mapi.hotmangasd.com",
                "hot:mapi.hotmangasf.com",
            )
            setDefaultValue(ROUTE_AUTO)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY
            title = "图片清晰度"
            entries = arrayOf("800（省流）", "1200", "1500（推荐）", "保持源站原始地址")
            entryValues = arrayOf("800", "1200", "1500", "original")
            setDefaultValue("1500")
            summary = "%s"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_TRAD_TO_SIMP
            title = "繁体转简体"
            summary = "将标题、作者、标签、简介、章节名和评论文本转换为简体中文"
            setDefaultValue(true)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_USERNAME
            title = "拷贝/热辣账号"
            summary = "用户名 / 邮箱"
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_PASSWORD
            title = "密码"
            summary = "仅保存在本机，用于登录和 Token 失效后重登"
            setOnBindEditTextListener { it.inputType = 0x00000081 }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "copy_login_action"
            title = "登录 / 刷新登录"
            summary = "按当前线路登录；自动线路会在拷贝 / 热辣节点间尝试"
            setOnPreferenceClickListener {
                client.dispatcher.executorService.execute {
                    val result = runCatching { signIn() }
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            screen.context,
                            result.fold({ "登录成功" }, { "登录失败：${it.message}" }),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "copy_refresh_route_action"
            title = "刷新动态线路"
            summary = "清除已缓存节点，下次请求重新获取当前线路"
            setOnPreferenceClickListener {
                preferences.edit()
                    .remove(PREF_COPY_DISCOVERED)
                    .remove(PREF_HOT_DISCOVERED)
                    .remove(PREF_LAST_HOST)
                    .apply()
                Toast.makeText(screen.context, "已清除线路缓存", Toast.LENGTH_SHORT).show()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "copy_logout_action"
            title = "退出登录"
            setOnPreferenceClickListener {
                preferences.edit().remove(PREF_TOKEN).apply()
                Toast.makeText(screen.context, "已退出账号", Toast.LENGTH_SHORT).show()
                true
            }
        }.also(screen::addPreference)
    }

    private fun signIn() {
        val username = preferences.getString(PREF_USERNAME, "").orEmpty().trim()
        val password = preferences.getString(PREF_PASSWORD, "").orEmpty()
        if (username.isBlank() || password.isBlank()) throw IOException("请输入账号和密码")
        val salt = (1000 + random.nextInt(9000)).toString()
        val encoded = Base64.encodeToString("$password-$salt".toByteArray(), Base64.NO_WRAP)
        var last: Throwable? = null
        for (route in apiCandidates(includeLast = true)) {
            try {
                val builder = FormBody.Builder()
                    .add("username", username)
                    .add("password", encoded)
                    .add("salt", salt)
                if (route.kind == RouteKind.HOT) {
                    builder.add("source", "Official").add("version", "2.2.0").add("platform", "3")
                } else {
                    builder.add("source", "copyApp").add("version", COPY_VERSION).add("platform", "3")
                }
                val request = Request.Builder()
                    .url("https://${route.host}/api/v3/login")
                    .headers(apiHeaders(route.kind, includeToken = false))
                    .post(builder.build())
                    .build()
                client.newCall(request).execute().use { response ->
                    val root = parseResponse(response.code, response.body.string())
                    val newToken = root.results()?.string("token") ?: throw IOException("登录响应没有 Token")
                    preferences.edit().putString(PREF_TOKEN, newToken).putString(PREF_LAST_HOST, route.serialized).apply()
                    return
                }
            } catch (e: Throwable) {
                last = e
            }
        }
        throw IOException(last?.message ?: "所有登录线路均失败", last)
    }

    private fun requireLogin() {
        if (token().isNotBlank()) return
        if (!hasCredentials()) throw IOException("请先在漫画源设置中填写拷贝/热辣账号并登录")
        signIn()
    }

    private fun hasCredentials(): Boolean = preferences.getString(PREF_USERNAME, "").orEmpty().isNotBlank() &&
        preferences.getString(PREF_PASSWORD, "").orEmpty().isNotBlank()

    private fun token() = preferences.getString(PREF_TOKEN, "").orEmpty().trim()

    private fun stableCopyValue(key: String, create: () -> String): String {
        preferences.getString(key, null)?.takeIf(String::isNotBlank)?.let { return it }
        return create().also { preferences.edit().putString(key, it).apply() }
    }

    private fun copyDeviceInfo(): String = stableCopyValue(PREF_COPY_DEVICE_INFO) {
        "${1_000_000 + random.nextInt(9_000_000)}V-${1_000 + random.nextInt(9_000)}"
    }

    private fun copyDevice(): String = stableCopyValue(PREF_COPY_DEVICE) {
        fun upper() = ('A'.code + random.nextInt(26)).toChar()
        fun digit() = ('0'.code + random.nextInt(10)).toChar()
        buildString {
            append(upper())
            append(upper())
            append(digit())
            append(upper())
            append('.')
            repeat(6) { append(digit()) }
            append('.')
            repeat(3) { append(digit()) }
        }
    }

    private fun copyPseudoId(): String = stableCopyValue(PREF_COPY_PSEUDO_ID) {
        buildString {
            repeat(16) { append(COPY_PSEUDO_CHARS[random.nextInt(COPY_PSEUDO_CHARS.length)]) }
        }
    }

    private fun copySignature(timestamp: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(COPY_HMAC_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(timestamp.toByteArray(Charsets.UTF_8)).joinToString("") {
            "%02x".format(Locale.US, it.toInt() and 0xff)
        }
    }

    private fun requestJson(
        path: String,
        method: String = "GET",
        body: FormBody? = null,
        includeToken: Boolean = true,
    ): JsonObject = requestWithCandidates(path, method, body, includeToken, commentOnly = false)

    private fun requestDetailJson(pathWord: String): JsonObject {
        var last: Throwable? = null
        apiCandidates(includeLast = true).distinctBy { it.serialized }.forEach { route ->
            try {
                val query = if (route.kind == RouteKind.COPY) {
                    "in_mainland=true&request_id=&platform=3"
                } else {
                    "in_mainland=true&platform=3"
                }
                val request = Request.Builder()
                    .url("https://${route.host}/api/v3/comic2/${enc(pathWord)}?$query")
                    .headers(
                        if (route.kind == RouteKind.COPY) {
                            copyDetailHeaders(includeToken = true)
                        } else {
                            apiHeaders(RouteKind.HOT, includeToken = true)
                        },
                    )
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val root = parseResponse(response.code, response.body.string())
                    if (root.results()?.obj("comic") == null) {
                        throw IOException("当前节点没有返回漫画详情")
                    }
                    preferences.edit().putString(PREF_LAST_HOST, route.serialized).apply()
                    return root
                }
            } catch (e: Throwable) {
                last = e
            }
        }
        throw IOException(last?.message ?: "所有拷贝/热辣详情线路均不可用", last)
    }

    private fun copyDetailHeaders(includeToken: Boolean): Headers {
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        return Headers.Builder()
            .set("User-Agent", "COPY/$COPY_DETAIL_VERSION")
            .set("source", "copyApp")
            .set("deviceinfo", copyDeviceInfo())
            .set("dt", SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date()))
            .set("platform", "3")
            .set("referer", "com.copymanga.app-$COPY_DETAIL_VERSION")
            .set("version", COPY_DETAIL_VERSION)
            .set("device", copyDevice())
            .set("pseudoid", copyPseudoId())
            .set("Accept", "application/json")
            .set("region", "0")
            .set(
                "Authorization",
                if (includeToken && token().isNotBlank()) "Token ${token()}" else "Token",
            )
            .set("umstring", COPY_UMSTRING)
            .set("x-auth-timestamp", timestamp)
            .set("x-auth-signature", copySignature(timestamp))
            .build()
    }

    private fun requestCommentJson(
        path: String,
        method: String = "GET",
        body: FormBody? = null,
    ): JsonObject {
        if (method == "POST") requireLogin()
        return try {
            requestWithCandidates(path, method, body, includeToken = true, commentOnly = true)
        } catch (first: Throwable) {
            if (token().isNotBlank() && hasCredentials()) {
                preferences.edit().remove(PREF_TOKEN).apply()
                runCatching { signIn() }
                requestWithCandidates(path, method, body, includeToken = true, commentOnly = true)
            } else {
                throw first
            }
        }
    }

    private fun requestChapterCommentJson(
        path: String,
        method: String = "GET",
        body: FormBody? = null,
    ): JsonObject {
        if (method == "POST") requireLogin()
        val normalized = if (path.startsWith('/')) path else "/$path"
        var last: Throwable? = null
        COPY_HOSTS.distinct().forEach { host ->
            val route = ApiRoute(RouteKind.COPY, host)
            try {
                val requestBuilder = Request.Builder()
                    .url("https://$host$normalized")
                    .headers(apiHeaders(RouteKind.COPY, includeToken = true))
                when (method.uppercase(Locale.US)) {
                    "POST" -> requestBuilder.post(body ?: FormBody.Builder().build())
                    else -> requestBuilder.get()
                }
                client.newCall(requestBuilder.build()).execute().use { response ->
                    val root = parseResponse(response.code, response.body.string())
                    if (method != "POST" && root.results()?.array("list") == null) {
                        throw IOException("当前节点没有返回章评列表")
                    }
                    preferences.edit().putString(PREF_LAST_HOST, route.serialized).apply()
                    return root
                }
            } catch (e: Throwable) {
                last = e
            }
        }
        throw IOException(last?.message ?: "所有拷贝章评线路均不可用", last)
    }

    private fun requestWithCandidates(
        path: String,
        method: String,
        body: FormBody?,
        includeToken: Boolean,
        commentOnly: Boolean,
    ): JsonObject {
        val normalized = if (path.startsWith('/')) path else "/$path"
        val routes = if (commentOnly) commentCandidates() else apiCandidates(includeLast = true)
        var last: Throwable? = null
        routes.distinctBy { it.serialized }.forEach { route ->
            try {
                val requestBuilder = Request.Builder()
                    .url("https://${route.host}$normalized")
                    .headers(apiHeaders(route.kind, includeToken))
                when (method.uppercase(Locale.US)) {
                    "POST" -> requestBuilder.post(body ?: FormBody.Builder().build())
                    else -> requestBuilder.get()
                }
                client.newCall(requestBuilder.build()).execute().use { response ->
                    val root = parseResponse(response.code, response.body.string())
                    if (
                        method.equals("GET", ignoreCase = true) &&
                        normalized.startsWith("/api/v3/comic2/") &&
                        !normalized.contains("/query") &&
                        root.results()?.obj("comic") == null
                    ) {
                        throw IOException("当前节点没有返回漫画详情")
                    }
                    preferences.edit().putString(PREF_LAST_HOST, route.serialized).apply()
                    return root
                }
            } catch (e: Throwable) {
                last = e
            }
        }
        throw IOException(last?.message ?: "所有拷贝/热辣线路均不可用", last)
    }

    private fun parseResponse(httpCode: Int, text: String): JsonObject {
        if (isRestrictionResponse(text)) throw IOException("当前拷贝节点拒绝该请求，继续尝试其它线路")
        if (httpCode == 401 || httpCode == 403) throw UnauthorizedException("登录已失效（HTTP $httpCode）")
        if (httpCode !in 200..299) throw IOException("HTTP $httpCode")
        val root = runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw IOException("接口返回不是有效 JSON") }
        val message = root.string("message").orEmpty()
        if (isRestrictionResponse(message)) throw IOException("当前拷贝节点拒绝该请求，继续尝试其它线路")
        val code = root.int("code")
        if (code == 401 || code == 403) throw UnauthorizedException(message.ifBlank { "登录已失效" })
        if (code != null && code != 200) throw IOException(message.ifBlank { "接口错误 code=$code" })
        return root
    }

    private fun isRestrictionResponse(text: String): Boolean = listOf(
        "官網更新最新",
        "官网更新最新",
        "破解版本",
        "等待1小時",
        "等待1小时",
        "限制會自動解除",
        "限制会自动解除",
    ).any(text::contains)

    private fun migrateRouteState() {
        if (preferences.getInt(PREF_ROUTE_SCHEMA, 0) >= ROUTE_SCHEMA) return
        val selected = preferences.getString(PREF_ROUTE, ROUTE_AUTO).orEmpty()
        val selectedRoute = parseExplicit(selected)
        val editor = preferences.edit()
            .remove(PREF_COPY_DISCOVERED)
            .remove(PREF_LAST_HOST)
            .putInt(PREF_ROUTE_SCHEMA, ROUTE_SCHEMA)
        if (selectedRoute?.kind == RouteKind.COPY && selectedRoute.host !in COPY_HOSTS) {
            editor.putString(PREF_ROUTE, ROUTE_COPY_AUTO)
        }
        editor.apply()
    }

    private fun apiCandidates(includeLast: Boolean): List<ApiRoute> {
        migrateRouteState()
        val selected = preferences.getString(PREF_ROUTE, ROUTE_AUTO) ?: ROUTE_AUTO
        val explicit = parseExplicit(selected)
        if (explicit != null) return listOf(explicit)

        val result = mutableListOf<ApiRoute>()
        if (includeLast) parseExplicit(preferences.getString(PREF_LAST_HOST, null))?.let(result::add)
        when (selected) {
            ROUTE_COPY_AUTO -> result += copyCandidates()
            ROUTE_HOT_AUTO -> result += hotCandidates()
            else -> {
                result += copyCandidates()
                result += hotCandidates()
            }
        }
        return result.distinctBy { it.serialized }
    }

    private fun commentCandidates(): List<ApiRoute> {
        migrateRouteState()
        return (copyCandidates() + apiCandidates(includeLast = true) + hotCandidates()).distinctBy { it.serialized }
    }

    private fun copyCandidates(): List<ApiRoute> {
        val discovered = preferences.getString(PREF_COPY_DISCOVERED, "").orEmpty()
            .split(',').map(String::trim).filter(String::isNotBlank)
        val dynamic = if (discovered.isNotEmpty()) discovered else discover(RouteKind.COPY)
        return (dynamic + COPY_HOSTS).distinct().map { ApiRoute(RouteKind.COPY, it) }
    }

    private fun hotCandidates(): List<ApiRoute> {
        val discovered = preferences.getString(PREF_HOT_DISCOVERED, "").orEmpty()
            .split(',').map(String::trim).filter(String::isNotBlank)
        val dynamic = if (discovered.isNotEmpty()) discovered else discover(RouteKind.HOT)
        return (dynamic + HOT_HOSTS).distinct().map { ApiRoute(RouteKind.HOT, it) }
    }

    private fun discover(kind: RouteKind): List<String> {
        val bootstrap = if (kind == RouteKind.HOT) HOT_BOOTSTRAP else COPY_BOOTSTRAP
        return runCatching {
            val request = Request.Builder()
                .url("https://$bootstrap/api/v3/system/network2?platform=3")
                .headers(apiHeaders(kind, includeToken = false))
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val root = parseResponse(response.code, response.body.string())
                val results = root.results() ?: JsonObject(emptyMap())
                val hosts = buildList {
                    results.array("api")?.forEach { node ->
                        when (node) {
                            is JsonArray -> node.forEach { add((it as? JsonPrimitive)?.contentOrNull.orEmpty()) }
                            is JsonPrimitive -> add(node.contentOrNull.orEmpty())
                            else -> Unit
                        }
                    }
                    results.array("share")?.forEach { add((it as? JsonPrimitive)?.contentOrNull.orEmpty()) }
                }.map(::normalizeHost).filter(String::isNotBlank).distinct()
                preferences.edit().putString(
                    if (kind == RouteKind.HOT) PREF_HOT_DISCOVERED else PREF_COPY_DISCOVERED,
                    hosts.joinToString(","),
                ).apply()
                hosts
            }
        }.getOrDefault(emptyList())
    }

    private fun apiHeaders(kind: RouteKind, includeToken: Boolean): Headers {
        val builder = Headers.Builder()
        if (kind == RouteKind.HOT) {
            builder.set("User-Agent", "COPY/2.2.0")
            builder.set("Accept", "application/json")
            builder.set("webp", "1")
            builder.set("platform", "3")
            builder.set("version", "2024.04.28")
            builder.set("X-Requested-With", "com.manga2020.app")
            if (includeToken && token().isNotBlank()) builder.set("Authorization", "Token ${token()}")
        } else {
            builder.set("User-Agent", COPY_UA)
            builder.set("Accept", "application/json")
            builder.set("webp", "1")
            builder.set("region", "1")
            builder.set("platform", "3")
            builder.set("source", "copyApp")
            builder.set("version", COPY_VERSION)
            builder.set("referer", "com.copymanga.app-$COPY_REFERER_VERSION")
            if (includeToken && token().isNotBlank()) builder.set("Authorization", "Token ${token()}")
        }
        return builder.build()
    }

    private fun parseExplicit(value: String?): ApiRoute? {
        if (value.isNullOrBlank() || !value.contains(':')) return null
        val prefix = value.substringBefore(':')
        val host = normalizeHost(value.substringAfter(':'))
        if (host.isBlank()) return null
        return when (prefix) {
            "copy" -> ApiRoute(RouteKind.COPY, host)
            "hot" -> ApiRoute(RouteKind.HOT, host)
            else -> null
        }
    }

    private fun simplify(text: String?): String? {
        if (text.isNullOrBlank() || !preferences.getBoolean(PREF_TRAD_TO_SIMP, true)) return text
        return runCatching { OpenCC.convert(text, "t2s") }.getOrElse { text }
    }

    private fun normalizeHost(value: String) = value.trim().removePrefix("https://").removePrefix("http://").trim('/')

    private fun objectNames(array: JsonArray?, cache: ConcurrentHashMap<String, String>): List<String> {
        if (array == null) return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject
            if (obj == null) {
                return@mapNotNull simplify((element as? JsonPrimitive)?.contentOrNull)
            }
            val originalName = obj.string("name") ?: return@mapNotNull null
            val displayName = simplify(originalName).orEmpty()
            obj.string("path_word")?.takeIf(String::isNotBlank)?.let { pathWord ->
                cache[originalName] = pathWord
                cache[displayName] = pathWord
            }
            displayName
        }
    }

    private fun statusOf(o: JsonObject): Int {
        val text = displayObject(o.obj("status")).orEmpty()
        return when {
            text.contains("完结") || text.contains("完結") -> SManga.COMPLETED
            text.contains("连载") || text.contains("連載") -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    private fun displayObject(o: JsonObject?): String? = simplify(o?.string("display") ?: o?.string("name") ?: o?.string("value"))

    private fun pathWord(manga: SManga): String = COMIC_PATH.find(manga.url)?.groupValues?.getOrNull(1) ?: manga.url.substringAfterLast('/')

    private fun chapterNumber(name: String): Float {
        val match = NUMBER.find(name) ?: return -1f
        return match.groupValues[1].toFloatOrNull() ?: -1f
    }

    private fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        for (pattern in DATE_PATTERNS) {
            val value = runCatching { SimpleDateFormat(pattern, Locale.US).parse(raw)?.time }.getOrNull()
            if (value != null) return value
        }
        return 0L
    }

    private fun applyQuality(url: String, quality: String): String {
        if (quality == "original") return url
        return url
            .replace(Regex("\\.c\\d+x\\.webp(?:$|\\?)")) { match ->
                val suffix = if (match.value.endsWith("?")) "?" else ""
                ".c${quality}x.webp$suffix"
            }
            .replace(Regex("\\.h\\d+x\\."), ".h${quality}x.")
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun JsonObject.results(): JsonObject? = obj("results")
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private data class ApiRoute(val kind: RouteKind, val host: String) {
        val serialized: String get() = "${if (kind == RouteKind.COPY) "copy" else "hot"}:$host"
    }

    private enum class RouteKind { COPY, HOT }

    private class UnauthorizedException(message: String) : IOException(message)

    companion object {
        private const val PAGE_SIZE = 30
        private const val CHAPTER_PAGE = 500
        private const val COMMENT_PAGE = 20
        private const val COPY_VERSION = "3.0.9"
        private const val COPY_UA = "COPY/3.0.0"
        private const val COPY_REFERER_VERSION = "3.0.0"
        private const val COPY_DETAIL_VERSION = "3.0.6"
        private const val COPY_HMAC_KEY = "3af08590311032efe0660500a0563a53"
        private const val COPY_UMSTRING = "b4c89ca4104ea9a97750314d791520ac"
        private const val COPY_PSEUDO_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        private const val COPY_BOOTSTRAP = "api.2026copy.com"
        private const val HOT_BOOTSTRAP = "api.2024manga.com"
        private val COPY_HOSTS = listOf(
            "api.copy2000.online",
            "api.copy-manga.com",
            "api.2026copy.com",
            "api.copy4000.com",
            "api.mangacopy.com",
            "api.copy3000.com",
            "mapi.copy20.com",
        )
        private val HOT_HOSTS = listOf(
            "api.2024manga.com",
            "mapi.hotmangasg.com",
            "mapi.hotmangasd.com",
            "mapi.hotmangasf.com",
        )
        private const val PREF_ROUTE = "route"
        private const val PREF_QUALITY = "quality"
        private const val PREF_TRAD_TO_SIMP = "traditional_to_simplified"
        private const val PREF_USERNAME = "username"
        private const val PREF_PASSWORD = "password"
        private const val PREF_TOKEN = "token"
        private const val PREF_LAST_HOST = "last_host"
        private const val PREF_COPY_DISCOVERED = "copy_discovered"
        private const val PREF_HOT_DISCOVERED = "hot_discovered"
        private const val PREF_ROUTE_SCHEMA = "route_schema"
        private const val PREF_COPY_DEVICE_INFO = "copy_device_info"
        private const val PREF_COPY_DEVICE = "copy_device"
        private const val PREF_COPY_PSEUDO_ID = "copy_pseudo_id"
        private const val ROUTE_SCHEMA = 4
        private const val ROUTE_AUTO = "auto"
        private const val ROUTE_COPY_AUTO = "copy_auto"
        private const val ROUTE_HOT_AUTO = "hot_auto"
        private const val AUTHOR_PREFIX = "作者:"
        private const val TAG_PREFIX = "标签:"
        private const val SORT_DEFAULT = "default"
        private const val SORT_HOT = "hot"
        private const val SORT_LATEST = "latest"
        private val COMIC_PATH = Regex("/comic/([^/?#]+)")
        private val CHAPTER_PATH = Regex("/comic/([^/?#]+)/chapter/([^/?#]+)")
        private val NUMBER = Regex("(\\d+(?:\\.\\d+)?)")
        private val DATE_PATTERNS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
        )
    }
}
