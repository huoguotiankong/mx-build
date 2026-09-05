from pathlib import Path
import re
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('.')
screen = root / 'app/src/main/java/eu/kanade/presentation/manga/comments/CommentScreen.kt'
text = screen.read_text(encoding='utf-8')

old = '''        .replace(COMMENT_BBCODE_FORMATTING_REGEX, "")
        .replace(COMMENT_CUSTOM_EMOJI_TOKEN_REGEX) { match ->
'''
new = '''        .replace(COMMENT_BBCODE_FORMATTING_REGEX, "")
        .replace(COMMENT_COLON_EMOJI_TOKEN_REGEX) { match ->
            val raw = match.groupValues.getOrNull(1)?.trim().orEmpty()
            val name = raw.removePrefix("b").removePrefix("B").trim().ifBlank { raw }
            if (name.isBlank()) match.value else "【表情：$name】"
        }
        .replace(COMMENT_CUSTOM_EMOJI_TOKEN_REGEX) { match ->
'''
if old not in text:
    raise SystemExit('comment markup baseline not found')
text = text.replace(old, new, 1)

old = '''private val COMMENT_ALWAYS_IMAGE_HOST_SUFFIXES = setOf(
    "biliimg.com",
'''
new = '''private val COMMENT_ALWAYS_IMAGE_HOST_SUFFIXES = setOf(
    "acimg.cn",
    "biliimg.com",
'''
if old not in text:
    raise SystemExit('image host set baseline not found')
text = text.replace(old, new, 1)

image_block = re.compile(
    r'private val COMMENT_IMAGE_FILE_REGEX = Regex\(\n.*?\n\)',
    re.DOTALL,
)
replacement = '''private val COMMENT_IMAGE_FILE_REGEX = Regex(
    "\\\\.(?:jpe?g|jfif|pjpeg|pjp|png|apng|webp|gif|avif|bmp|dib|heic|heif|svg|svgz|wbmp)(?:(?:[/@!~])[^\\\\s<>\\\"'?#]*)*(?:[?#].*)?$",
    RegexOption.IGNORE_CASE,
)'''
text, count = image_block.subn(lambda _: replacement, text, count=1)
if count != 1:
    raise SystemExit(f'image extension regex block matches={count}')

marker = '''private val COMMENT_CUSTOM_EMOJI_TOKEN_REGEX = Regex(
'''
insertion = '''private val COMMENT_COLON_EMOJI_TOKEN_REGEX = Regex(
    "\\\\[:([^:\\\\]\\\\r\\\\n]{1,40}):]",
)
private val COMMENT_CUSTOM_EMOJI_TOKEN_REGEX = Regex(
'''
if marker not in text:
    raise SystemExit('custom emoji regex marker not found')
text = text.replace(marker, insertion, 1)
screen.write_text(text, encoding='utf-8')

tests = root / 'app/src/test/java/eu/kanade/presentation/manga/comments/CommentRichContentTest.kt'
t = tests.read_text(encoding='utf-8')
marker = '''    @Test
    fun `normal links stay as comment text`() {
'''
cases = '''    @Test
    fun `tencent transformed jpg urls become rich images`() {
        val url = "https://manhua.acimg.cn/manhua/0/15_09_39_13170f3f334f7e005496d9311df42127.jpg/0?tp=sharp"
        val result = parseCommentRichContent("画了一张图\\n$url")
        assertEquals("画了一张图", result.text)
        assertEquals(listOf(url), result.imageUrls)
    }

    @Test
    fun `tencent legacy colon emoji tokens get readable fallback`() {
        val result = parseCommentRichContent("重刷觉有没有[:b鼓掌:][:b鼓掌:] [:068:]")
        assertEquals("重刷觉有没有【表情：鼓掌】【表情：鼓掌】 【表情：068】", result.text)
        assertEquals(emptyList<String>(), result.imageUrls)
    }

'''
if marker not in t:
    raise SystemExit('test insertion marker not found')
if 'tencent transformed jpg urls become rich images' not in t:
    t = t.replace(marker, cases + marker, 1)
tests.write_text(t, encoding='utf-8')

gradle = root / 'app/build.gradle.kts'
g = gradle.read_text(encoding='utf-8')
if 'versionCode = 85' not in g or 'versionName = "1.14.5"' not in g:
    raise SystemExit('MX 1.14.5 baseline not found')
g = g.replace('versionCode = 85', 'versionCode = 86', 1)
g = g.replace('versionName = "1.14.5"', 'versionName = "1.14.6"', 1)
gradle.write_text(g, encoding='utf-8')

docs = root / 'docs/COMMENTS.md'
d = docs.read_text(encoding='utf-8')
section = '''

## Tencent rich-media compatibility (1.14.6)

- Treat Tencent `manhua.acimg.cn` assets as image CDN content.
- Recognize image URLs whose extension is followed by CDN transform path segments, e.g. `.jpg/0?tp=sharp`.
- Convert unresolved legacy `[:token:]` comment emoji markup to readable fallback text instead of exposing raw protocol syntax.
- Tencent-specific exact emoji semantics remain the responsibility of the Tencent extension; the app fallback is intentionally generic.
'''
if '## Tencent rich-media compatibility (1.14.6)' not in d:
    d += section
docs.write_text(d, encoding='utf-8')
