from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] if Path(__file__).name.startswith("patch_") else Path.cwd()


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one match in {path}, found {count}: {old[:120]!r}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_between(path: str, start: str, end: str, new_middle: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    start_pos = text.find(start)
    if start_pos < 0:
        raise SystemExit(f"Start anchor not found in {path}: {start!r}")
    end_pos = text.find(end, start_pos)
    if end_pos < 0:
        raise SystemExit(f"End anchor not found in {path}: {end!r}")
    file.write_text(text[:start_pos] + new_middle + text[end_pos:], encoding="utf-8")


# ---- ReaderViewModel: fuzzy edition selection + successful-load marker confirmation ----
reader = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt"

replace_once(
    reader,
    '''    private fun selectBestReplacementManga(\n        candidates: List<SManga>,\n        manga: Manga,\n    ): SManga? {\n        val normalizedExpected = sequenceOf(manga.title, manga.ogTitle)\n            .map(String::trim)\n            .filter(String::isNotBlank)\n            .map(::normalizeMatchText)\n            .filter(String::isNotBlank)\n            .toSet()\n\n        val exactMatches = candidates.filter {\n            normalizeMatchText(it.title) in normalizedExpected\n        }\n        if (exactMatches.size == 1) return exactMatches.single()\n        if (exactMatches.size > 1) return null\n\n        val containmentMatches = candidates.filter { candidate ->\n            val normalized = normalizeMatchText(candidate.title)\n            normalizedExpected.any { expected ->\n                normalized.isNotBlank() &&\n                    expected.isNotBlank() &&\n                    (normalized.contains(expected) || expected.contains(normalized))\n            }\n        }\n        return containmentMatches.singleOrNull()\n    }\n''',
    '''    private fun selectBestReplacementManga(\n        candidates: List<SManga>,\n        manga: Manga,\n    ): SManga? {\n        val expectedTitles = sequenceOf(manga.title, manga.ogTitle)\n            .map(String::trim)\n            .filter(String::isNotBlank)\n            .distinct()\n            .toList()\n        return selectBestReplacementMangaCandidate(candidates, expectedTitles)\n    }\n''',
)

replace_once(
    reader,
    '''            if (directResult.isSuccess) {\n                return directResult.getOrThrow()\n            }\n''',
    '''            if (directResult.isSuccess) {\n                if (manga != null && chapterId != null) {\n                    ensureDirectReplacementMarker(manga.id, chapterId)\n                }\n                return directResult.getOrThrow()\n            }\n''',
)

replace_once(
    reader,
    '''            loader.loadReplacementChapter(\n                chapter = chapter,\n                replacementSource = cache.source,\n                replacementChapter = targetChapter,\n                page = page,\n            )\n\n            ChapterContentLoadResult(\n''',
    '''            loader.loadReplacementChapter(\n                chapter = chapter,\n                replacementSource = cache.source,\n                replacementChapter = targetChapter,\n                page = page,\n            )\n            if (manga != null && chapterId != null) {\n                confirmWholeMangaReplacementMarker(\n                    mangaId = manga.id,\n                    chapterId = chapterId,\n                    binding = binding,\n                )\n            }\n\n            ChapterContentLoadResult(\n''',
)

replace_once(
    reader,
    '''        saveChapterContentBinding(manga, selection, chapterOffset)\n        val binding = getChapterContentBinding(manga)\n        mutableState.update {\n''',
    '''        saveChapterContentBinding(manga, selection, chapterOffset)\n        val binding = getChapterContentBinding(manga)\n        if (replaceCurrentChapter && binding != null) {\n            currentChapter.chapter.id?.let { chapterId ->\n                confirmWholeMangaReplacementMarker(\n                    mangaId = manga.id,\n                    chapterId = chapterId,\n                    binding = binding,\n                )\n            }\n        }\n        mutableState.update {\n''',
)

reader_helper_anchor = '''    private fun getPreferredReplacementManga(\n        mangaId: Long,\n        sourceId: Long,\n    ): SManga? {\n'''
reader_helpers = '''    private fun ensureDirectReplacementMarker(mangaId: Long, chapterId: Long) {\n        if (readerPreferences.chapterContentReplacement(mangaId, chapterId).get().isBlank()) return\n        val markerPreference = readerPreferences.chapterContentReplacedChapterIds(mangaId)\n        val chapterKey = chapterId.toString()\n        val current = markerPreference.get()\n        if (chapterKey !in current) {\n            markerPreference.set(current + chapterKey)\n        }\n    }\n\n    private fun confirmWholeMangaReplacementMarker(\n        mangaId: Long,\n        chapterId: Long,\n        binding: ChapterContentBinding,\n    ) {\n        val signature = chapterContentBindingSignature(binding)\n        val snapshotSignature = readerPreferences.chapterContentWholeMangaMatchSignature(mangaId).get()\n        val existingIds = if (snapshotSignature == signature) {\n            readerPreferences.chapterContentWholeMangaMatchedChapterIds(mangaId)\n                .get()\n                .mapNotNull(String::toLongOrNull)\n                .toSet()\n        } else {\n            emptySet()\n        }\n        if (chapterId !in existingIds || snapshotSignature != signature) {\n            readerPreferences.saveChapterContentWholeMangaMatchSnapshot(\n                mangaId = mangaId,\n                signature = signature,\n                chapterIds = existingIds + chapterId,\n            )\n        }\n    }\n\n    private fun chapterContentBindingSignature(binding: ChapterContentBinding): String {\n        return listOf(\n            binding.sourceId.toString(),\n            binding.mangaUrl,\n            binding.mangaTitle,\n            binding.mangaMemo,\n            binding.chapterOffset.toString(),\n        ).joinToString("\\u001F")\n    }\n\n'''
replace_once(reader, reader_helper_anchor, reader_helpers + reader_helper_anchor)

# ---- MangaScreen: use persisted mappings as truth and observe the whole-manga snapshot directly ----
manga_screen = "app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt"

replace_once(
    manga_screen,
    '''import eu.kanade.tachiyomi.ui.reader.isWholeMangaReplacementEligible\n''',
    '''import eu.kanade.tachiyomi.ui.reader.isWholeMangaReplacementEligible\nimport eu.kanade.tachiyomi.ui.reader.selectBestReplacementMangaCandidate\n''',
)

snapshot_start = '''        var wholeMangaReplacementChapterIds by remember(\n'''
snapshot_end = '''        LaunchedEffect(\n            wholeMangaBindingSourceId,\n'''
snapshot_block = '''        val wholeMangaSnapshotSignature by readerPreferences\n            .chapterContentWholeMangaMatchSignature(successState.manga.id)\n            .changes()\n            .collectAsState(\n                initial = readerPreferences.chapterContentWholeMangaMatchSignature(successState.manga.id).get(),\n            )\n        val wholeMangaSnapshotIds by readerPreferences\n            .chapterContentWholeMangaMatchedChapterIds(successState.manga.id)\n            .changes()\n            .collectAsState(\n                initial = readerPreferences.chapterContentWholeMangaMatchedChapterIds(successState.manga.id).get(),\n            )\n        val wholeMangaReplacementChapterIds = remember(\n            hasWholeMangaContentBinding,\n            wholeMangaMatchSignature,\n            wholeMangaSnapshotSignature,\n            wholeMangaSnapshotIds,\n        ) {\n            if (hasWholeMangaContentBinding && wholeMangaSnapshotSignature == wholeMangaMatchSignature) {\n                wholeMangaSnapshotIds.mapNotNull(String::toLongOrNull).toSet()\n            } else {\n                emptySet()\n            }\n        }\n'''
replace_between(manga_screen, snapshot_start, snapshot_end, snapshot_block)

replace_once(
    manga_screen,
    '''            if (!hasWholeMangaContentBinding) {\n                wholeMangaReplacementChapterIds = emptySet()\n                readerPreferences.clearChapterContentWholeMangaMatchSnapshot(successState.manga.id)\n                return@LaunchedEffect\n            }\n''',
    '''            if (!hasWholeMangaContentBinding) {\n                readerPreferences.clearChapterContentWholeMangaMatchSnapshot(successState.manga.id)\n                return@LaunchedEffect\n            }\n''',
)

replace_once(
    manga_screen,
    '''            }.onSuccess { verifiedChapterIds ->\n                wholeMangaReplacementChapterIds = verifiedChapterIds\n                readerPreferences.saveChapterContentWholeMangaMatchSnapshot(\n''',
    '''            }.onSuccess { verifiedChapterIds ->\n                readerPreferences.saveChapterContentWholeMangaMatchSnapshot(\n''',
)

replace_once(
    manga_screen,
    '''            isChapterContentReplaced = { chapter ->\n                val directReplacement = chapter.id.toString() in replacedChapterIds\n                val wholeMangaReplacement = chapter.id in wholeMangaReplacementChapterIds\n                directReplacement || wholeMangaReplacement\n            },\n''',
    '''            isChapterContentReplaced = { chapter ->\n                // Keep the indexed preference collected above as an invalidation signal, but use\n                // the actual persisted chapter mapping as the source of truth. This self-heals\n                // older/stale indexes where the reader can replace content while the list shows 🔒.\n                replacedChapterIds.size\n                val directReplacement = readerPreferences\n                    .chapterContentReplacement(successState.manga.id, chapter.id)\n                    .get()\n                    .isNotBlank()\n                val wholeMangaReplacement = chapter.id in wholeMangaReplacementChapterIds\n                directReplacement || wholeMangaReplacement\n            },\n''',
)

replace_once(
    manga_screen,
    '''                hasReplacement = replacementChapter.id.toString() in replacedChapterIds,\n''',
    '''                hasReplacement = readerPreferences\n                    .chapterContentReplacement(successState.manga.id, replacementChapter.id)\n                    .get()\n                    .isNotBlank(),\n''',
)

replace_once(
    manga_screen,
    '''private fun selectBestChapterListReplacementManga(\n    candidates: List<SManga>,\n    manga: Manga,\n): SManga? {\n    val normalizedExpected = sequenceOf(manga.title, manga.ogTitle)\n        .map(String::trim)\n        .filter(String::isNotBlank)\n        .map(::normalizeChapterContentMatchText)\n        .filter(String::isNotBlank)\n        .toSet()\n\n    val exactMatches = candidates.filter {\n        normalizeChapterContentMatchText(it.title) in normalizedExpected\n    }\n    if (exactMatches.size == 1) return exactMatches.single()\n    if (exactMatches.size > 1) return null\n\n    val containmentMatches = candidates.filter { candidate ->\n        val normalized = normalizeChapterContentMatchText(candidate.title)\n        normalizedExpected.any { expected ->\n            normalized.isNotBlank() &&\n                expected.isNotBlank() &&\n                (normalized.contains(expected) || expected.contains(normalized))\n        }\n    }\n    return containmentMatches.singleOrNull()\n}\n''',
    '''private fun selectBestChapterListReplacementManga(\n    candidates: List<SManga>,\n    manga: Manga,\n): SManga? {\n    val expectedTitles = sequenceOf(manga.title, manga.ogTitle)\n        .map(String::trim)\n        .filter(String::isNotBlank)\n        .distinct()\n        .toList()\n    return selectBestReplacementMangaCandidate(candidates, expectedTitles)\n}\n''',
)

# ---- Version bump ----
replace_once(
    "app/build.gradle.kts",
    '''        versionCode = 93\n        versionName = "1.14.13"\n''',
    '''        versionCode = 94\n        versionName = "1.14.14"\n''',
)

# ---- Maintenance note ----
docs = Path("docs/CHAPTER_CONTENT.md")
doc_text = docs.read_text(encoding="utf-8")
note = '''\n## 1.14.14 replacement-state and edition-matching hardening\n\nThe 1.14.14 maintenance line treats the persisted replacement mapping / verified whole-manga\nsnapshot as the marker source of truth instead of trusting only the auxiliary direct-ID index. A\nsuccessful reader replacement confirms the corresponding marker snapshot, so returning from the\nreader no longer depends on a later background catalog refresh before `🔁` can replace `🔒`.\nTransient catalog failures still preserve the last verified state.\n\nWhole-manga edition selection also no longer requires an exactly identical provider title. The host\nnormalizes titles and accepts strong containment or high-similarity matches (including common\nprovider suffixes and small character differences). Automatic binding is allowed only for a strong,\nclear winner; close/ambiguous editions fall back to the explicit manga picker. Provider/source IDs\nand chapter matching safety rules remain unchanged.\n\nCI/unit verification and Android real-device validation must be recorded separately; this note does\nnot itself upgrade the capability to device-verified.\n'''
if "## 1.14.14 replacement-state and edition-matching hardening" not in doc_text:
    docs.write_text(doc_text.rstrip() + "\n" + note, encoding="utf-8")
else:
    raise SystemExit("1.14.14 maintenance note already present")

print("MX v1.14.14 replacement-state/fuzzy-title patch applied successfully")
