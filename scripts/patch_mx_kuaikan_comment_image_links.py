#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
source_path = root / "app/src/main/java/eu/kanade/presentation/manga/comments/CommentScreen.kt"
test_path = root / "app/src/test/java/eu/kanade/presentation/manga/comments/CommentRichContentTest.kt"
build_path = root / "app/build.gradle.kts"

src = source_path.read_text("utf-8")
old_function = '''private fun isCommentImageUrl(url: String): Boolean {
    val normalized = url.lowercase()
    return COMMENT_IMAGE_FILE_REGEX.containsMatchIn(normalized) ||
        normalized.startsWith("https://cc-image.kkmh.com/comment/image/") ||
        COMMENT_V3MH_SOCIAL_IMAGE_REGEX.containsMatchIn(normalized)
}
'''
new_function = '''private fun isCommentImageUrl(url: String): Boolean {
    val normalized = url.lowercase()
    return COMMENT_IMAGE_FILE_REGEX.containsMatchIn(normalized) ||
        normalized.startsWith("https://cc-image.kkmh.com/comment/image/") ||
        COMMENT_V3MH_SOCIAL_IMAGE_REGEX.containsMatchIn(normalized) ||
        COMMENT_V3MH_TNCACHE_IMAGE_REGEX.containsMatchIn(normalized)
}
'''
assert src.count(old_function) == 1, "comment image detector anchor mismatch"
src = src.replace(old_function, new_function, 1)

old_regex = 'private val COMMENT_V3MH_SOCIAL_IMAGE_REGEX = Regex("^https?://[^/]*\\\\.v3mh\\\\.com/social/", RegexOption.IGNORE_CASE)\n'
new_regex = old_regex + 'private val COMMENT_V3MH_TNCACHE_IMAGE_REGEX = Regex("^https?://tncache[^/]*\\\\.v3mh\\\\.com/", RegexOption.IGNORE_CASE)\n'
assert src.count(old_regex) == 1, "v3mh social regex anchor mismatch"
src = src.replace(old_regex, new_regex, 1)
source_path.write_text(src, "utf-8")

test = test_path.read_text("utf-8")
anchor = '''    @Test
    fun `normal links stay as comment text`() {
'''
addition = '''    @Test
    fun `kuaikan tncache image urls without extension become rich images`() {
        val urls = listOf(
            "https://tncache1-f1.v3mh.com/FuiNNqGKcfgqCIW8Kgq2_IIN2-7v",
            "https://tncache1-f1.v3mh.com/963dae9010dfdde8bc1815556f39fd3e1646900621150",
            "https://tncache1-f1.v3mh.com/comment/image/115027473282546_2154342664-watermark-compress",
        )
        val result = parseCommentRichContent("历史图片\\n" + urls.joinToString("\\n"))
        assertEquals("历史图片", result.text)
        assertEquals(urls, result.imageUrls)
    }

'''
assert test.count(anchor) == 1, "comment parser test anchor mismatch"
test = test.replace(anchor, addition + anchor, 1)
test_path.write_text(test, "utf-8")

build = build_path.read_text("utf-8")
old_version = '''        versionCode = 83
        versionName = "1.14.3"
'''
new_version = '''        versionCode = 84
        versionName = "1.14.4"
'''
assert build.count(old_version) == 1, "MX app version anchor mismatch"
build_path.write_text(build.replace(old_version, new_version, 1), "utf-8")
