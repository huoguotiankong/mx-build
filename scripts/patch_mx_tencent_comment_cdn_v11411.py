from __future__ import annotations

import sys
from pathlib import Path


ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
COMMENT_SCREEN = ROOT / "app/src/main/java/eu/kanade/presentation/manga/comments/CommentScreen.kt"
COMMENT_TEST = ROOT / "app/src/test/java/eu/kanade/presentation/manga/comments/CommentRichContentTest.kt"
APP_BUILD = ROOT / "app/build.gradle.kts"
COMMENTS_DOC = ROOT / "docs/COMMENTS.md"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Missing patch anchor: {label}")
    if text.count(old) != 1:
        raise SystemExit(f"Patch anchor is not unique ({text.count(old)}): {label}")
    return text.replace(old, new, 1)


screen = COMMENT_SCREEN.read_text(encoding="utf-8")
screen = replace_once(
    screen,
    "import androidx.compose.ui.layout.ContentScale\n",
    "import androidx.compose.ui.layout.ContentScale\nimport androidx.compose.ui.platform.LocalContext\n",
    "LocalContext import",
)
screen = replace_once(
    screen,
    "import coil3.compose.AsyncImage\n",
    "import coil3.compose.AsyncImage\nimport coil3.network.NetworkHeaders\nimport coil3.network.httpHeaders\nimport coil3.request.ImageRequest\n",
    "Coil request header imports",
)

old_render = '''                richContent.imageUrls.forEach { imageUrl ->
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
new_render = '''                val commentImageContext = LocalContext.current
                richContent.imageUrls.forEach { imageUrl ->
                    val imageCandidates = remember(imageUrl) { commentImageCandidates(imageUrl) }
                    var imageCandidateIndex by remember(imageUrl) { mutableStateOf(0) }
                    if (imageCandidateIndex >= imageCandidates.size) {
                        Text(
                            text = imageUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val candidateUrl = imageCandidates[imageCandidateIndex]
                        val imageRequest = remember(commentImageContext, candidateUrl) {
                            buildCommentImageRequest(commentImageContext, candidateUrl)
                        }
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium),
                            contentScale = ContentScale.FillWidth,
                            onError = { imageCandidateIndex++ },
                        )
                    }
                }
'''
screen = replace_once(screen, old_render, new_render, "comment image rendering fallback")

helper_anchor = '''private fun isCommentImageUrl(url: String): Boolean {
'''
helper_block = '''internal fun commentImageCandidates(url: String): List<String> {
    val normalizedUrl = normalizeCommentUrl(url)
    if (!isTencentCommentImageHost(normalizedUrl)) return listOf(normalizedUrl)

    val canonicalUrl = normalizedUrl.replace(COMMENT_TENCENT_IMAGE_TRANSFORM_SUFFIX_REGEX, "$1")
    return listOf(normalizedUrl, canonicalUrl).distinct()
}

private fun buildCommentImageRequest(
    context: android.content.Context,
    url: String,
): ImageRequest {
    val builder = ImageRequest.Builder(context).data(url)
    if (isTencentCommentImageHost(url)) {
        builder.httpHeaders(TENCENT_COMMENT_IMAGE_HEADERS)
    }
    return builder.build()
}

private fun isTencentCommentImageHost(url: String): Boolean {
    val host = runCatching { URI(url).host?.lowercase()?.trimEnd('.') }.getOrNull() ?: return false
    return host == "acimg.cn" || host.endsWith(".acimg.cn")
}

private fun isCommentImageUrl(url: String): Boolean {
'''
screen = replace_once(screen, helper_anchor, helper_block, "Tencent comment image request helpers")

regex_anchor = '''private val COMMENT_ALWAYS_IMAGE_HOST_SUFFIXES = setOf(
'''
regex_block = '''private val COMMENT_TENCENT_IMAGE_TRANSFORM_SUFFIX_REGEX = Regex(
    "(\\\\.(?:jpe?g|png|webp|gif|avif))/(?:\\\\d+)(?:\\\\?[^#]*)?(?:#.*)?$",
    RegexOption.IGNORE_CASE,
)
private val TENCENT_COMMENT_IMAGE_HEADERS = NetworkHeaders.Builder()
    .set("Referer", "https://ac.qq.com/")
    .set(
        "User-Agent",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
    )
    .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
    .build()

private val COMMENT_ALWAYS_IMAGE_HOST_SUFFIXES = setOf(
'''
screen = replace_once(screen, regex_anchor, regex_block, "Tencent transform regex and request headers")
COMMENT_SCREEN.write_text(screen, encoding="utf-8")


test = COMMENT_TEST.read_text(encoding="utf-8")
test_anchor = '''    @Test
    fun `known image cdn urls without extensions become rich images`() {
'''
test_block = '''    @Test
    fun `tencent transformed image candidates include canonical original`() {
        val transformed =
            "https://manhua.acimg.cn/manhua/0/05_16_49_f1ebabbb7cddf4f34cb4805f1e02b306.jpg/0?tp=sharp"
        val canonical =
            "https://manhua.acimg.cn/manhua/0/05_16_49_f1ebabbb7cddf4f34cb4805f1e02b306.jpg"
        assertEquals(listOf(transformed, canonical), commentImageCandidates(transformed))
    }

    @Test
    fun `non Tencent image candidate is not rewritten`() {
        val url = "https://cc-image.kkmh.com/comment/image/1592609200331.jpg"
        assertEquals(listOf(url), commentImageCandidates(url))
    }

    @Test
    fun `known image cdn urls without extensions become rich images`() {
'''
test = replace_once(test, test_anchor, test_block, "Tencent CDN fallback tests")
COMMENT_TEST.write_text(test, encoding="utf-8")


build = APP_BUILD.read_text(encoding="utf-8")
build = replace_once(build, "versionCode = 90", "versionCode = 91", "versionCode 91")
build = replace_once(build, 'versionName = "1.14.10"', 'versionName = "1.14.11"', "versionName 1.14.11")
APP_BUILD.write_text(build, encoding="utf-8")


doc = COMMENTS_DOC.read_text(encoding="utf-8")
marker = "## 2026-09-06 Tencent CDN image loading follow-up"
if marker not in doc:
    doc = doc.rstrip() + f'''\n\n{marker}\n\nAndroid 实机在 MX 1.14.10 + 腾讯动漫 1.4.26 上确认：`manhua.acimg.cn/...jpg/0?tp=sharp` 已被富媒体解析器识别，但部分图片加载失败后 `AsyncImage.onError` 会回退显示原 URL，因此问题位于图片网络加载而不是 URL 识别。\n\nMX 1.14.11 针对 `acimg.cn` 评论图片增加：\n\n- Coil 单请求 `Referer: https://ac.qq.com/`、浏览器 User-Agent 与图片 Accept 请求头；\n- 对腾讯 `.<format>/0?tp=...` 转换地址保留原请求，并在失败后自动尝试去掉转换尾巴的原图 URL；\n- 仅当转换地址和原图地址都失败时才显示 URL 文本；\n- 单元测试覆盖腾讯转换地址候选生成以及非腾讯图片不改写。\n\n实机仍以用户复测为最终真值。\n'''
    COMMENTS_DOC.write_text(doc, encoding="utf-8")

print("MX Tencent comment CDN v1.14.11 patch applied")
