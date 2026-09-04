#!/usr/bin/env python3
from pathlib import Path
import sys
import textwrap

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
source_path = root / "app/src/main/java/eu/kanade/presentation/manga/comments/CommentScreen.kt"
test_path = root / "app/src/test/java/eu/kanade/presentation/manga/comments/CommentRichContentTest.kt"

src = source_path.read_text("utf-8")

old_import = "import coil3.compose.AsyncImage\n"
new_import = "import coil3.compose.AsyncImage\nimport coil3.compose.SubcomposeAsyncImage\n"
assert src.count(old_import) == 1, "Coil import anchor mismatch"
src = src.replace(old_import, new_import, 1)

old_time_import = "import java.time.Instant\n"
new_time_import = "import java.net.URI\nimport java.time.Instant\n"
assert src.count(old_time_import) == 1, "java import anchor mismatch"
src = src.replace(old_time_import, new_time_import, 1)

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
# dedent() strips the indentation that is significant in Kotlin. Restore the 16-space
# outer indentation while preserving relative indentation inside the block.
def indent_block(value: str, spaces: int) -> str:
    prefix = " " * spaces
    return "\n".join(prefix + line if line else line for line in value.split("\n"))

old_render = indent_block(old_render, 16)
new_render = indent_block(new_render, 16)
assert src.count(old_render) == 1, "comment image rendering anchor mismatch"
src = src.replace(old_render, new_render, 1)

parser_start = src.index("internal fun parseCommentRichContent(content: String): CommentRichContent {")
parser_end = src.index("\nprivate fun formatCommentTime(timestamp: Long): String {", parser_start)
new_parser = r'''internal fun parseCommentRichContent(content: String): CommentRichContent {
    val explicitImageUrls = mutableSetOf<String>()
    val normalizedContent = normalizeExplicitCommentImages(content, explicitImageUrls)
    val imageUrls = mutableListOf<String>()
    val text = COMMENT_URL_REGEX
        .replace(normalizedContent) { match ->
            val rawUrl = match.value
            val normalizedUrl = rawUrl.trimEnd(*COMMENT_URL_TRAILING_PUNCTUATION)
            if (normalizedUrl in explicitImageUrls || isCommentImageUrl(normalizedUrl)) {
                imageUrls += normalizedUrl
                ""
            } else {
                rawUrl
            }
        }
        .replace(COMMENT_TRAILING_SPACE_BEFORE_NEWLINE_REGEX, "\n")
        .replace(COMMENT_EXCESS_NEWLINES_REGEX, "\n\n")
        .trim()

    return CommentRichContent(text = text, imageUrls = imageUrls.distinct())
}

private fun normalizeExplicitCommentImages(
    content: String,
    explicitImageUrls: MutableSet<String>,
): String {
    fun replaceExplicitImages(value: String, regex: Regex): String = regex.replace(value) { match ->
        val url = match.groupValues.getOrNull(1)
            ?.trim()
            ?.trimEnd(*COMMENT_URL_TRAILING_PUNCTUATION)
            .orEmpty()
        if (url.isBlank()) {
            match.value
        } else {
            explicitImageUrls += url
            url
        }
    }

    return sequenceOf(
        COMMENT_HTML_IMAGE_REGEX,
        COMMENT_MARKDOWN_IMAGE_REGEX,
        COMMENT_BBCODE_IMAGE_REGEX,
    ).fold(content, ::replaceExplicitImages)
}

private fun isCommentImageUrl(url: String): Boolean {
    val normalized = url.lowercase()
    if (!normalized.startsWith("https://") && !normalized.startsWith("http://")) return false
    if (COMMENT_IMAGE_FILE_REGEX.containsMatchIn(normalized)) return true

    val uri = runCatching { URI(normalized) }.getOrNull() ?: return false
    val host = uri.host?.trimEnd('.') ?: return false
    val path = uri.rawPath.orEmpty()
    val query = uri.rawQuery.orEmpty()

    if (COMMENT_DIRECT_IMAGE_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }) return true
    if (COMMENT_IMAGE_PATH_REGEX.containsMatchIn(path)) return true
    if (COMMENT_IMAGE_QUERY_REGEX.containsMatchIn(query)) return true

    val firstHostLabel = host.substringBefore('.')
    val lastPathSegment = path.substringAfterLast('/')
    return COMMENT_CDN_HOST_HINT_REGEX.matches(firstHostLabel) &&
        COMMENT_OPAQUE_ASSET_SEGMENT_REGEX.matches(lastPathSegment)
}

private val COMMENT_DIRECT_IMAGE_HOST_SUFFIXES = setOf(
    "v3mh.com",
    "kkmh.com",
    "hdslb.com",
    "biliimg.com",
    "sinaimg.cn",
    "qpic.cn",
    "qlogo.cn",
    "gtimg.cn",
    "alicdn.com",
    "byteimg.com",
    "pstatp.com",
    "douyinpic.com",
    "douyincdn.com",
    "toutiaoimg.com",
)
private val COMMENT_URL_REGEX = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
private val COMMENT_IMAGE_FILE_REGEX = Regex(
    "\\.(?:jpe?g|jfif|pjpeg|png|webp|gif|avif|bmp|heic|heif|jxl|svg)(?:[?#].*)?$",
    RegexOption.IGNORE_CASE,
)
private val COMMENT_IMAGE_PATH_REGEX = Regex(
    "/(?:comment/image|image|images|img|imgs|pic|pics|picture|pictures|photo|photos|thumb|thumbnail|thumbnails|upload|uploads|media)(?:/|$)",
    RegexOption.IGNORE_CASE,
)
private val COMMENT_IMAGE_QUERY_REGEX = Regex(
    "(?:^|&)(?:(?:format|fm|ext|image_format|imageformat)=(?:jpe?g|jfif|png|webp|gif|avif|bmp|heic|heif|jxl|svg)(?:&|$)|(?:mime|type|content-type|response-content-type)=image(?:%2f|/)(?:jpe?g|png|webp|gif|avif|bmp|heic|heif|jxl|svg)(?:&|$)|(?:x-oss-process|x-bce-process|image_process)=[^&]*image|(?:imagemogr2|imageview2)(?:/|=|&|$))",
    RegexOption.IGNORE_CASE,
)
private val COMMENT_CDN_HOST_HINT_REGEX = Regex(
    "(?:img|image|images|pic|pics|photo|media|cdn|static|cache|tncache|thumb)[a-z0-9-]*",
    RegexOption.IGNORE_CASE,
)
private val COMMENT_OPAQUE_ASSET_SEGMENT_REGEX = Regex("[a-z0-9_-]{12,}", RegexOption.IGNORE_CASE)
private val COMMENT_HTML_IMAGE_REGEX = Regex(
    "<img\\b[^>]*\\b(?:src|data-src|data-original)\\s*=\\s*[\"'](https?://[^\"']+)[\"'][^>]*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val COMMENT_MARKDOWN_IMAGE_REGEX = Regex(
    "!\\[[^]]*]\\(\\s*(https?://[^\\s)]+)(?:\\s+[\"'][^\"']*[\"'])?\\s*\\)",
    RegexOption.IGNORE_CASE,
)
private val COMMENT_BBCODE_IMAGE_REGEX = Regex(
    "\\[img(?:=[^]]*)?]\\s*(https?://[^\\s\\[]+)\\s*\\[/img]",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val COMMENT_TRAILING_SPACE_BEFORE_NEWLINE_REGEX = Regex("[ \\t]+\\n")
private val COMMENT_EXCESS_NEWLINES_REGEX = Regex("\\n{3,}")
private val COMMENT_URL_TRAILING_PUNCTUATION = charArrayOf(
    '.', ',', '，', '。', ';', '；', ':', '：', '!', '！', '?', '？', ')', '）', ']', '】',
)
'''
src = src[:parser_start] + new_parser + src[parser_end:]
source_path.write_text(src, "utf-8")

test = test_path.read_text("utf-8")
old_tail = '''    @Test
    fun `normal links stay as comment text`() {
        val url = "https://www.kuaikanmanhua.com/web/topic/906"
        val result = parseCommentRichContent("看看 $url")
        assertEquals("看看 $url", result.text)
        assertEquals(emptyList<String>(), result.imageUrls)
    }
}
'''
new_tail = '''    @Test
    fun `kuaikan opaque v3mh urls without extension become rich images`() {
        val urls = listOf(
            "https://tncache1-f1.v3mh.com/FiWI8SOJM8iQlvnvQbaL6ilNAVIG",
            "https://tncache1-f1.v3mh.com/963dae9010dfdde8bc1815556f39fd3e1646900621150",
        )
        val result = parseCommentRichContent("评论正文\\n" + urls.joinToString("\\n"))
        assertEquals("评论正文", result.text)
        assertEquals(urls, result.imageUrls)
    }

    @Test
    fun `known image cdn urls without extensions become rich images`() {
        val url = "https://i0.hdslb.com/bfs/new_dyn/0123456789abcdef0123456789abcdef"
        val result = parseCommentRichContent("哔哩哔哩图片\\n$url")
        assertEquals("哔哩哔哩图片", result.text)
        assertEquals(listOf(url), result.imageUrls)
    }

    @Test
    fun `image paths and transform queries become rich images`() {
        val pathUrl = "https://example.org/images/0123456789abcdef"
        val queryUrl = "https://example.org/resource?id=42&format=webp"
        val result = parseCommentRichContent("图一 $pathUrl\\n图二 $queryUrl")
        assertEquals("图一\\n图二", result.text)
        assertEquals(listOf(pathUrl, queryUrl), result.imageUrls)
    }

    @Test
    fun `opaque assets on image style cdn hosts become rich images`() {
        val url = "https://cdn.example.org/0123456789abcdef0123456789abcdef"
        val result = parseCommentRichContent("图片\\n$url")
        assertEquals("图片", result.text)
        assertEquals(listOf(url), result.imageUrls)
    }

    @Test
    fun `explicit markdown html and bbcode images are recognized`() {
        val markdown = "https://example.org/asset?id=markdown"
        val html = "https://example.org/asset?id=html"
        val bbcode = "https://example.org/asset?id=bbcode"
        val result = parseCommentRichContent(
            "MD ![图]($markdown)\\nHTML <img data-src='$html'>\\nBB [img]$bbcode[/img]",
        )
        assertEquals("MD\\nHTML\\nBB", result.text)
        assertEquals(listOf(markdown, html, bbcode), result.imageUrls)
    }

    @Test
    fun `signed image urls with common extra formats become rich images`() {
        val url = "https://cdn.example.org/file.heic?token=abc123"
        val result = parseCommentRichContent("原图\\n$url")
        assertEquals("原图", result.text)
        assertEquals(listOf(url), result.imageUrls)
    }

    @Test
    fun `normal links stay as comment text`() {
        val urls = listOf(
            "https://www.kuaikanmanhua.com/web/topic/906",
            "https://example.org/article/0123456789abcdef",
            "https://cdn.example.org/article/123",
        )
        val content = urls.joinToString("\\n")
        val result = parseCommentRichContent(content)
        assertEquals(content, result.text)
        assertEquals(emptyList<String>(), result.imageUrls)
    }
}
'''
assert test.count(old_tail) == 1, "test tail anchor mismatch"
test_path.write_text(test.replace(old_tail, new_tail, 1), "utf-8")

feature_workflow = root / ".github/workflows/feat-comment-media-detection.yml"
if feature_workflow.exists():
    feature_workflow.unlink()
