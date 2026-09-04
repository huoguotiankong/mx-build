package eu.kanade.tachiyomi.extension.zh.ehentaiplus

import android.net.Uri
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.utils.asJsoup
import keiyoushi.utils.tryParseDateTime
import okhttp3.Response
import org.jsoup.nodes.Document
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

internal data class EHTag(val name: String, val light: Boolean = false, val weak: Boolean = false)

internal data class RelatedGallery(val title: String, val url: String)

internal data class GalleryMetadata(
    var url: String = "",
    var thumbnailUrl: String? = null,
    var title: String? = null,
    var altTitle: String? = null,
    var category: String? = null,
    var datePosted: Long? = null,
    var parent: RelatedGallery? = null,
    var newerVersions: List<RelatedGallery> = emptyList(),
    var visible: String? = null,
    var language: String? = null,
    var translated: Boolean = false,
    var size: Long? = null,
    var length: Int? = null,
    var favorites: Int? = null,
    var ratingCount: Int? = null,
    var averageRating: Double? = null,
    var uploader: String? = null,
    val tags: LinkedHashMap<String, List<EHTag>> = linkedMapOf(),
)

internal data class DetailBundle(val manga: SManga, val metadata: GalleryMetadata)

internal object EHParser {
    data class MangaListResult(val mangas: List<SManga>, val hasNextPage: Boolean, val lastGalleryId: String)

    fun parseMangaList(response: Response, enforceChinese: Boolean): MangaListResult {
        val doc = response.asJsoup()
        val roots = buildList {
            addAll(doc.select("table.itg > tbody > tr").filter { it.selectFirst("th") == null && it.selectFirst("a[href*=/g/]") != null })
            if (isEmpty()) addAll(doc.select("div.gl1t"))
        }

        var lastId = ""
        val mangas = roots.mapNotNull { root ->
            val link = root.selectFirst("a[href*=/g/]") ?: return@mapNotNull null
            val href = link.attr("abs:href").ifBlank { link.attr("href") }
            val normalized = normalizeGalleryUrl(href) ?: return@mapNotNull null

            val language = root.select("div[title^=language:]")
                .map { it.attr("title").substringAfter(':').trim() }
                .firstOrNull { it != "translated" }
            if (enforceChinese && language != null && !language.equals("chinese", true)) return@mapNotNull null

            val image = root.selectFirst("img")
            val title = root.selectFirst(".glink")?.text()?.trim()
                ?.takeIf(String::isNotBlank)
                ?: image?.attr("title")?.trim()?.takeIf(String::isNotBlank)
                ?: image?.attr("alt")?.trim()?.takeIf(String::isNotBlank)
                ?: link.text().trim().takeIf(String::isNotBlank)
                ?: return@mapNotNull null

            lastId = galleryId(normalized).orEmpty()
            SManga.create().apply {
                url = normalized
                this.title = title
                thumbnail_url = image?.let { img ->
                    img.attr("data-src").ifBlank { img.attr("src") }.takeIf(String::isNotBlank)
                }
                initialized = false
            }
        }.distinctBy { it.url }

        val hasNext = doc.selectFirst("a#unext[href],a#dnext[href]") != null
        return MangaListResult(mangas, hasNext, lastId)
    }

    fun parseDetails(response: Response): DetailBundle {
        val doc = response.asJsoup()
        val metadata = GalleryMetadata().apply {
            url = normalizeGalleryUrl(response.request.url.toString()) ?: response.request.url.encodedPath
            title = doc.selectFirst("#gn")?.text()?.trim()?.takeIf(String::isNotBlank)
            altTitle = doc.selectFirst("#gj")?.text()?.trim()?.takeIf(String::isNotBlank)
            thumbnailUrl = doc.selectFirst("#gd1 div")?.attr("style")?.let(::extractCssUrl)
            category = doc.selectFirst("#gdc div")?.text()?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank)
            uploader = doc.selectFirst("#gdn")?.text()?.trim()?.takeIf(String::isNotBlank)

            doc.select("#gdd tr").forEach { row ->
                val key = row.select(".gdt1").text().trim().removeSuffix(":").lowercase(Locale.ROOT)
                val value = row.select(".gdt2").text().trim()
                when (key) {
                    "posted" -> datePosted = parseEhDate(value)
                    "parent" -> row.selectFirst(".gdt2 a[href*=/g/]")?.let { a ->
                        parent = RelatedGallery(a.text().trim().ifBlank { "父版本" }, a.attr("abs:href").ifBlank { a.attr("href") })
                    }
                    "visible" -> visible = value.takeIf(String::isNotBlank)
                    "language" -> {
                        translated = value.endsWith("TR", true)
                        language = value.removeSuffix("TR").trim().takeIf(String::isNotBlank)
                    }
                    "file size" -> size = parseHumanReadableByteCount(value)?.toLong()
                    "length" -> length = value.substringBefore("page").trim().filter { it.isDigit() }.toIntOrNull()
                    "favorited" -> favorites = value.filter { it.isDigit() }.toIntOrNull()
                }
            }

            averageRating = doc.selectFirst("#rating_label")?.text()?.substringAfter("Average:")?.trim()?.toDoubleOrNull()
            ratingCount = doc.selectFirst("#rating_count")?.text()?.trim()?.toIntOrNull()

            doc.select("#taglist tr").forEach { row ->
                val namespace = row.selectFirst(".tc")?.text()?.removeSuffix(":")?.trim()?.lowercase(Locale.ROOT).orEmpty()
                if (namespace.isBlank()) return@forEach
                val values = row.select("div[id^=td_],div.gt,div.gtl,div.gtw").mapNotNull { element ->
                    val text = element.text().trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
                    EHTag(text, light = element.hasClass("gtl"), weak = element.hasClass("gtw"))
                }.distinctBy { it.name }
                if (values.isNotEmpty()) tags[namespace] = values
            }

            newerVersions = doc.select("#gnd a[href*=/g/]").mapNotNull { a ->
                val href = a.attr("abs:href").ifBlank { a.attr("href") }
                normalizeGalleryUrl(href)?.let { RelatedGallery(a.text().trim().ifBlank { "新版本" }, href) }
            }.distinctBy { it.url }
        }

        val manga = SManga.create().apply {
            url = metadata.url
            title = metadata.title ?: metadata.altTitle ?: "E-Hentai"
            thumbnail_url = metadata.thumbnailUrl
            artist = metadata.tags["artist"]?.joinToString(" / ") { it.name }?.takeIf(String::isNotBlank)
            author = metadata.tags["author"]?.joinToString(" / ") { it.name }?.takeIf(String::isNotBlank)
            genre = metadata.tags.flatMap { (namespace, tags) -> tags.map { "$namespace:${it.name}" } }.joinToString(", ").takeIf(String::isNotBlank)
            status = if (ONGOING_SUFFIX.any { suffix -> this.title.endsWith(suffix, ignoreCase = true) }) SManga.ONGOING else SManga.COMPLETED
            description = buildChineseDescription(metadata)
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            initialized = true
        }
        return DetailBundle(manga, metadata)
    }

    fun parseChapterPage(doc: Document): List<String> = doc.select("#gdt a[href*=/s/],.gdtm a[href*=/s/],.gdtl a[href*=/s/]")
        .map { it.attr("abs:href").ifBlank { it.attr("href") } }
        .filter(String::isNotBlank)
        .distinct()

    fun nextGalleryPage(doc: Document): String? = doc.select("a[onclick=return false]").lastOrNull()?.let {
        if (it.text().trim() == ">") it.attr("abs:href").ifBlank { it.attr("href") }.takeIf(String::isNotBlank) else null
    }

    data class ImageInfo(val imageUrl: String, val reloadViewerUrl: String? = null)

    fun parseImageInfo(response: Response, original: Boolean): ImageInfo {
        val doc = response.asJsoup()
        val defaultUrl = doc.selectFirst("#img")?.attr("abs:src")?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("未找到正文图片")
        if (defaultUrl == "https://ehgt.org/g/509.gif" || defaultUrl == "https://exhentai.org/img/509.gif") {
            throw IllegalStateException("E-Hentai 图片配额已用尽，请稍后再试或在 My Home 检查 Image Limits")
        }
        val nl = Regex("nl\\('([^']+)'\\)").find(doc.selectFirst("#loadfail")?.attr("onclick").orEmpty())?.groupValues?.getOrNull(1)
        val originalUrl = if (original) doc.selectFirst("a[href*=/fullimg/]")?.attr("abs:href")?.takeIf(String::isNotBlank) else null
        val reloadUrl = nl?.let { token -> response.request.url.newBuilder().setQueryParameter("nl", token).build().toString() }
        return ImageInfo(originalUrl ?: defaultUrl, reloadUrl)
    }

    fun parseComments(doc: Document): List<ParsedComment> = doc.select("#cdiv > .c1").mapNotNull { element ->
        val body = element.selectFirst(".c6") ?: return@mapNotNull null
        val id = body.id().substringAfter('_', "").ifBlank { body.attr("id").filter(Char::isDigit) }.ifBlank { return@mapNotNull null }
        val authorLink = element.selectFirst(".c2 > .c3 > a,.c3 a")
        val author = authorLink?.text()?.trim()?.takeIf(String::isNotBlank) ?: "E-Hentai 用户"
        val userId = Regex("showuser=(\\d+)").find(authorLink?.attr("href").orEmpty())?.groupValues?.getOrNull(1)
        val scoreText = element.selectFirst(".c2 > .c5.nosel > span,.c5 span")?.text()?.trim().orEmpty()
        val score = Regex("-?\\d+").find(scoreText)?.value?.toLongOrNull() ?: 0L
        val timeText = element.selectFirst(".c2 > .c3,.c3")?.ownText()?.trim()?.takeIf(String::isNotBlank)
            ?: element.selectFirst(".c2 > .c3,.c3")?.text()?.trim()
        val clean = body.clone().apply { select("script,style,.c7,form").remove() }.text().trim()
        if (clean.isBlank()) return@mapNotNull null
        ParsedComment(id, userId, author, clean, parseCommentDate(timeText), commentDisplayTime(timeText), score)
    }

    private fun extractCssUrl(style: String): String? {
        val start = style.indexOf('(')
        val end = style.lastIndexOf(')')
        if (start < 0 || end <= start) return null
        return style.substring(start + 1, end).trim().trim('\'', '"').takeIf(String::isNotBlank)
    }

    private fun buildChineseDescription(m: GalleryMetadata): String = buildString {
        m.altTitle?.takeIf { it != m.title }?.let { append("原文标题：$it\n") }
        m.uploader?.let { append("上传者：$it\n") }
        m.datePosted?.let {
            val text = LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneOffset.UTC).format(EH_DATE_FORMATTER)
            append("发布时间：$text UTC\n")
        }
        m.visible?.let { append("可见性：$it\n") }
        m.category?.let { append("分类：${categoryZh(it)}\n") }
        m.language?.let { append("语言：${languageZh(it)}${if (m.translated) "（翻译）" else ""}\n") }
        m.size?.let { append("文件大小：${humanReadableByteCount(it, true)}\n") }
        m.length?.let { append("页数：$it\n") }
        m.favorites?.let { append("收藏次数：$it\n") }
        m.averageRating?.let { rating -> append("评分：$rating${m.ratingCount?.let { "（$it 人）" }.orEmpty()}\n") }
    }.trim()
}

internal data class ParsedComment(
    val id: String,
    val userId: String?,
    val author: String,
    val content: String,
    val createdAt: Long,
    val displayTime: String?,
    val score: Long,
)

internal fun normalizeGalleryUrl(raw: String): String? {
    val segments = if (raw.startsWith("http")) Uri.parse(raw).pathSegments else raw.split('/').filter(String::isNotBlank)
    val gIndex = segments.indexOf("g")
    if (gIndex < 0 || segments.size <= gIndex + 2) return null
    val id = segments[gIndex + 1]
    val token = segments[gIndex + 2]
    if (!id.all(Char::isDigit) || token.isBlank()) return null
    return "/g/$id/$token/?nw=always"
}

internal fun galleryId(url: String): String? = normalizeGalleryUrl(url)?.split('/')?.getOrNull(2)
internal fun galleryToken(url: String): String? = normalizeGalleryUrl(url)?.split('/')?.getOrNull(3)

internal val EH_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
private val COMMENT_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.ENGLISH)

private fun parseEhDate(value: String): Long? = runCatching { EH_DATE_FORMATTER.tryParseDateTime(value, ZoneOffset.UTC) }.getOrNull()

private fun commentDisplayTime(value: String?): String? {
    val millis = parseCommentDate(value)
    if (millis <= 0L) return value?.trim()?.takeIf(String::isNotBlank)
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC).format(EH_DATE_FORMATTER) + " UTC"
}

private fun parseCommentDate(value: String?): Long {
    val raw = value.orEmpty()
    val candidate = Regex("(?i)Posted on\\s+(.+?)(?:\\s+UTC|\\s+by:|$)").find(raw)?.groupValues?.getOrNull(1)?.trim()
        ?: Regex("(\\d{1,2}\\s+[A-Za-z]+\\s+\\d{4},\\s+\\d{2}:\\d{2})").find(raw)?.value
        ?: return 0L
    return runCatching { LocalDateTime.parse(candidate, COMMENT_DATE_FORMATTER).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrDefault(0L)
}

internal fun categoryZh(value: String): String = when (value.trim().lowercase(Locale.ROOT)) {
    "doujinshi" -> "同人志"
    "manga" -> "漫画"
    "artist cg" -> "画师 CG"
    "game cg" -> "游戏 CG"
    "western" -> "西方作品"
    "non-h" -> "非 H"
    "image set" -> "图集"
    "cosplay" -> "Cosplay"
    "asian porn" -> "亚洲成人"
    "misc" -> "其他"
    else -> value
}

internal fun languageZh(value: String): String = when (value.trim().lowercase(Locale.ROOT)) {
    "chinese" -> "中文"
    "japanese" -> "日文"
    "english" -> "英文"
    "korean" -> "韩文"
    "spanish" -> "西班牙文"
    "french" -> "法文"
    "german" -> "德文"
    "italian" -> "意大利文"
    "portuguese" -> "葡萄牙文"
    "russian" -> "俄文"
    "thai" -> "泰文"
    "vietnamese" -> "越南文"
    else -> value
}

internal fun namespaceZh(value: String): String = when (value.lowercase(Locale.ROOT)) {
    "language" -> "语言"
    "reclass" -> "重分类"
    "parody" -> "原作"
    "character" -> "角色"
    "group" -> "汉化组 / 社团"
    "artist" -> "画师"
    "cosplayer" -> "Cosplayer"
    "male" -> "男性"
    "female" -> "女性"
    "mixed" -> "混合"
    "other" -> "其他"
    "location" -> "地点"
    "temp" -> "临时标签"
    "author" -> "作者"
    else -> value
}

internal fun humanReadableByteCount(bytes: Long, si: Boolean): String {
    val unit = if (si) 1000 else 1024
    if (bytes < unit) return "$bytes B"
    val exp = (ln(bytes.toDouble()) / ln(unit.toDouble())).toInt()
    val prefix = (if (si) "kMGTPE" else "KMGTPE")[exp - 1] + if (si) "" else "i"
    return String.format(Locale.US, "%.1f %sB", bytes / unit.toDouble().pow(exp.toDouble()), prefix)
}

internal fun parseHumanReadableByteCount(value: String): Double? {
    val match = Regex("([0-9.]+)\\s*([KMGT]?i?B)", RegexOption.IGNORE_CASE).find(value) ?: return null
    val number = match.groupValues[1].toDoubleOrNull() ?: return null
    return when (match.groupValues[2].uppercase(Locale.ROOT)) {
        "KB" -> number * 1_000
        "KIB" -> number * 1_024
        "MB" -> number * 1_000_000
        "MIB" -> number * 1_048_576
        "GB" -> number * 1_000_000_000
        "GIB" -> number * 1_073_741_824
        "TB" -> number * 1_000_000_000_000
        "TIB" -> number * 1_099_511_627_776
        else -> number
    }
}

private val ONGOING_SUFFIX = arrayOf("[ongoing]", "(ongoing)", "{ongoing}", "[連載中]", "[连载中]")
