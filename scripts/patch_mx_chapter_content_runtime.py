from pathlib import Path

ROOT = Path(".")
MANGA_SCREEN = ROOT / "app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt"
READER_VM = ROOT / "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt"
MATCHER = ROOT / "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ChapterContentMatcher.kt"
MATCHER_TEST = ROOT / "app/src/test/java/eu/kanade/tachiyomi/ui/reader/ChapterContentMatcherTest.kt"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one match in {path}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_between(path: Path, start: str, end: str, replacement: str) -> None:
    text = path.read_text(encoding="utf-8")
    start_index = text.find(start)
    if start_index < 0:
        raise RuntimeError(f"Start anchor not found in {path}: {start!r}")
    end_index = text.find(end, start_index)
    if end_index < 0:
        raise RuntimeError(f"End anchor not found in {path}: {end!r}")
    if text.find(start, start_index + len(start)) >= 0:
        raise RuntimeError(f"Start anchor is not unique in {path}: {start!r}")
    path.write_text(text[:start_index] + replacement + text[end_index:], encoding="utf-8")


# Keep the marker presentation path tied to persisted, binding-scoped verified state.
replace_once(
    MANGA_SCREEN,
    "import eu.kanade.tachiyomi.ui.reader.isWholeMangaReplacementEligible\n",
    "import eu.kanade.tachiyomi.ui.reader.isWholeMangaReplacementEligible\n"
    "import eu.kanade.tachiyomi.ui.reader.selectBestReplacementMangaCandidate\n",
)

marker_start = "        val hasWholeMangaContentBinding = wholeMangaBindingSourceId >= 0L && wholeMangaBindingUrl.isNotBlank()\n"
marker_end = "        val chapterReplacementSources = remember(successState.source.id) {\n"
marker_replacement = '''        val hasWholeMangaContentBinding = wholeMangaBindingSourceId >= 0L && wholeMangaBindingUrl.isNotBlank()
        val wholeMangaMatchSignature = remember(
            wholeMangaBindingSourceId,
            wholeMangaBindingUrl,
            wholeMangaBindingTitle,
            wholeMangaBindingMemo,
            wholeMangaBindingOffset,
        ) {
            readerPreferences.chapterContentWholeMangaBindingSignature(
                sourceId = wholeMangaBindingSourceId,
                mangaUrl = wholeMangaBindingUrl,
                mangaTitle = wholeMangaBindingTitle,
                mangaMemo = wholeMangaBindingMemo,
                chapterOffset = wholeMangaBindingOffset,
            )
        }
        val persistedWholeMangaMatchSignature by readerPreferences
            .chapterContentWholeMangaMatchSignature(successState.manga.id)
            .changes()
            .collectAsState(
                initial = readerPreferences.chapterContentWholeMangaMatchSignature(successState.manga.id).get(),
            )
        val persistedWholeMangaMatchedChapterIds by readerPreferences
            .chapterContentWholeMangaMatchedChapterIds(successState.manga.id)
            .changes()
            .collectAsState(
                initial = readerPreferences.chapterContentWholeMangaMatchedChapterIds(successState.manga.id).get(),
            )
        val wholeMangaReplacementChapterIds = remember(
            hasWholeMangaContentBinding,
            wholeMangaMatchSignature,
            persistedWholeMangaMatchSignature,
            persistedWholeMangaMatchedChapterIds,
        ) {
            if (hasWholeMangaContentBinding && persistedWholeMangaMatchSignature == wholeMangaMatchSignature) {
                persistedWholeMangaMatchedChapterIds.mapNotNull(String::toLongOrNull).toSet()
            } else {
                emptySet()
            }
        }
        val wholeMangaChapterMatchInputs = remember(successState.chapters) {
            successState.chapters.map { item ->
                Triple(item.chapter.id, item.chapter.name, item.chapter.chapterNumber)
            }
        }
        LaunchedEffect(
            wholeMangaBindingSourceId,
            wholeMangaBindingUrl,
            wholeMangaBindingTitle,
            wholeMangaBindingMemo,
            wholeMangaBindingOffset,
            wholeMangaMatchSignature,
            wholeMangaChapterMatchInputs,
            successState.isRefreshingData,
        ) {
            if (successState.isRefreshingData) return@LaunchedEffect
            if (!hasWholeMangaContentBinding) {
                readerPreferences.clearChapterContentWholeMangaMatchSnapshot(successState.manga.id)
                return@LaunchedEffect
            }

            runCatching {
                withIOContext {
                    val targetSource = sourceManager.get(wholeMangaBindingSourceId) as? HttpSource
                        ?: error("Bound replacement source is unavailable")
                    val targetManga = SManga.create().apply {
                        url = wholeMangaBindingUrl
                        title = wholeMangaBindingTitle.ifBlank { wholeMangaBindingUrl }
                        if (wholeMangaBindingMemo.isNotBlank()) {
                            memo = runCatching {
                                Json.parseToJsonElement(wholeMangaBindingMemo).jsonObject
                            }.getOrDefault(memo)
                        }
                    }
                    val targetChapters = targetSource.getMangaUpdate(
                        manga = targetManga,
                        chapters = emptyList(),
                        fetchDetails = false,
                        fetchChapters = true,
                    ).chapters
                    if (targetChapters.isEmpty()) {
                        error("Bound replacement source returned an empty chapter catalog")
                    }

                    successState.chapters
                        .asSequence()
                        .map { it.chapter }
                        .filter { isWholeMangaReplacementEligible(it.name) }
                        .mapNotNull { chapter ->
                            chapter.id.takeIf {
                                findBestReplacementChapter(
                                    currentName = chapter.name,
                                    currentNumber = chapter.chapterNumber.toFloat(),
                                    candidates = targetChapters,
                                    offset = wholeMangaBindingOffset,
                                ) != null
                            }
                        }
                        .toSet()
                }
            }.onSuccess { verifiedChapterIds ->
                readerPreferences.saveChapterContentWholeMangaMatchSnapshot(
                    mangaId = successState.manga.id,
                    signature = wholeMangaMatchSignature,
                    chapterIds = verifiedChapterIds,
                )
            }.onFailure { error ->
                logcat(LogPriority.WARN, error) {
                    "Failed to refresh MX whole-manga replacement availability; keeping last verified markers"
                }
            }
        }

'''
replace_between(MANGA_SCREEN, marker_start, marker_end, marker_replacement)

# Route both detail-page and reader whole-manga candidate selection through one guarded fuzzy matcher.
old_detail_picker = '''private fun selectBestChapterListReplacementManga(
    candidates: List<SManga>,
    manga: Manga,
): SManga? {
    val normalizedExpected = sequenceOf(manga.title, manga.ogTitle)
        .map(String::trim)
        .filter(String::isNotBlank)
        .map(::normalizeChapterContentMatchText)
        .filter(String::isNotBlank)
        .toSet()

    val exactMatches = candidates.filter {
        normalizeChapterContentMatchText(it.title) in normalizedExpected
    }
    if (exactMatches.size == 1) return exactMatches.single()
    if (exactMatches.size > 1) return null

    val containmentMatches = candidates.filter { candidate ->
        val normalized = normalizeChapterContentMatchText(candidate.title)
        normalizedExpected.any { expected ->
            normalized.isNotBlank() &&
                expected.isNotBlank() &&
                (normalized.contains(expected) || expected.contains(normalized))
        }
    }
    return containmentMatches.singleOrNull()
}
'''
new_detail_picker = '''private fun selectBestChapterListReplacementManga(
    candidates: List<SManga>,
    manga: Manga,
): SManga? = selectBestReplacementMangaCandidate(
    candidates = candidates,
    expectedTitles = sequenceOf(manga.title, manga.ogTitle),
)
'''
replace_once(MANGA_SCREEN, old_detail_picker, new_detail_picker)

old_reader_picker = '''    private fun selectBestReplacementManga(
        candidates: List<SManga>,
        manga: Manga,
    ): SManga? {
        val normalizedExpected = sequenceOf(manga.title, manga.ogTitle)
            .map(String::trim)
            .filter(String::isNotBlank)
            .map(::normalizeMatchText)
            .filter(String::isNotBlank)
            .toSet()

        val exactMatches = candidates.filter {
            normalizeMatchText(it.title) in normalizedExpected
        }
        if (exactMatches.size == 1) return exactMatches.single()
        if (exactMatches.size > 1) return null

        val containmentMatches = candidates.filter { candidate ->
            val normalized = normalizeMatchText(candidate.title)
            normalizedExpected.any { expected ->
                normalized.isNotBlank() &&
                    expected.isNotBlank() &&
                    (normalized.contains(expected) || expected.contains(normalized))
            }
        }
        return containmentMatches.singleOrNull()
    }
'''
new_reader_picker = '''    private fun selectBestReplacementManga(
        candidates: List<SManga>,
        manga: Manga,
    ): SManga? = selectBestReplacementMangaCandidate(
        candidates = candidates,
        expectedTitles = sequenceOf(manga.title, manga.ogTitle),
    )
'''
replace_once(READER_VM, old_reader_picker, new_reader_picker)

# A reader load that really succeeded is stronger evidence than a preflight marker calculation.
# Persist that success immediately so returning to the details screen cannot show a false lock.
old_binding_success = '''            loader.loadReplacementChapter(
                chapter = chapter,
                replacementSource = cache.source,
                replacementChapter = targetChapter,
                page = page,
            )

            ChapterContentLoadResult(
                sourceId = cache.source.id,
                sourceName = cache.source.name,
            )
'''
new_binding_success = '''            loader.loadReplacementChapter(
                chapter = chapter,
                replacementSource = cache.source,
                replacementChapter = targetChapter,
                page = page,
            )

            chapter.chapter.id?.let { chapterId ->
                val signature = readerPreferences.chapterContentWholeMangaBindingSignature(
                    sourceId = binding.sourceId,
                    mangaUrl = binding.mangaUrl,
                    mangaTitle = binding.mangaTitle,
                    mangaMemo = binding.mangaMemo,
                    chapterOffset = binding.chapterOffset,
                )
                readerPreferences.markChapterContentWholeMangaReplacementSucceeded(
                    mangaId = manga.id,
                    signature = signature,
                    chapterId = chapterId,
                )
            }

            ChapterContentLoadResult(
                sourceId = cache.source.id,
                sourceName = cache.source.name,
            )
'''
replace_once(READER_VM, old_binding_success, new_binding_success)

# Initial whole-manga binding loads the current chapter before the binding is persisted. Record the
# already-proven current chapter as soon as the binding exists.
old_initial_binding = '''        saveChapterContentBinding(manga, selection, chapterOffset)
        val binding = getChapterContentBinding(manga)
        mutableState.update {
'''
new_initial_binding = '''        saveChapterContentBinding(manga, selection, chapterOffset)
        val binding = getChapterContentBinding(manga)
        if (replaceCurrentChapter && binding != null) {
            currentChapter.chapter.id?.let { chapterId ->
                val signature = readerPreferences.chapterContentWholeMangaBindingSignature(
                    sourceId = binding.sourceId,
                    mangaUrl = binding.mangaUrl,
                    mangaTitle = binding.mangaTitle,
                    mangaMemo = binding.mangaMemo,
                    chapterOffset = binding.chapterOffset,
                )
                readerPreferences.markChapterContentWholeMangaReplacementSucceeded(
                    mangaId = manga.id,
                    signature = signature,
                    chapterId = chapterId,
                )
            }
        }
        mutableState.update {
'''
replace_once(READER_VM, old_initial_binding, new_initial_binding)

# Add the shared fuzzy manga-title selector next to the existing chapter matcher.
replace_once(
    MATCHER,
    "import eu.kanade.tachiyomi.source.model.SChapter\n",
    "import eu.kanade.tachiyomi.source.model.SChapter\nimport eu.kanade.tachiyomi.source.model.SManga\n",
)

helper_anchor = '''/**
 * Whole-manga replacement is entitlement-aware: only chapters explicitly marked as still
 * requiring purchase may be substituted automatically. Free and account-unlocked chapters
 * remain on the original source. Persisted single-chapter replacement is handled separately.
 */
'''
helper = '''/**
 * Selects the most likely edition of the same manga without requiring title equality.
 *
 * Exact and containment matches remain strongest. A guarded longest-common-subsequence score then
 * accepts common language/edition suffixes and small naming differences. Ambiguous near-ties return
 * null so the UI asks the user to choose instead of silently binding the wrong title.
 */
internal fun selectBestReplacementMangaCandidate(
    candidates: List<SManga>,
    expectedTitles: Sequence<String>,
): SManga? {
    val expected = expectedTitles
        .map(String::trim)
        .filter(String::isNotBlank)
        .map(::normalizeMangaMatchText)
        .filter(String::isNotBlank)
        .distinct()
        .toList()
    if (expected.isEmpty() || candidates.isEmpty()) return null

    data class RankedManga(
        val manga: SManga,
        val score: Double,
        val index: Int,
    )

    val ranked = candidates
        .mapIndexed { index, candidate ->
            val normalized = normalizeMangaMatchText(candidate.title)
            val score = expected.maxOfOrNull { mangaTitleSimilarity(normalized, it) } ?: 0.0
            RankedManga(candidate, score, index)
        }
        .sortedWith(compareByDescending<RankedManga> { it.score }.thenBy { it.index })

    val best = ranked.firstOrNull() ?: return null
    if (best.score < 0.72) return null

    val second = ranked.getOrNull(1)
    if (second != null && best.score < 0.98 && best.score - second.score < 0.04) {
        return null
    }
    return best.manga
}

private fun mangaTitleSimilarity(first: String, second: String): Double {
    if (first.isBlank() || second.isBlank()) return 0.0
    if (first == second) return 1.0

    val shorter = minOf(first.length, second.length)
    val longer = maxOf(first.length, second.length)
    if (shorter <= 2) return 0.0

    if (first.contains(second) || second.contains(first)) {
        val coverage = shorter.toDouble() / longer.toDouble()
        val minimumCoverage = when {
            shorter <= 4 -> 0.75
            shorter <= 6 -> 0.60
            else -> 0.50
        }
        if (coverage >= minimumCoverage) {
            return (0.90 + coverage * 0.08).coerceAtMost(0.98)
        }
    }

    val lcs = longestCommonSubsequenceLength(first, second)
    val similarity = (2.0 * lcs) / (first.length + second.length).toDouble()
    val minimumSimilarity = when {
        shorter <= 4 -> 0.84
        shorter <= 6 -> 0.76
        shorter <= 10 -> 0.72
        else -> 0.68
    }
    return if (similarity >= minimumSimilarity) similarity else 0.0
}

private fun longestCommonSubsequenceLength(first: String, second: String): Int {
    if (first.isEmpty() || second.isEmpty()) return 0

    val previous = IntArray(second.length + 1)
    val current = IntArray(second.length + 1)
    for (left in first) {
        for (index in second.indices) {
            current[index + 1] = if (left == second[index]) {
                previous[index] + 1
            } else {
                maxOf(current[index], previous[index + 1])
            }
        }
        for (index in previous.indices) {
            previous[index] = current[index]
            current[index] = 0
        }
    }
    return previous[second.length]
}

private fun normalizeMangaMatchText(value: String): String {
    return value.lowercase().filter(Char::isLetterOrDigit)
}

'''
replace_once(MATCHER, helper_anchor, helper + helper_anchor)

# Add focused regression tests for relaxed title matching and ambiguity protection.
replace_once(
    MATCHER_TEST,
    "import eu.kanade.tachiyomi.source.model.SChapter\n",
    "import eu.kanade.tachiyomi.source.model.SChapter\nimport eu.kanade.tachiyomi.source.model.SManga\n",
)

test_anchor = '''    @Test
    fun `whole manga replacement only applies to chapters that still require purchase`() {
'''
new_tests = '''    @Test
    fun `manga edition matching accepts a clear version suffix`() {
        val expected = "我独自升级"
        val simplifiedEdition = manga("我独自升级（简体版）", "/simplified")
        val unrelated = manga("全知读者视角", "/other")

        val result = selectBestReplacementMangaCandidate(
            candidates = listOf(unrelated, simplifiedEdition),
            expectedTitles = sequenceOf(expected),
        )

        assertEquals(simplifiedEdition.url, result?.url)
    }

    @Test
    fun `manga edition matching refuses an ambiguous near tie`() {
        val firstEdition = manga("进击的巨人 最终季", "/final")
        val secondEdition = manga("进击的巨人 彩色版", "/color")

        val result = selectBestReplacementMangaCandidate(
            candidates = listOf(firstEdition, secondEdition),
            expectedTitles = sequenceOf("进击的巨人"),
        )

        assertNull(result)
    }

    @Test
    fun `manga edition matching rejects a different title`() {
        val result = selectBestReplacementMangaCandidate(
            candidates = listOf(manga("咒术回战", "/other")),
            expectedTitles = sequenceOf("间谍过家家"),
        )

        assertNull(result)
    }

'''
replace_once(MATCHER_TEST, test_anchor, new_tests + test_anchor)

helper_end = '''    private fun chapter(name: String, number: Float): SChapter = SChapter.create().apply {
        url = "/$name"
        this.name = name
        chapter_number = number
    }
'''
helper_end_new = helper_end + '''
    private fun manga(title: String, url: String): SManga = SManga.create().apply {
        this.title = title
        this.url = url
    }
'''
replace_once(MATCHER_TEST, helper_end, helper_end_new)

# ReaderPreferences was patched in the maintenance branch before this workflow. Refuse to proceed if
# the required binding-scoped snapshot/runtime-success API is not present.
prefs = (ROOT / "app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderPreferences.kt").read_text(encoding="utf-8")
for required in (
    "chapterContentWholeMangaBindingSignature",
    "saveChapterContentWholeMangaMatchSnapshot",
    "markChapterContentWholeMangaReplacementSucceeded",
    "clearChapterContentWholeMangaMatchSnapshot",
):
    if required not in prefs:
        raise RuntimeError(f"Missing expected ReaderPreferences maintenance API: {required}")

print("Applied MX chapter-content runtime/marker/title-matching patch")
