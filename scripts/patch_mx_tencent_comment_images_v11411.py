from __future__ import annotations

from pathlib import Path
from textwrap import dedent
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "source")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


screen = ROOT / "app/src/main/java/eu/kanade/presentation/manga/comments/CommentScreen.kt"
text = screen.read_text(encoding="utf-8")
text = replace_once(
    text,
    "import androidx.compose.ui.layout.ContentScale\n",
    "import androidx.compose.ui.layout.ContentScale\nimport androidx.compose.ui.platform.LocalContext\n",
    "LocalContext import",
)
text = replace_once(
    text,
    "import coil3.compose.AsyncImage\n",
    "import coil3.compose.AsyncImage\nimport coil3.network.NetworkHeaders\nimport coil3.network.httpHeaders\nimport coil3.request.ImageRequest\n",
    "Coil network imports",
)

old_render = dedent(
    """\
                    richContent.imageUrls.forEach { imageUrl ->
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
    """,
)
new_render = dedent(
    """\
                    richContent.imageUrls.forEach { imageUrl ->
                        CommentRichImage(imageUrl)
                    }
    """,
)
# dedent removes the leading 16 spaces, but the source block lives inside CommentItem.
old_render = "".join("                " + line if line.strip() else line for line in old_render.splitlines(keepends=True))
new_render = "".join("                " + line if line.strip() else line for line in new_render.splitlines(keepends=True))
text = replace_once(text, old_render, new_render, "comment image rendering block")

marker = "@Composable\nprivate fun voteIconTint(selected: Boolean, enabled: Boolean) = when {\n"
helper = dedent(
    """\
    @Composable
    private fun CommentRichImage(imageUrl: String) {
        val context = LocalContext.current
        val candidates = remember(imageUrl) { commentImageLoadCandidates(imageUrl) }
        var candidateIndex by remember(imageUrl) { mutableStateOf(0) }
        val candidate = candidates.getOrNull(candidateIndex)

        if (candidate == null) {
            Text(
                text = imageUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }

        val request = remember(context, candidate) {
            ImageRequest.Builder(context)
                .data(candidate)
                .apply {
                    val headers = commentImageRequestHeaders(candidate)
                    if (headers.isNotEmpty()) {
                        httpHeaders(
                            NetworkHeaders.Builder()
                                .apply {
                                    headers.forEach { (name, value) -> set(name, value) }
                                }
                                .build(),
                        )
                    }
                }
                .build()
        }

        AsyncImage(
            model = request,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium),
            contentScale = ContentScale.FillWidth,
            onError = { candidateIndex += 1 },
        )
    }

    @Composable
    private fun voteIconTint(selected: Boolean, enabled: Boolean) = when {
    """,
)
text = replace_once(text, marker, helper, "CommentRichImage helper insertion")

marker = "private fun isCommentImageUrl(url: String): Boolean {\n"
helpers = dedent(
    """\
    internal fun commentImageLoadCandidates(imageUrl: String): List<String> {
        val normalizedUrl = normalizeCommentUrl(imageUrl)
        val candidates = linkedSetOf(normalizedUrl)
        val uri = runCatching { URI(normalizedUrl) }.getOrNull() ?: return candidates.toList()
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return candidates.toList()

        if (host == "acimg.cn" || host.endsWith(".acimg.cn")) {
            COMMENT_TENCENT_TRANSFORMED_IMAGE_REGEX.matchEntire(normalizedUrl)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf(String::isNotBlank)
                ?.let(candidates::add)
        }

        return candidates.toList()
    }

    internal fun commentImageRequestHeaders(imageUrl: String): Map<String, String> {
        val normalizedUrl = normalizeCommentUrl(imageUrl)
        val uri = runCatching { URI(normalizedUrl) }.getOrNull() ?: return emptyMap()
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return emptyMap()
        if (host != "acimg.cn" && !host.endsWith(".acimg.cn")) return emptyMap()

        return linkedMapOf(
            "Referer" to "https://ac.qq.com/",
            "User-Agent" to COMMENT_TENCENT_IMAGE_USER_AGENT,
        )
    }

    private fun isCommentImageUrl(url: String): Boolean {
    """,
)
text = replace_once(text, marker, helpers, "Tencent image helpers insertion")

marker = "private val COMMENT_ALWAYS_IMAGE_HOST_SUFFIXES = setOf(\n"
regex = dedent(
    """\
    private val COMMENT_TENCENT_TRANSFORMED_IMAGE_REGEX = Regex(
        "^(https?://[^?#]+\\.(?:jpe?g|jfif|png|webp|gif|avif))/\\d+(?:\\?[^#]*)?(?:#.*)?$",
        RegexOption.IGNORE_CASE,
    )
    private const val COMMENT_TENCENT_IMAGE_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

    private val COMMENT_ALWAYS_IMAGE_HOST_SUFFIXES = setOf(
    """,
)
text = replace_once(text, marker, regex, "Tencent transformed URL regex insertion")
screen.write_text(text, encoding="utf-8")


tests = ROOT / "app/src/test/java/eu/kanade/presentation/manga/comments/CommentRichContentTest.kt"
test_text = tests.read_text(encoding="utf-8")
marker = dedent(
    """\
        @Test
        fun `known image cdn urls without extensions become rich images`() {
    """,
)
added = dedent(
    """\
        @Test
        fun `tencent transformed acimg urls get canonical retry candidates`() {
            val cases = listOf(
                "https://manhua.acimg.cn/manhua/0/05_16_49_f1ebabbb7cddf4f34cb4805f1e02b306.jpg/0?tp=sharp" to
                    "https://manhua.acimg.cn/manhua/0/05_16_49_f1ebabbb7cddf4f34cb4805f1e02b306.jpg",
                "https://manhua.acimg.cn/manhua/0/15_09_39_13170f3f334f7e005496d9311df42127.jpg/0?tp=sharp" to
                    "https://manhua.acimg.cn/manhua/0/15_09_39_13170f3f334f7e005496d9311df42127.jpg",
            )

            cases.forEach { (transformed, canonical) ->
                assertEquals(listOf(transformed, canonical), commentImageLoadCandidates(transformed))
            }
        }

        @Test
        fun `tencent acimg image requests get browser referer headers`() {
            val headers = commentImageRequestHeaders(
                "https://manhua.acimg.cn/manhua/0/05_16_49_f1ebabbb7cddf4f34cb4805f1e02b306.jpg/0?tp=sharp",
            )
            assertEquals("https://ac.qq.com/", headers["Referer"])
            assertEquals(true, headers["User-Agent"]?.startsWith("Mozilla/5.0"))
        }

        @Test
        fun `non tencent comment images keep one candidate and no special headers`() {
            val url = "https://i0.hdslb.com/bfs/new_dyn/0123456789abcdef0123456789abcdef"
            assertEquals(listOf(url), commentImageLoadCandidates(url))
            assertEquals(emptyMap<String, String>(), commentImageRequestHeaders(url))
        }

        @Test
        fun `known image cdn urls without extensions become rich images`() {
    """,
)
# Preserve class indentation.
marker = "".join("    " + line if line.strip() else line for line in marker.splitlines(keepends=True))
added = "".join("    " + line if line.strip() else line for line in added.splitlines(keepends=True))
test_text = replace_once(test_text, marker, added, "test insertion")
tests.write_text(test_text, encoding="utf-8")


gradle = ROOT / "app/build.gradle.kts"
gradle_text = gradle.read_text(encoding="utf-8")
gradle_text = replace_once(gradle_text, "versionCode = 90", "versionCode = 91", "versionCode")
gradle_text = replace_once(gradle_text, 'versionName = "1.14.10"', 'versionName = "1.14.11"', "versionName")
gradle.write_text(gradle_text, encoding="utf-8")


docs = ROOT / "docs/COMMENTS.md"
docs_text = docs.read_text(encoding="utf-8")
anchor = "This is a host-side rendering fix; it does not require changing the Tencent extension's comment API or attachment extraction."
addition = anchor + "\n\n" + (
    "Android device feedback on MX 1.14.10 confirmed a second failure mode: transformed `manhua.acimg.cn` attachments were recognized as media, but Coil could still fail the actual network/decode request and therefore fall back to showing the original URL. MX 1.14.11 adds Tencent-specific image request headers (`Referer: https://ac.qq.com/` plus the same browser-style User-Agent used by the extension) and, only after a transformed URL fails, retries the canonical image filename without the trailing `/0?tp=sharp` transform. The original URL remains the final readable fallback only if both image candidates fail. Regression tests cover the two exact transformed URL shapes reported from Android hardware."
)
docs_text = replace_once(docs_text, anchor, addition, "docs Tencent section")
docs.write_text(docs_text, encoding="utf-8")

print("Patched MX Tencent comment image loading for 1.14.11")
