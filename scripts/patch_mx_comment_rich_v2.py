#!/usr/bin/env python3
from pathlib import Path
import sys
import textwrap

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
source_path = root / "app/src/main/java/eu/kanade/presentation/manga/comments/CommentScreen.kt"
test_path = root / "app/src/test/java/eu/kanade/presentation/manga/comments/CommentRichContentTest.kt"
libs_path = root / "gradle/libs.versions.toml"
docs_path = root / "docs/COMMENTS.md"

src = source_path.read_text("utf-8")

old_import = "import coil3.compose.AsyncImage\n"
new_import = "import coil3.compose.AsyncImage\nimport coil3.compose.SubcomposeAsyncImage\n"
if "import coil3.compose.SubcomposeAsyncImage\n" not in src:
    assert src.count(old_import) == 1, "Coil import anchor mismatch"
    src = src.replace(old_import, new_import, 1)

old_java_import = "import java.time.Instant\n"
new_java_import = "import java.net.URI\nimport java.time.Instant\n"
if "import java.net.URI\n" not in src:
    assert src.count(old_java_import) == 1, "java import anchor mismatch"
    src = src.replace(old_java_import, new_java_import, 1)

old_render = textwrap.dedent('''\
                richContent.imageUrls.forEach { imageUrl ->
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium),
                        contentScale = ContentScale.FillWidth,
                    )
                }
''')
new_render = textwrap.dedent('''\
                richContent.imageUrls.forEach { imageUrl ->
                    SubcomposeAsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium),
                        contentScale = ContentScale.FillWidth,
                        loading = {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        },
                        error = {
                            Text(
                                text = imageUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
''')

def indent_block(value: str, spaces: int) -> str:
    prefix = " " * spaces
    return "\n".join(prefix + line if line else line for line in value.split("\n"))

old_render = indent_block(old_render, 16)
new_render = indent_block(new_render, 16)
if old_render in src:
    src = src.replace(old_render, new_render, 1)
else:
    assert "SubcomposeAsyncImage(" in src, "comment image rendering anchor mismatch"

parser_start = src.index("internal fun parseCommentRichContent(content: String): CommentRichContent {")
parser_end = src.index("\nprivate fun formatCommentTime(timestamp: Long): String {", parser_start)
new_parser = r'''internal fun parseCommentRichContent(content: String): CommentRichContent {
    val explicitImageUrls = linkedSetOf<String>()
    val normalizedContent = normalizeExplicitCommentMedia(content, explicitImageUrls)
    val imageUrls = mutableListOf<String>()
    val textWithoutImages = COMMENT_URL_REGEX.replace(normalizedContent) { match ->
        val normalizedUrl = normalizeCommentUrl(match.value)
        if (normalizedUrl in explicitImageUrls || isCommentImageUrl(normalizedUrl)) {
            imageUrls += normalizedUrl
            ""
        } else {
            match.value
        }
    }

    return CommentRichContent(
        text = normalizeCommentMarkup(textWithoutImages),
        imageUrls = imageUrls.distinct(),
    )
}

private fun normalizeExplicitCommentMedia(
    content: String,
    explicitImageUrls: MutableSet<String>,
): String {
    var value = COMMENT_HTML_MEDIA_TAG_REGEX.replace(content) { match ->
        val url = extractHtmlMediaUrl(match.value)
        if (url == null) {
            match.value
        } else {
            explicitImageUrls += url
            url
        }
    }

    fun replaceExplicitImages(input: String, regex: Regex): String = regex.replace(input) { match ->
        val rawUrl = match.groupValues.getOrNull(1).orEmpty()
        val url = normalizeCommentUrl(rawUrl)
        if (!isHttpCommentUrl(url)) {
            match.value
        } else {
            explicitImageUrls += url
            url
        }
    }

    value = replaceExplicitImages(value, COMMENT_MARKDOWN_IMAGE_REGEX)
    value = replaceExplicitImages(value, COMMENT_BBCODE_IMAGE_REGEX)
    return value
}

private fun extractHtmlMediaUrl(tag: String): String? {
    val raw = COMMENT_HTML_MEDIA_ATTR_REGEX.find(tag)?.let(::firstNonBlankCapture)
        ?: COMMENT_HTML_SRCSET_ATTR_REGEX.find(tag)?.let(::firstNonBlankCapture)
            ?.substringBefore(',')
            ?.trim()
            ?.substringBefore(' ')
    return raw
        ?.let(::normalizeCommentUrl)
        ?.takeIf(::isHttpCommentUrl)
}

private fun firstNonBlankCapture(match: MatchResult): String? = match.groupValues
    .drop(1)
    .firstOrNull(String::isNotBlank)

private fun normalizeCommentUrl(value: String): String {
    val unescaped = org.jsoup.parser.Parser.unescapeEntities(value.trim(), false)
        .trimEnd(*COMMENT_URL_TRAILING_PUNCTUATION)
    return if (unescaped.startsWith("//")) "https:$unescaped" else unescaped
}

private fun isHttpCommentUrl(url: String): Boolean =
    url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

private fun normalizeCommentMarkup(value: String): String {
    val unescaped = org.jsoup.parser.Parser.unescapeEntities(value, false)
    return unescaped
        .replace(COMMENT_HTML_BREAK_REGEX, "\n")
        .replace(COMMENT_HTML_BLOCK_TAG_REGEX, "\n")
        .replace(COMMENT_HTML_ANY_TAG_REGEX, "")
        .replace(COMMENT_BBCODE_BREAK_REGEX, "\n")
        .replace(COMMENT_BBCODE_FORMATTING_REGEX, "")
        .replace(COMMENT_CUSTOM_EMOJI_TOKEN_REGEX) { match ->
            val name = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (name.isBlank()) match.value else "【表情：$name】"
        }
        .replace(COMMENT_TRAILING_SPACE_BEFORE_NEWLINE_REGEX, "\n")
        .replace(COMMENT_EXCESS_NEWLINES_REGEX, "\n\n")
        .trim()
}

private fun isCommentImageUrl(url: String): Boolean {
    val normalizedUrl = normalizeCommentUrl(url)
    if (!isHttpCommentUrl(normalizedUrl)) return false
    val normalized = normalizedUrl.lowercase()
    if (COMMENT_IMAGE_FILE_REGEX.containsMatchIn(normalized)) return true

    val uri = runCatching { URI(normalizedUrl) }.getOrNull() ?: return false
    val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
    val path = uri.rawPath.orEmpty()
    val query = uri.rawQuery.orEmpty()
    val firstHostLabel = host.substringBefore('.')
    val lastPathSegment = path.substringAfterLast('/')

    if (host == "cc-image.kkmh.com") return true
    if (COMMENT_KUAIKAN_IMAGE_HOST_REGEX.matches(host)) return true
    if ((host == "v3mh.com" || host.endsWith(".v3mh.com")) &&
        COMMENT_OPAQUE_ASSET_SEGMENT_REGEX.matches(lastPathSegment)
    ) {
        return true
    }
    if (COMMENT_ALWAYS_IMAGE_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }) return true
    if (COMMENT_IMAGE_PATH_REGEX.containsMatchIn(path)) return true
    if (COMMENT_IMAGE_QUERY_REGEX.containsMatchIn(query)) return true

    return COMMENT_CDN_HOST_HINT_REGEX.matches(firstHostLabel) &&
        COMMENT_OPAQUE_ASSET_SEGMENT_REGEX.matches(lastPathSegment)
}

private val COMMENT_ALWAYS_IMAGE_HOST_SUFFIXES = setOf(
    "biliimg.com",
    "hdslb.com",
    "sinaimg.cn",
    "qpic.cn",
    "qlogo.cn",
    "gtimg.cn",
    "douyinpic.com",
    "toutiaoimg.com",
)
private val COMMENT_URL_REGEX = Regex(
    "(?:(?:https?:)?//)[^\\s<>\"']+",
    RegexOption.IGNORE_CASE,
)
private val COMMENT_IMAGE_FILE_REGEX = Regex(
    "\\.(?:jpe?g|jfif|pjpeg|pjp|png|apng|webp|gif|avif|bmp|dib|heic|heif|svg|svgz|wbmp)(?:[?#].*)?$",
    RegexOption.IGNORE_CASE,
)
private val COMMENT_IMAGE_PATH_REGEX = Regex(
    "/(?:comment/image|social|meme|emoji|emoticon|emotion|sticker|image|images|img|imgs|pic|pics|picture|pictures|photo|photos|thumb|thumbnail|thumbnails|attachment|attachments|upload|uploads|media)(?:/|$)",
    RegexOption.IGNORE_CASE,
)
private val COMMENT_IMAGE_QUERY_REGEX = Regex(
    "(?:^|&)(?:(?:format|fm|fmt|ext|image_format|imageformat)=(?:jpe?g|jfif|png|apng|webp|gif|avif|bmp|heic|heif|svg|wbmp)(?:&|$)|(?:mime|type|content-type|response-content-type)=image(?:%2f|/)(?:jpe?g|png|webp|gif|avif|bmp|heic|heif|svg)(?:&|$)|(?:x-oss-process|x-bce-process|image_process)=[^&]*(?:image|resize|format)|(?:imagemogr2|imageview2)(?:/|=|&|$))",
    RegexOption.IGNORE_CASE,
)
private val COMMENT_CDN_HOST_HINT_REGEX = Regex(
    "(?:img|image|images|pic|pics|photo|media|cdn|static|cache|tncache|thumb|cc-image)[a-z0-9-]*",
    RegexOption.IGNORE_CASE,
)
private val COMMENT_KUAIKAN_IMAGE_HOST_REGEX = Regex(
    "^(?:cc-image|tn(?:cache)?[a-z0-9-]*|img[a-z0-9-]*|image[a-z0-9-]*|pic[a-z0-9-]*|f[0-9]+[a-z0-9-]*)\\.(?:kkmh|v3mh)\\.com$",
    RegexOption.IGNORE_CASE,
)
private val COMMENT_OPAQUE_ASSET_SEGMENT_REGEX = Regex("[a-z0-9_-]{12,}", RegexOption.IGNORE_CASE)
private val COMMENT_HTML_MEDIA_TAG_REGEX = Regex(
    "<(?:img|image|emoji|emoticon|sticker)\\b[^>]*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val COMMENT_HTML_MEDIA_ATTR_REGEX = Regex(
    "\\b(?:src|data-src|data-original|data-url|data-lazy-src|data-lazy|href)\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s>]+))",
    RegexOption.IGNORE_CASE,
)
private val COMMENT_HTML_SRCSET_ATTR_REGEX = Regex(
    "\\bsrcset\\s*=\\s*(?:\"([^\"]+)\"|'([^']+)'|([^\\s>]+))",
    RegexOption.IGNORE_CASE,
)
private val COMMENT_MARKDOWN_IMAGE_REGEX = Regex(
    "!\\[[^]]*]\\(\\s*<?((?:(?:https?:)?//)[^\\s)>]+)>?(?:\\s+[\"'][^\"']*[\"'])?\\s*\\)",
    RegexOption.IGNORE_CASE,
)
private val COMMENT_BBCODE_IMAGE_REGEX = Regex(
    "\\[(?:img|image|photo|emoji|emoticon|sticker)(?:=[^]]*)?]\\s*((?:(?:https?:)?//)[^\\s\\[]+)\\s*\\[/(?:img|image|photo|emoji|emoticon|sticker)]",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val COMMENT_HTML_BREAK_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val COMMENT_HTML_BLOCK_TAG_REGEX = Regex(
    "</?(?:p|div|li|ul|ol|blockquote|section|article|h[1-6]|pre)\\b[^>]*>",
    RegexOption.IGNORE_CASE,
)
private val COMMENT_HTML_ANY_TAG_REGEX = Regex("<[^>]+>")
private val COMMENT_BBCODE_BREAK_REGEX = Regex("\\[(?:br|p)]|\\[/(?:p|quote)]", RegexOption.IGNORE_CASE)
private val COMMENT_BBCODE_FORMATTING_REGEX = Regex(
    "\\[/?(?:b|i|u|s|strike|strong|em|color|size|font|center|left|right|quote|code|spoiler|url)(?:=[^]]*)?]",
    RegexOption.IGNORE_CASE,
)
private val COMMENT_CUSTOM_EMOJI_TOKEN_REGEX = Regex(
    "\\[(?:热词|表情|表情包|emoji|emoticon|meme|sticker|emotion)[_：:]([^\\[\\]\\n]{1,80})]",
    RegexOption.IGNORE_CASE,
)
private val COMMENT_TRAILING_SPACE_BEFORE_NEWLINE_REGEX = Regex("[ \\t]+\\n")
private val COMMENT_EXCESS_NEWLINES_REGEX = Regex("\\n{3,}")
private val COMMENT_URL_TRAILING_PUNCTUATION = charArrayOf(
    '.', ',', '，', '。', ';', '；', ':', '：', '!', '！', '?', '？', ')', '）', ']', '】',
)
'''
src = src[:parser_start] + new_parser + src[parser_end:]
source_path.write_text(src, "utf-8")

# Keep SVG support aligned with URL recognition instead of turning valid SVG stickers into load errors.
libs = libs_path.read_text("utf-8")
if 'coil-svg = { module = "io.coil-kt.coil3:coil-svg" }' not in libs:
    anchor = 'coil-gif = { module = "io.coil-kt.coil3:coil-gif" }\n'
    assert libs.count(anchor) == 1, "coil gif catalog anchor mismatch"
    libs = libs.replace(anchor, anchor + 'coil-svg = { module = "io.coil-kt.coil3:coil-svg" }\n', 1)
old_bundle = 'coil = ["coil-core", "coil-gif", "coil-compose", "coil-network-okhttp"]'
new_bundle = 'coil = ["coil-core", "coil-gif", "coil-svg", "coil-compose", "coil-network-okhttp"]'
if old_bundle in libs:
    libs = libs.replace(old_bundle, new_bundle, 1)
else:
    assert new_bundle in libs, "coil bundle anchor mismatch"
libs_path.write_text(libs, "utf-8")

test_path.write_text(r'''package eu.kanade.presentation.manga.comments

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommentRichContentTest {
    @Test
    fun `kuaikan chapter image urls become rich images`() {
        val result = parseCommentRichContent(
            "正文\nhttps://cc-image.kkmh.com/comment/image/1592609200331.jpg\n" +
                "https://cc-image.kkmh.com/comment/image/1592609214776_0aAwHMPZ1y.jpg",
        )
        assertEquals("正文", result.text)
        assertEquals(2, result.imageUrls.size)
    }

    @Test
    fun `kuaikan legacy tncache urls without extensions become rich images`() {
        val urls = listOf(
            "https://tncache1-f1.v3mh.com/FuiNNqGKcfgqCIW8Kgq2_IIN2-7v",
            "https://tncache1-f1.v3mh.com/963dae9010dfdde8bc1815556f39fd3e1646900621150",
            "https://tncache1-f1.v3mh.com/comment/image/115027473282546_2154342664-watermark-compress",
        )
        val result = parseCommentRichContent("历史图片\n" + urls.joinToString("\n"))
        assertEquals("历史图片", result.text)
        assertEquals(urls, result.imageUrls)
    }

    @Test
    fun `protocol relative kuaikan images become https rich images`() {
        val result = parseCommentRichContent("图\n//tncache1-f1.v3mh.com/0123456789abcdef")
        assertEquals("图", result.text)
        assertEquals(listOf("https://tncache1-f1.v3mh.com/0123456789abcdef"), result.imageUrls)
    }

    @Test
    fun `known image cdn urls without extensions become rich images`() {
        val url = "https://i0.hdslb.com/bfs/new_dyn/0123456789abcdef0123456789abcdef"
        val result = parseCommentRichContent("哔哩哔哩图片\n$url")
        assertEquals("哔哩哔哩图片", result.text)
        assertEquals(listOf(url), result.imageUrls)
    }

    @Test
    fun `image paths and transform queries become rich images`() {
        val pathUrl = "https://example.org/images/0123456789abcdef"
        val queryUrl = "https://example.org/resource?id=42&format=webp"
        val result = parseCommentRichContent("图一 $pathUrl\n图二 $queryUrl")
        assertEquals("图一\n图二", result.text)
        assertEquals(listOf(pathUrl, queryUrl), result.imageUrls)
    }

    @Test
    fun `opaque assets on image style cdn hosts become rich images`() {
        val url = "https://cdn.example.org/0123456789abcdef0123456789abcdef"
        val result = parseCommentRichContent("图片\n$url")
        assertEquals("图片", result.text)
        assertEquals(listOf(url), result.imageUrls)
    }

    @Test
    fun `explicit markdown html and bbcode media are recognized even without extensions`() {
        val markdown = "https://assets.example.org/object?id=markdown"
        val html = "https://assets.example.org/object?id=html"
        val bbcode = "https://assets.example.org/object?id=bbcode"
        val result = parseCommentRichContent(
            "MD ![图]($markdown)\nHTML <img data-src='$html'>\nBB [img]$bbcode[/img]",
        )
        assertEquals("MD\nHTML\nBB", result.text)
        assertEquals(listOf(markdown, html, bbcode), result.imageUrls)
    }

    @Test
    fun `html image srcset and unquoted source are recognized`() {
        val srcset = "https://assets.example.org/a?id=1"
        val unquoted = "https://assets.example.org/b?id=2"
        val result = parseCommentRichContent(
            "<img srcset=\"$srcset 1x, https://assets.example.org/a2 2x\">\n<img src=$unquoted>",
        )
        assertEquals("", result.text)
        assertEquals(listOf(srcset, unquoted), result.imageUrls)
    }

    @Test
    fun `common modern image formats are recognized`() {
        val urls = listOf(
            "https://cdn.example.org/a.apng",
            "https://cdn.example.org/b.avif?token=1",
            "https://cdn.example.org/c.heic",
            "https://cdn.example.org/d.heif",
            "https://cdn.example.org/e.svg",
            "https://cdn.example.org/f.wbmp",
        )
        val result = parseCommentRichContent(urls.joinToString("\n"))
        assertEquals("", result.text)
        assertEquals(urls, result.imageUrls)
    }

    @Test
    fun `html and bbcode formatting tags are removed while text is preserved`() {
        val result = parseCommentRichContent(
            "<p><strong>你好</strong><br><span>世界&amp;朋友</span></p>[b]粗体[/b][br][color=#fff]彩色[/color]",
        )
        assertEquals("你好\n世界&朋友\n粗体\n彩色", result.text)
        assertEquals(emptyList<String>(), result.imageUrls)
    }

    @Test
    fun `unresolved custom emoji tokens get readable fallback text`() {
        val result = parseCommentRichContent("支持[热词_附议] [emoji_smile] [表情包：开心]")
        assertEquals("支持【表情：附议】 【表情：smile】 【表情：开心】", result.text)
        assertEquals(emptyList<String>(), result.imageUrls)
    }

    @Test
    fun `duplicate image urls are rendered only once`() {
        val url = "https://cdn.example.org/repeat.png"
        val result = parseCommentRichContent("$url\n[url=$url]重复[/url]\n$url")
        assertEquals("重复", result.text)
        assertEquals(listOf(url), result.imageUrls)
    }

    @Test
    fun `normal links stay as comment text`() {
        val urls = listOf(
            "https://www.kuaikanmanhua.com/web/topic/906",
            "https://example.org/article/0123456789abcdef",
            "https://cdn.example.org/article/123",
        )
        val content = urls.joinToString("\n")
        val result = parseCommentRichContent(content)
        assertEquals(content, result.text)
        assertEquals(emptyList<String>(), result.imageUrls)
    }
}
''', "utf-8")

docs = docs_path.read_text("utf-8")
section = '''\n## Rich comment content rendering\n\nMX comment cards normalize provider rich-content payloads before rendering. In addition to ordinary\nJPEG/PNG/WebP/GIF/AVIF URLs, the host recognizes extensionless image-CDN assets, image transformation\nqueries, protocol-relative URLs, and explicit HTML/Markdown/BBCode image markup. Common modern image\nformats such as APNG, HEIC/HEIF, SVG, and WBMP are recognized; Coil SVG decoding is enabled in the app.\n\nCommon HTML and BBCode presentation tags are stripped while preserving readable text and line breaks.\nProvider-specific emoji/sticker images can be supplied as explicit image markup even when the asset URL\nhas no extension or comes from an unknown CDN. Unresolved Kuaikan-style tokens such as `[热词_附议]`\nfall back to readable `【表情：附议】` text instead of leaking raw markup.\n\nThe renderer does not probe arbitrary links with HEAD/GET requests merely to guess whether they are\nimages. This keeps scrolling deterministic and avoids turning the comment list into a large number of\nextra network requests. Non-image links therefore remain ordinary text unless a provider marks them as\nmedia or they match a known image form.\n'''
if "## Rich comment content rendering" not in docs:
    docs_path.write_text(docs.rstrip() + "\n" + section, "utf-8")
