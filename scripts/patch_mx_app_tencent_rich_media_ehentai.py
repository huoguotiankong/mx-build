from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
screen = root / "app/src/main/java/eu/kanade/presentation/manga/comments/CommentScreen.kt"
tests = root / "app/src/test/java/eu/kanade/presentation/manga/comments/CommentRichContentTest.kt"
docs = root / "docs/COMMENTS.md"

text = screen.read_text(encoding="utf-8")

old = '''            val rawUrl = match.value
            val normalizedUrl = rawUrl.trimEnd(*COMMENT_URL_TRAILING_PUNCTUATION)
            if (isCommentImageUrl(normalizedUrl)) {
                imageUrls += normalizedUrl
'''
new = '''            val rawUrl = match.value
            val normalizedUrl = normalizeCommentMediaUrl(rawUrl)
            if (isCommentImageUrl(normalizedUrl)) {
                imageUrls += normalizedUrl
'''
if old in text:
    text = text.replace(old, new, 1)
elif new not in text:
    raise SystemExit("Comment URL normalization baseline not found")

old = '''private fun isCommentImageUrl(url: String): Boolean {
    val normalized = url.lowercase()
    return COMMENT_IMAGE_FILE_REGEX.containsMatchIn(normalized) ||
        normalized.startsWith("https://cc-image.kkmh.com/comment/image/") ||
        COMMENT_V3MH_SOCIAL_IMAGE_REGEX.containsMatchIn(normalized)
}

private val COMMENT_URL_REGEX = Regex("https?://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE)
private val COMMENT_IMAGE_FILE_REGEX = Regex("\\.(?:jpe?g|png|webp|gif|avif)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
private val COMMENT_V3MH_SOCIAL_IMAGE_REGEX = Regex("^https?://[^/]*\\.v3mh\\.com/social/", RegexOption.IGNORE_CASE)
'''
new = '''private fun normalizeCommentMediaUrl(rawUrl: String): String = rawUrl
    .trimEnd(*COMMENT_URL_TRAILING_PUNCTUATION)
    .replace("&amp;", "&", ignoreCase = true)
    .let { if (it.startsWith("//")) "https:$it" else it }

private fun isCommentImageUrl(url: String): Boolean {
    val normalized = url.lowercase()
    return COMMENT_IMAGE_FILE_REGEX.containsMatchIn(normalized) ||
        normalized.startsWith("https://cc-image.kkmh.com/comment/image/") ||
        COMMENT_V3MH_SOCIAL_IMAGE_REGEX.containsMatchIn(normalized) ||
        COMMENT_TENCENT_IMAGE_HOST_REGEX.containsMatchIn(normalized)
}

private val COMMENT_URL_REGEX = Regex("(?:https?:)?//[^\\s<>\\\"']+", RegexOption.IGNORE_CASE)
private val COMMENT_IMAGE_FILE_REGEX = Regex(
    "\\.(?:jpe?g|jfif|png|apng|webp|gif|avif|bmp|heic|heif)(?:/[^?#\\s]*)?(?:[?#].*)?$",
    RegexOption.IGNORE_CASE,
)
private val COMMENT_V3MH_SOCIAL_IMAGE_REGEX = Regex("^https?://[^/]*\\.v3mh\\.com/social/", RegexOption.IGNORE_CASE)
private val COMMENT_TENCENT_IMAGE_HOST_REGEX = Regex(
    "^https?://(?:manhua\\.acimg\\.cn|manhua\\.qpic\\.cn|ugc\\.qpic\\.cn|gtimg\\.ac\\.qq\\.com|gtimgcdn\\.ac\\.qq\\.com)/",
    RegexOption.IGNORE_CASE,
)
'''
if old in text:
    text = text.replace(old, new, 1)
elif new not in text:
    raise SystemExit("Comment image matcher baseline not found")

screen.write_text(text, encoding="utf-8")

test_text = tests.read_text(encoding="utf-8")
marker = '''    @Test
    fun `normal links stay as comment text`() {
'''
insert = '''    @Test
    fun `tencent transformed jpg urls become rich images`() {
        val url = "https://manhua.acimg.cn/comment/example.jpg/0?tp=sharp&wxfrom=5"
        val result = parseCommentRichContent("腾讯图片\\n$url")
        assertEquals("腾讯图片", result.text)
        assertEquals(listOf(url), result.imageUrls)
    }

    @Test
    fun `tencent image cdn urls without file extension become rich images`() {
        val url = "https://ugc.qpic.cn/qqcomic/comment/abcdef012345"
        val result = parseCommentRichContent("配图\\n$url")
        assertEquals("配图", result.text)
        assertEquals(listOf(url), result.imageUrls)
    }

    @Test
    fun `protocol relative media url is normalized`() {
        val result = parseCommentRichContent("//manhua.qpic.cn/comment/sample.webp")
        assertEquals("", result.text)
        assertEquals(listOf("https://manhua.qpic.cn/comment/sample.webp"), result.imageUrls)
    }

    @Test
    fun `html encoded image query parameters are normalized`() {
        val result = parseCommentRichContent("https://manhua.acimg.cn/comment/a.png/0?tp=sharp&amp;quality=90")
        assertEquals("", result.text)
        assertEquals(
            listOf("https://manhua.acimg.cn/comment/a.png/0?tp=sharp&quality=90"),
            result.imageUrls,
        )
    }

    @Test
    fun `additional image extensions are recognized`() {
        val urls = listOf(
            "https://example.com/a.apng",
            "https://example.com/b.jfif?x=1",
            "https://example.com/c.heic/0?quality=90",
        )
        val result = parseCommentRichContent(urls.joinToString("\\n"))
        assertEquals("", result.text)
        assertEquals(urls, result.imageUrls)
    }

'''
if "tencent transformed jpg urls become rich images" not in test_text:
    if marker not in test_text:
        raise SystemExit("CommentRichContentTest insertion marker not found")
    test_text = test_text.replace(marker, insert + marker, 1)
tests.write_text(test_text, encoding="utf-8")

doc_text = docs.read_text(encoding="utf-8")
note = '''

## 2026-09-05 Tencent / Kuaikan rich-media compatibility follow-up

- Comment media parsing now accepts CDN transform paths such as `...jpg/0?tp=sharp`, rather than requiring the image extension to be the final URL path segment.
- The parser recognizes additional common image extensions (`jfif`, `apng`, `bmp`, `heic`, `heif`) and keeps existing `jpg/png/webp/gif/avif` support.
- Protocol-relative media URLs are normalized to HTTPS and HTML-escaped `&amp;` query separators are decoded before image loading.
- Known Tencent Comics image CDNs discovered in the official Android 12.19.9 package are treated as image media even when the URL has no file extension: `manhua.acimg.cn`, `manhua.qpic.cn`, `ugc.qpic.cn`, `gtimg.ac.qq.com`, `gtimgcdn.ac.qq.com`.
- Normal non-image links remain comment text.
'''
if "## 2026-09-05 Tencent / Kuaikan rich-media compatibility follow-up" not in doc_text:
    doc_text += note
docs.write_text(doc_text, encoding="utf-8")
