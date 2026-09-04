from pathlib import Path
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "source")


def read_lines(path: str) -> list[str]:
    return (ROOT / path).read_text().splitlines()


def write_lines(path: str, lines: list[str]) -> None:
    (ROOT / path).write_text("\n".join(lines) + "\n")


reader = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt"
lines = read_lines(reader)
starts = [
    i
    for i, line in enumerate(lines)
    if line.strip() == "val targetChapter = findReplacementChapter("
    and any("offset = chapterOffset," in candidate for candidate in lines[i : i + 16])
]
if len(starts) != 1:
    raise SystemExit(f"Reader whole-binding target block: expected 1 start, found {len(starts)}")
start = starts[0]
end = next(
    (
        i
        for i in range(start, min(start + 40, len(lines)))
        if "reader_replace_content_loader_unavailable" in lines[i]
    ),
    None,
)
if end is None:
    raise SystemExit("Reader whole-binding target block: loader end not found")

indent = lines[start][: len(lines[start]) - len(lines[start].lstrip())]
block = lines[start : end + 1]
lines[start : end + 1] = [
    indent + "val replaceCurrentChapter = isWholeMangaReplacementEligible(currentChapter.chapter.name)",
    indent + "if (replaceCurrentChapter) {",
    *[(indent + "    " + line[len(indent) :]) for line in block],
    indent + "}",
]

binding_save = next(
    (
        i
        for i in range(start, min(start + 100, len(lines)))
        if lines[i].strip() == "val binding = getChapterContentBinding(manga)"
    ),
    None,
)
if binding_save is None:
    raise SystemExit("Reader whole-binding saved binding state not found")
state_end = min(binding_save + 45, len(lines))
active_id = [
    i
    for i in range(binding_save, state_end)
    if lines[i].strip() == "activeChapterContentSourceId = selection.source.id,"
]
active_name = [
    i
    for i in range(binding_save, state_end)
    if lines[i].strip() == "activeChapterContentSourceName = selection.source.name,"
]
if len(active_id) != 1 or len(active_name) != 1:
    raise SystemExit(f"Reader whole-binding active state mismatch: id={len(active_id)} name={len(active_name)}")
i = active_id[0]
prefix = lines[i][: len(lines[i]) - len(lines[i].lstrip())]
lines[i] = prefix + "activeChapterContentSourceId = selection.source.id.takeIf { replaceCurrentChapter },"
i = active_name[0]
prefix = lines[i][: len(lines[i]) - len(lines[i].lstrip())]
lines[i] = prefix + "activeChapterContentSourceName = selection.source.name.takeIf { replaceCurrentChapter },"

load_body = next((i for i, line in enumerate(lines) if line.strip() == "private suspend fun loadChapterBody("), None)
if load_body is None:
    raise SystemExit("Reader loadChapterBody not found")
binding_matches = [
    i
    for i in range(load_body, min(load_body + 120, len(lines)))
    if lines[i].strip() == "val binding = manga?.let(::getChapterContentBinding)"
]
if len(binding_matches) != 1:
    raise SystemExit(f"Reader load binding mismatch: {len(binding_matches)}")
binding_line = binding_matches[0]
if lines[binding_line + 1].strip() != "if (binding == null) {":
    raise SystemExit("Reader binding guard has unexpected shape")
prefix = lines[binding_line + 1][: len(lines[binding_line + 1]) - len(lines[binding_line + 1].lstrip())]
lines[binding_line + 1] = prefix + "if (binding == null || !isWholeMangaReplacementEligible(chapter.chapter.name)) {"
write_lines(reader, lines)

matcher = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ChapterContentMatcher.kt"
lines = read_lines(matcher)
points = [i for i, line in enumerate(lines) if line.startswith("private fun normalizeChapterMainTitle(")]
if len(points) != 1:
    raise SystemExit(f"Matcher insertion point mismatch: {len(points)}")
i = points[0]
lines[i:i] = [
    "/**",
    " * Whole-manga replacement is entitlement-aware: only chapters explicitly marked as still",
    " * requiring purchase may be substituted automatically. Free and account-unlocked chapters",
    " * remain on the original source. Persisted single-chapter replacement is handled separately.",
    " */",
    "internal fun isWholeMangaReplacementEligible(chapterName: String): Boolean {",
    '    return chapterName.trimStart().startsWith("🔒")',
    "}",
    "",
]
write_lines(matcher, lines)

manga = "app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt"
lines = read_lines(manga)
imports = [i for i, line in enumerate(lines) if line == "import eu.kanade.tachiyomi.ui.reader.findBestReplacementChapter"]
if len(imports) != 1:
    raise SystemExit(f"MangaScreen import mismatch: {len(imports)}")
lines.insert(imports[0] + 1, "import eu.kanade.tachiyomi.ui.reader.isWholeMangaReplacementEligible")
markers = [i for i, line in enumerate(lines) if line.strip() == "isChapterContentReplaced = { chapter ->"]
if len(markers) != 1:
    raise SystemExit(f"MangaScreen replacement marker block mismatch: {len(markers)}")
i = markers[0]
if lines[i + 1].strip() != "hasWholeMangaContentBinding || chapter.id.toString() in replacedChapterIds" or lines[i + 2].strip() != "},":
    raise SystemExit("MangaScreen replacement marker block has unexpected shape")
prefix = lines[i + 1][: len(lines[i + 1]) - len(lines[i + 1].lstrip())]
lines[i + 1 : i + 2] = [
    prefix + "val directReplacement = chapter.id.toString() in replacedChapterIds",
    prefix + "val wholeMangaReplacement = hasWholeMangaContentBinding &&",
    prefix + "    isWholeMangaReplacementEligible(chapter.name)",
    prefix + "directReplacement || wholeMangaReplacement",
]
write_lines(manga, lines)

item = "app/src/main/java/eu/kanade/presentation/manga/components/MangaChapterListItem.kt"
lines = read_lines(item)
whens = [
    i
    for i, line in enumerate(lines)
    if line.strip() == "text = when {"
    and i + 4 < len(lines)
    and "contentReplaced && title.trimStart().startsWith" in lines[i + 1]
]
if len(whens) != 1:
    raise SystemExit(f"Chapter item title block mismatch: {len(whens)}")
i = whens[0]
prefix = lines[i][: len(lines[i]) - len(lines[i].lstrip())]
lines[i : i + 5] = [prefix + "text = chapterContentDisplayTitle(title, contentReplaced),"]
points = [i for i, line in enumerate(lines) if line.startswith("internal fun getSwipeAction(")]
if len(points) != 1:
    raise SystemExit(f"Chapter item helper insertion mismatch: {len(points)}")
i = points[0]
lines[i:i] = [
    "internal fun chapterContentDisplayTitle(title: String, contentReplaced: Boolean): String {",
    "    if (!contentReplaced) return title",
    "",
    "    val leadingWhitespace = title.takeWhile(Char::isWhitespace)",
    "    val body = title.drop(leadingWhitespace.length)",
    "    val content = CHAPTER_CONTENT_ACCESS_PREFIXES",
    "        .firstOrNull(body::startsWith)",
    "        ?.let { prefix -> body.removePrefix(prefix).trimStart() }",
    "        ?: body",
    "",
    "    return buildString {",
    "        append(leadingWhitespace)",
    '        append("🔁")',
    "        if (content.isNotBlank()) {",
    "            append(' ')",
    "            append(content)",
    "        }",
    "    }",
    "}",
    "",
    'private val CHAPTER_CONTENT_ACCESS_PREFIXES = listOf("🔒", "✅", "🔓", "🆓")',
    "",
]
write_lines(item, lines)

test = "app/src/test/java/eu/kanade/tachiyomi/ui/reader/ChapterContentMatcherTest.kt"
lines = read_lines(test)
junit = next((i for i, line in enumerate(lines) if line == "import org.junit.jupiter.api.Test"), None)
if junit is None:
    raise SystemExit("Matcher test JUnit import not found")
lines[junit:junit] = [
    "import org.junit.jupiter.api.Assertions.assertFalse",
    "import org.junit.jupiter.api.Assertions.assertTrue",
]
points = [i for i, line in enumerate(lines) if line.strip().startswith("private fun chapter(name: String")]
if len(points) != 1:
    raise SystemExit(f"Matcher test helper mismatch: {len(points)}")
i = points[0]
lines[i:i] = [
    "    @Test",
    "    fun `whole manga replacement only applies to chapters that still require purchase`() {",
    '        assertTrue(isWholeMangaReplacementEligible("🔒 第10话"))',
    '        assertFalse(isWholeMangaReplacementEligible("✅ 第10话"))',
    '        assertFalse(isWholeMangaReplacementEligible("🔓 第10话"))',
    '        assertFalse(isWholeMangaReplacementEligible("第10话"))',
    "    }",
    "",
]
write_lines(test, lines)

display_test = ROOT / "app/src/test/java/eu/kanade/presentation/manga/components/MangaChapterListItemTest.kt"
display_test.parent.mkdir(parents=True, exist_ok=True)
display_test.write_text('''package eu.kanade.presentation.manga.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MangaChapterListItemTest {

    @Test
    fun `replacement marker replaces existing access marker`() {
        assertEquals("🔁 第10话", chapterContentDisplayTitle("🔒 第10话", true))
        assertEquals("🔁 第10话", chapterContentDisplayTitle("✅ 第10话", true))
        assertEquals("🔁 第10话", chapterContentDisplayTitle("🔓 第10话", true))
    }

    @Test
    fun `replacement marker is added to free chapter only for explicit replacement`() {
        assertEquals("🔁 第10话", chapterContentDisplayTitle("第10话", true))
        assertEquals("第10话", chapterContentDisplayTitle("第10话", false))
    }
}
''')

docs = ROOT / "docs/CHAPTER_CONTENT.md"
text = docs.read_text()
old = "- The manga chapter list now treats an active whole-manga content binding as replaced content, so the existing `🔁` marker and highlighted replacement action are visible instead of only marking manually mapped single chapters."
new = "- The manga chapter list now marks only chapters that are actually replaced. Under a whole-manga binding, only original chapters marked `🔒` are shown as `🔁`; free and already-unlocked chapters keep their original status."
if text.count(old) != 1:
    raise SystemExit(f"Docs marker statement mismatch: {text.count(old)}")
text = text.replace(old, new, 1)
text += '''

## Whole-manga entitlement policy

Whole-manga replacement is deliberately narrower than single-chapter replacement:

- `🔒` on the **original source chapter** means the current account still needs to buy/unlock that chapter. An active whole-manga binding may replace it automatically and the chapter list shows `🔁` instead of `🔒`.
- `✅` / `🔓` means the current account already has reading permission. Whole-manga binding must keep using the original source and must not replace it.
- a chapter with no paid-access marker is treated as free/original content and whole-manga binding must keep using the original source.
- an explicit persisted **single-chapter replacement** remains the only way to force replacement of a free or already-unlocked chapter; it has priority over the whole-manga rule.
- if an original source cannot expose a reliable `🔒` state, MX takes the safe path and does not auto-replace that chapter through a whole-manga binding.
- chapter-list replacement markers reflect the chapter that will actually be loaded, not merely the existence of a whole-manga binding.

This policy is host-side and does not bypass source entitlement checks. The original extension remains responsible for accurately marking free, unlocked, and still-paywalled chapters after account state is refreshed.
'''
docs.write_text(text)
