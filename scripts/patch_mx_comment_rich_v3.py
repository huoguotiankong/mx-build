#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
source_path = root / "app/src/main/java/eu/kanade/presentation/manga/comments/CommentScreen.kt"
test_path = root / "app/src/test/java/eu/kanade/presentation/manga/comments/CommentRichContentTest.kt"
docs_path = root / "docs/COMMENTS.md"

src = source_path.read_text("utf-8")

# Rich comment images no longer need SubcomposeAsyncImage. Keeping every image card on the
# ordinary AsyncImage path avoids one subcomposition per media item while still exposing a
# readable URL fallback after a real decode/load failure.
src = src.replace("import coil3.compose.SubcomposeAsyncImage\n", "")

old_render = '''                richContent.imageUrls.forEach { imageUrl ->
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
'''
new_render = '''                richContent.imageUrls.forEach { imageUrl ->
                    var imageLoadFailed by remember(imageUrl) { mutableStateOf(false) }
                    if (imageLoadFailed) {
                        Text(
                            text = imageUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium),
                            contentScale = ContentScale.FillWidth,
                            onError = { imageLoadFailed = true },
                        )
                    }
                }
'''
if old_render in src:
    src = src.replace(old_render, new_render, 1)
else:
    assert new_render in src, "rich comment image render anchor mismatch"

old_parser_start = '''internal fun parseCommentRichContent(content: String): CommentRichContent {
    val explicitImageUrls = linkedSetOf<String>()
    val normalizedContent = normalizeExplicitCommentMedia(content, explicitImageUrls)
'''
new_parser_start = '''internal fun parseCommentRichContent(content: String): CommentRichContent {
    val explicitImageUrls = linkedSetOf<String>()
    val decodedContent = decodeCommentEntities(content)
    val normalizedContent = normalizeExplicitCommentMedia(decodedContent, explicitImageUrls)
'''
if old_parser_start in src:
    src = src.replace(old_parser_start, new_parser_start, 1)
else:
    assert new_parser_start in src, "rich comment parser start anchor mismatch"

old_normalize_url = '''private fun normalizeCommentUrl(value: String): String {
    val unescaped = org.jsoup.parser.Parser.unescapeEntities(value.trim(), false)
        .trimEnd(*COMMENT_URL_TRAILING_PUNCTUATION)
    return if (unescaped.startsWith("//")) "https:$unescaped" else unescaped
}
'''
new_normalize_url = '''private fun decodeCommentEntities(value: String): String {
    var decoded = value
    repeat(2) {
        val next = org.jsoup.parser.Parser.unescapeEntities(decoded, false)
        if (next == decoded) return decoded
        decoded = next
    }
    return decoded
}

private fun normalizeCommentUrl(value: String): String {
    val unescaped = decodeCommentEntities(value.trim())
        .trimEnd(*COMMENT_URL_TRAILING_PUNCTUATION)
    return if (unescaped.startsWith("//")) "https:$unescaped" else unescaped
}
'''
if old_normalize_url in src:
    src = src.replace(old_normalize_url, new_normalize_url, 1)
else:
    assert new_normalize_url in src, "comment URL normalization anchor mismatch"

old_markup = '''private fun normalizeCommentMarkup(value: String): String {
    val unescaped = org.jsoup.parser.Parser.unescapeEntities(value, false)
    return unescaped
'''
new_markup = '''private fun normalizeCommentMarkup(value: String): String {
    val unescaped = decodeCommentEntities(value)
    return unescaped
'''
if old_markup in src:
    src = src.replace(old_markup, new_markup, 1)
else:
    assert new_markup in src, "comment markup normalization anchor mismatch"

source_path.write_text(src, "utf-8")

tests = test_path.read_text("utf-8")
anchor = '''    @Test
    fun `normal links stay as comment text`() {
'''
extra_tests = '''    @Test
    fun `escaped html image markup is decoded before media extraction`() {
        val url = "https://assets.example.org/object?id=escaped&token=2"
        val result = parseCommentRichContent(
            "前文&lt;img data-src=&quot;https://assets.example.org/object?id=escaped&amp;token=2&quot;&gt;后文",
        )
        assertEquals("前文后文", result.text)
        assertEquals(listOf(url), result.imageUrls)
    }

    @Test
    fun `double escaped html sticker markup is decoded safely`() {
        val url = "https://assets.example.org/sticker?id=42&format=webp"
        val result = parseCommentRichContent(
            "表情&amp;lt;sticker src=&amp;quot;https://assets.example.org/sticker?id=42&amp;amp;format=webp&amp;quot;&amp;gt;",
        )
        assertEquals("表情", result.text)
        assertEquals(listOf(url), result.imageUrls)
    }

'''
if extra_tests not in tests:
    assert tests.count(anchor) == 1, "comment rich-content test anchor mismatch"
    tests = tests.replace(anchor, extra_tests + anchor, 1)
test_path.write_text(tests, "utf-8")

docs = docs_path.read_text("utf-8")
old_docs = '''Provider-specific emoji/sticker images can be supplied as explicit image markup even when the asset URL
has no extension or comes from an unknown CDN. Unresolved Kuaikan-style tokens such as `[热词_附议]`
fall back to readable `【表情：附议】` text instead of leaking raw markup.
'''
new_docs = '''Provider-specific emoji/sticker images can be supplied as explicit image markup even when the asset URL
has no extension or comes from an unknown CDN. HTML media tags are decoded before extraction, including
entity-escaped and double-escaped payloads such as `&lt;img ...&gt;`, so provider serialization does not
silently turn images or stickers into raw text. Unresolved Kuaikan-style tokens such as `[热词_附议]`
fall back to readable `【表情：附议】` text instead of leaking raw markup.

Comment-card media uses the ordinary Coil `AsyncImage` path instead of per-item subcomposition. Failed
loads fall back to the original media URL as readable text, while successful image-heavy threads avoid
the extra composition cost that previously made long comment lists more expensive to scroll.
'''
if old_docs in docs:
    docs = docs.replace(old_docs, new_docs, 1)
else:
    assert new_docs in docs, "comments docs anchor mismatch"
docs_path.write_text(docs, "utf-8")
