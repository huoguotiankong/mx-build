from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "source")
module = root / "src/zh/copymangaplus"
kt = module / "src/eu/kanade/tachiyomi/extension/zh/copymangaplus/CopyMangaPlus.kt"
gradle = module / "build.gradle.kts"
doc = root / "docs/sources/copymangaplus.md"

s = kt.read_text("utf-8")


def once(old: str, new: str, label: str) -> None:
    global s
    if new in s:
        return
    if old not in s:
        raise SystemExit(f"missing anchor: {label}")
    s = s.replace(old, new, 1)


once(
    "import androidx.preference.PreferenceScreen\n",
    "import androidx.preference.PreferenceScreen\nimport androidx.preference.SwitchPreferenceCompat\n",
    "SwitchPreferenceCompat import",
)
once(
    "import okhttp3.Request\n",
    "import okhttp3.Request\nimport openccjava.OpenCC\n",
    "OpenCC import",
)

replacements = [
    ('title = o.string("name").orEmpty()', 'title = simplify(o.string("name")).orEmpty()'),
    ('description = o.string("brief")', 'description = simplify(o.string("brief"))'),
    ('title = comic.string("name").orEmpty()', 'title = simplify(comic.string("name")).orEmpty()'),
    (
        'comic.string("brief")?.takeIf(String::isNotBlank)?.let(::append)',
        'simplify(comic.string("brief"))?.takeIf(String::isNotBlank)?.let(::append)',
    ),
    (
        'comic.string("alias")?.takeIf(String::isNotBlank)?.let {',
        'simplify(comic.string("alias"))?.takeIf(String::isNotBlank)?.let {',
    ),
    (
        'val groupName = group?.string("name").orEmpty()',
        'val groupName = simplify(group?.string("name")).orEmpty()',
    ),
    (
        'val rawName = o.string("name").orEmpty().ifBlank { "章节" }',
        'val rawName = simplify(o.string("name")).orEmpty().ifBlank { "章节" }',
    ),
    (
        'comic.obj("last_chapter")?.string("name")?.takeIf(String::isNotBlank)?.let {',
        'simplify(comic.obj("last_chapter")?.string("name"))?.takeIf(String::isNotBlank)?.let {',
    ),
    (
        'val authorName = o.string("user_name") ?: user?.string("nickname") ?: user?.string("username") ?: "拷贝用户"',
        'val authorName = simplify(o.string("user_name") ?: user?.string("nickname") ?: user?.string("username") ?: "拷贝用户").orEmpty()',
    ),
    (
        'content = o.string("comment") ?: o.string("content") ?: ""',
        'content = simplify(o.string("comment") ?: o.string("content")).orEmpty()',
    ),
    (
        'val content = o.string("comment") ?: o.string("roast") ?: return null',
        'val content = simplify(o.string("comment") ?: o.string("roast")) ?: return null',
    ),
    (
        'val name = results.string("nickname") ?: results.string("username") ?: return null',
        'val name = simplify(results.string("nickname") ?: results.string("username")) ?: return null',
    ),
]
for old, new in replacements:
    if new in s:
        continue
    if old not in s:
        raise SystemExit(f"missing replacement anchor: {old}")
    s = s.replace(old, new, 1)

if 'val originalName = obj.string("name")' not in s:
    start = s.index("    private fun objectNames(")
    end = s.index("\n    private fun statusOf", start)
    block = '''    private fun objectNames(array: JsonArray?, cache: ConcurrentHashMap<String, String>): List<String> {
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
'''
    s = s[:start] + block + s[end:]

old_display = '    private fun displayObject(o: JsonObject?): String? = o?.string("display") ?: o?.string("name") ?: o?.string("value")'
new_display = '    private fun displayObject(o: JsonObject?): String? = simplify(o?.string("display") ?: o?.string("name") ?: o?.string("value"))'
if new_display not in s:
    if old_display not in s:
        raise SystemExit("missing displayObject anchor")
    s = s.replace(old_display, new_display, 1)

if "private fun simplify(text: String?)" not in s:
    anchor = "    private fun normalizeHost(value: String) ="
    helper = '''    private fun simplify(text: String?): String? {
        if (text.isNullOrBlank() || !preferences.getBoolean(PREF_TRAD_TO_SIMP, true)) return text
        return runCatching { OpenCC.convert(text, "t2s") }.getOrElse { text }
    }

'''
    if anchor not in s:
        raise SystemExit("missing simplify helper anchor")
    s = s.replace(anchor, helper + anchor, 1)

if "key = PREF_TRAD_TO_SIMP" not in s:
    anchor = "        EditTextPreference(screen.context).apply {\n            key = PREF_USERNAME"
    pref = '''        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_TRAD_TO_SIMP
            title = "繁体转简体"
            summary = "将标题、作者、标签、简介、章节名和评论文本转换为简体中文"
            setDefaultValue(true)
        }.also(screen::addPreference)

'''
    if anchor not in s:
        raise SystemExit("missing preference anchor")
    s = s.replace(anchor, pref + anchor, 1)

if 'private const val PREF_TRAD_TO_SIMP = "traditional_to_simplified"' not in s:
    anchor = '        private const val PREF_QUALITY = "quality"\n'
    if anchor not in s:
        raise SystemExit("missing preference constant anchor")
    s = s.replace(
        anchor,
        anchor + '        private const val PREF_TRAD_TO_SIMP = "traditional_to_simplified"\n',
        1,
    )

kt.write_text(s, "utf-8")

g = gradle.read_text("utf-8")
if 'implementation("io.github.laisuk:openccjava:1.4.2")' not in g:
    anchor = "\nandroid {\n"
    dep = '\ndependencies {\n    implementation("io.github.laisuk:openccjava:1.4.2")\n}\n'
    if anchor not in g:
        raise SystemExit("missing Gradle android anchor")
    g = g.replace(anchor, dep + anchor, 1)
    gradle.write_text(g, "utf-8")

d = doc.read_text("utf-8")
if "## 繁体转简体" not in d:
    d += """

## 繁体转简体

v4 增加可开关的繁体转简体功能，默认开启。实现使用 Maven Central 的纯 Java `io.github.laisuk:openccjava:1.4.2`，不依赖 JNI / NDK。转换范围包括漫画标题、作者、标签、简介、别名、章节分组名、章节名、详情字段，以及书评/章评的昵称和评论正文。关闭设置后保留源站原文。

作者与标签点击搜索同时缓存源站原名和简体显示名到同一 `path_word`，避免开启转换后详情页点击作者/标签失效。用户主动发表的评论正文不在发送前强制改写，只转换服务端返回后的展示文本。

此功能只处理文本显示，不修改漫画 URL、章节 UUID、评论 ID、登录参数、图片地址或线路协议。
"""
    doc.write_text(d, "utf-8")

print("CopyManga Plus OpenCC v4 patch applied")
