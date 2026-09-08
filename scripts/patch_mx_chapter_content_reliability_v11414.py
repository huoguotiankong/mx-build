#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()


def replace_once(relative: str, old: str, new: str) -> None:
    path = ROOT / relative
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{relative}: expected exactly one anchor, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def write_new(relative: str, content: str) -> None:
    path = ROOT / relative
    if path.exists():
        raise SystemExit(f"{relative}: expected new file, but it already exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def require(relative: str, needle: str) -> None:
    text = (ROOT / relative).read_text(encoding="utf-8")
    if needle not in text:
        raise SystemExit(f"{relative}: required postcondition missing: {needle}")


# The serial-roadmap test build must update existing v93 Preview installs.
replace_once(
    "app/build.gradle.kts",
    '        versionCode = 91\n        versionName = "1.14.11"',
    '        versionCode = 94\n        versionName = "1.14.14"',
)

# Shared manga-edition matching. Automatic selection is fuzzy only when confidence is high;
# ambiguous editions stay in the explicit picker, where the user's URL choice is authoritative.
write_new(
    "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReplacementMangaMatcher.kt",
    r'''package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.source.model.SManga
import java.text.Normalizer
import kotlin.math.max

private const val AUTO_MANGA_MATCH_THRESHOLD = 0.72
private const val AUTO_MANGA_MATCH_MARGIN = 0.06

internal fun buildReplacementMangaSearchQueries(expectedTitles: List<String>): List<String> {
    val queries = linkedSetOf<String>()
    expectedTitles.forEach { value ->
        val title = value.trim()
        if (title.isBlank()) return@forEach
        queries += title
        stripReplacementEditionDecorations(title)
            .trim()
            .takeIf { it.isNotBlank() && it != title }
            ?.let(queries::add)
    }
    return queries.toList()
}

internal fun rankReplacementMangaCandidates(
    candidates: List<SManga>,
    expectedTitles: List<String>,
): List<SManga> {
    return candidates
        .map { candidate -> candidate to replacementMangaTitleScore(candidate.title, expectedTitles) }
        .sortedWith(
            compareByDescending<Pair<SManga, Double>> { it.second }
                .thenBy { it.first.title.lowercase() },
        )
        .map { it.first }
}

internal fun selectBestReplacementMangaCandidate(
    candidates: List<SManga>,
    expectedTitles: List<String>,
): SManga? {
    if (candidates.isEmpty()) return null

    val scored = candidates
        .map { candidate -> candidate to replacementMangaTitleScore(candidate.title, expectedTitles) }
        .sortedByDescending { it.second }
    val best = scored.first()
    if (best.second < AUTO_MANGA_MATCH_THRESHOLD) return null

    val runnerUp = scored.getOrNull(1)
    if (runnerUp != null &&
        runnerUp.second >= AUTO_MANGA_MATCH_THRESHOLD &&
        best.second - runnerUp.second < AUTO_MANGA_MATCH_MARGIN
    ) {
        return null
    }
    return best.first
}

internal fun replacementMangaTitleScore(candidateTitle: String, expectedTitles: List<String>): Double {
    if (candidateTitle.isBlank()) return 0.0
    val candidateVariants = replacementMangaTitleVariants(candidateTitle)
    return expectedTitles
        .asSequence()
        .filter(String::isNotBlank)
        .flatMap { replacementMangaTitleVariants(it).asSequence() }
        .flatMap { expected ->
            candidateVariants.asSequence().map { candidate -> titleSimilarity(expected, candidate) }
        }
        .maxOrNull()
        ?: 0.0
}

private fun replacementMangaTitleVariants(value: String): Set<String> {
    return sequenceOf(value, stripReplacementEditionDecorations(value))
        .map(::normalizeReplacementMangaTitle)
        .filter(String::isNotBlank)
        .toSet()
}

private fun titleSimilarity(first: String, second: String): Double {
    if (first == second) return 1.0
    if (first.isBlank() || second.isBlank()) return 0.0

    val shorter = if (first.length <= second.length) first else second
    val longer = if (first.length > second.length) first else second
    val lengthRatio = shorter.length.toDouble() / longer.length.toDouble()

    if (longer.contains(shorter) && shorter.length >= 4 && lengthRatio >= 0.50) {
        return 0.93 + (0.05 * lengthRatio)
    }

    // Very short names are too collision-prone for fuzzy automatic binding.
    if (shorter.length < 4) return 0.0

    val lcsRatio = longestCommonSubsequenceLength(first, second).toDouble() / longer.length.toDouble()
    val dice = bigramDice(first, second)
    return max(lcsRatio, (lcsRatio * 0.58) + (dice * 0.42))
}

private fun longestCommonSubsequenceLength(first: String, second: String): Int {
    if (first.isEmpty() || second.isEmpty()) return 0
    val previous = IntArray(second.length + 1)
    val current = IntArray(second.length + 1)
    first.forEach { left ->
        for (index in second.indices) {
            current[index + 1] = if (left == second[index]) {
                previous[index] + 1
            } else {
                max(current[index], previous[index + 1])
            }
        }
        current.copyInto(previous)
        current.fill(0)
    }
    return previous[second.length]
}

private fun bigramDice(first: String, second: String): Double {
    if (first.length < 2 || second.length < 2) return 0.0
    val firstCounts = first.windowed(2).groupingBy { it }.eachCount().toMutableMap()
    var intersection = 0
    second.windowed(2).forEach { gram ->
        val count = firstCounts[gram] ?: 0
        if (count > 0) {
            intersection++
            firstCounts[gram] = count - 1
        }
    }
    return (2.0 * intersection) / ((first.length - 1) + (second.length - 1)).toDouble()
}

private fun normalizeReplacementMangaTitle(value: String): String {
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .filter(Char::isLetterOrDigit)
}

private fun stripReplacementEditionDecorations(value: String): String {
    var result = REPLACEMENT_MANGA_BRACKET_REGEX.replace(value) { match ->
        val inner = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()
        if (containsReplacementEditionHint(inner)) " " else match.value
    }
    while (true) {
        val stripped = REPLACEMENT_MANGA_TRAILING_EDITION_REGEX.replace(result, "").trim()
        if (stripped == result.trim()) break
        result = stripped
    }
    return result
}

private fun containsReplacementEditionHint(value: String): Boolean {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase()
    return REPLACEMENT_MANGA_EDITION_HINTS.any(normalized::contains)
}

private val REPLACEMENT_MANGA_EDITION_HINTS = listOf(
    "高清",
    "彩色",
    "全彩",
    "完整版",
    "完结版",
    "完結版",
    "重制",
    "重製",
    "修订",
    "修訂",
    "典藏",
    "单行本",
    "單行本",
    "官方版",
    "汉化版",
    "漢化版",
    "无修",
    "無修",
    "full color",
    "fullcolor",
    "edition",
    "remaster",
    "official edition",
)

private val REPLACEMENT_MANGA_BRACKET_REGEX = Regex(
    """（([^）]*)）|\(([^)]*)\)|【([^】]*)】|\[([^\]]*)\]""",
)
private val REPLACEMENT_MANGA_TRAILING_EDITION_REGEX = Regex(
    """(?i)(?:[\s:_：\-–—·|/]+)?(?:高清(?:版)?|彩色(?:版)?|全彩(?:版)?|完整版|完结版|完結版|重制(?:版)?|重製(?:版)?|修订(?:版)?|修訂(?:版)?|典藏(?:版)?|单行本|單行本|官方版|汉化版|漢化版|无修(?:版)?|無修(?:版)?|full\s*color|edition|remaster(?:ed)?|official\s*edition)\s*$""",
)
''',
)

write_new(
    "app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReplacementMangaMatcherTest.kt",
    r'''package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.source.model.SManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReplacementMangaMatcherTest {

    @Test
    fun `edition suffix remains a strong same-work match`() {
        val target = manga("斗破苍穹（高清版）")

        val result = selectBestReplacementMangaCandidate(
            candidates = listOf(target),
            expectedTitles = listOf("斗破苍穹"),
        )

        assertEquals(target.url, result?.url)
    }

    @Test
    fun `mostly matching title can be selected when confidence is high`() {
        val target = manga("间谍家家酒")

        val result = selectBestReplacementMangaCandidate(
            candidates = listOf(target),
            expectedTitles = listOf("间谍过家家"),
        )

        assertEquals(target.url, result?.url)
    }

    @Test
    fun `unrelated title is not auto selected`() {
        val result = selectBestReplacementMangaCandidate(
            candidates = listOf(manga("斗罗大陆")),
            expectedTitles = listOf("斗破苍穹"),
        )

        assertNull(result)
    }

    @Test
    fun `ambiguous editions stay in manual picker`() {
        val result = selectBestReplacementMangaCandidate(
            candidates = listOf(
                manga("一人之下 彩色版"),
                manga("一人之下 高清版"),
            ),
            expectedTitles = listOf("一人之下"),
        )

        assertNull(result)
    }

    @Test
    fun `search queries include edition-stripped title`() {
        val queries = buildReplacementMangaSearchQueries(listOf("狐妖小红娘（彩色版）"))

        assertTrue("狐妖小红娘（彩色版）" in queries)
        assertTrue("狐妖小红娘" in queries)
    }

    private fun manga(title: String): SManga = SManga.create().apply {
        url = "/$title"
        this.title = title
    }
}
''',
)

# Persist the last verified match set and expose one common binding signature. IDs are written
# before a new signature to avoid briefly exposing old IDs under a new binding identity.
replace_once(
    "app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderPreferences.kt",
    '''    fun chapterContentBindingOffset(mangaId: Long) = preferenceStore.getInt(
        Preference.appStateKey("mx_reader_content_offset_$mangaId"),
        0,
    )

    fun chapterContentPreferredMangaUrl(mangaId: Long, sourceId: Long) = preferenceStore.getString(''',
    '''    fun chapterContentBindingOffset(mangaId: Long) = preferenceStore.getInt(
        Preference.appStateKey("mx_reader_content_offset_$mangaId"),
        0,
    )

    fun chapterContentWholeMangaMatchSignature(mangaId: Long) = preferenceStore.getString(
        Preference.appStateKey("mx_reader_content_whole_match_signature_$mangaId"),
        "",
    )

    fun chapterContentWholeMangaMatchedChapterIds(mangaId: Long) = preferenceStore.getStringSet(
        Preference.appStateKey("mx_reader_content_whole_match_ids_$mangaId"),
        emptySet(),
    )

    fun buildChapterContentWholeMangaMatchSignature(
        sourceId: Long,
        mangaUrl: String,
        mangaTitle: String,
        mangaMemo: String,
        chapterOffset: Int,
    ): String = listOf(
        sourceId.toString(),
        mangaUrl,
        mangaTitle,
        mangaMemo,
        chapterOffset.toString(),
    ).joinToString("\\u001F")

    fun saveChapterContentWholeMangaMatchSnapshot(
        mangaId: Long,
        signature: String,
        chapterIds: Set<Long>,
    ) {
        chapterContentWholeMangaMatchedChapterIds(mangaId).set(chapterIds.map(Long::toString).toSet())
        chapterContentWholeMangaMatchSignature(mangaId).set(signature)
    }

    fun markChapterContentWholeMangaMatch(
        mangaId: Long,
        signature: String,
        chapterId: Long,
    ) {
        val currentSignature = chapterContentWholeMangaMatchSignature(mangaId).get()
        val ids = if (currentSignature == signature) {
            chapterContentWholeMangaMatchedChapterIds(mangaId).get() + chapterId.toString()
        } else {
            setOf(chapterId.toString())
        }
        chapterContentWholeMangaMatchedChapterIds(mangaId).set(ids)
        chapterContentWholeMangaMatchSignature(mangaId).set(signature)
    }

    fun clearChapterContentWholeMangaMatchSnapshot(mangaId: Long) {
        chapterContentWholeMangaMatchedChapterIds(mangaId).delete()
        chapterContentWholeMangaMatchSignature(mangaId).delete()
    }

    fun chapterContentPreferredMangaUrl(mangaId: Long, sourceId: Long) = preferenceStore.getString(''',
)
replace_once(
    "app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderPreferences.kt",
    '''        chapterContentBindingMangaMemo(mangaId).delete()
        chapterContentBindingOffset(mangaId).delete()
    }''',
    '''        chapterContentBindingMangaMemo(mangaId).delete()
        chapterContentBindingOffset(mangaId).delete()
        clearChapterContentWholeMangaMatchSnapshot(mangaId)
    }''',
)

# Manga detail observes the persisted snapshot, so a successful reader replacement can update the
# marker immediately. Catalog refresh only swaps the snapshot after successful recalculation.
replace_once(
    "app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt",
    '''import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.findBestReplacementChapter
''',
    '''import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.buildReplacementMangaSearchQueries
import eu.kanade.tachiyomi.ui.reader.findBestReplacementChapter
import eu.kanade.tachiyomi.ui.reader.rankReplacementMangaCandidates
import eu.kanade.tachiyomi.ui.reader.selectBestReplacementMangaCandidate
''',
)
replace_once(
    "app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt",
    '''        val hasWholeMangaContentBinding = wholeMangaBindingSourceId >= 0L && wholeMangaBindingUrl.isNotBlank()
        val wholeMangaChapterMatchInputs = remember(successState.chapters) {
            successState.chapters.map { item ->
                Triple(item.chapter.id, item.chapter.name, item.chapter.chapterNumber)
            }
        }
        var wholeMangaReplacementChapterIds by remember(successState.manga.id) {
            mutableStateOf<Set<Long>>(emptySet())
        }
        LaunchedEffect(
            wholeMangaBindingSourceId,
            wholeMangaBindingUrl,
            wholeMangaBindingTitle,
            wholeMangaBindingMemo,
            wholeMangaBindingOffset,
            wholeMangaChapterMatchInputs,
            successState.isRefreshingData,
        ) {
            if (successState.isRefreshingData) return@LaunchedEffect
            if (!hasWholeMangaContentBinding) {
                wholeMangaReplacementChapterIds = emptySet()
                return@LaunchedEffect
            }

            wholeMangaReplacementChapterIds = emptySet()
            wholeMangaReplacementChapterIds = runCatching {
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
            }.onFailure { error ->
                logcat(LogPriority.WARN, error) {
                    "Failed to refresh MX whole-manga replacement availability"
                }
            }.getOrDefault(emptySet())
        }
''',
    '''        val hasWholeMangaContentBinding = wholeMangaBindingSourceId >= 0L && wholeMangaBindingUrl.isNotBlank()
        val wholeMangaMatchSignature = remember(
            wholeMangaBindingSourceId,
            wholeMangaBindingUrl,
            wholeMangaBindingTitle,
            wholeMangaBindingMemo,
            wholeMangaBindingOffset,
        ) {
            readerPreferences.buildChapterContentWholeMangaMatchSignature(
                sourceId = wholeMangaBindingSourceId,
                mangaUrl = wholeMangaBindingUrl,
                mangaTitle = wholeMangaBindingTitle,
                mangaMemo = wholeMangaBindingMemo,
                chapterOffset = wholeMangaBindingOffset,
            )
        }
        val storedWholeMangaMatchSignature by readerPreferences
            .chapterContentWholeMangaMatchSignature(successState.manga.id)
            .changes()
            .collectAsState(
                initial = readerPreferences.chapterContentWholeMangaMatchSignature(successState.manga.id).get(),
            )
        val storedWholeMangaMatchedChapterIds by readerPreferences
            .chapterContentWholeMangaMatchedChapterIds(successState.manga.id)
            .changes()
            .collectAsState(
                initial = readerPreferences.chapterContentWholeMangaMatchedChapterIds(successState.manga.id).get(),
            )
        val wholeMangaReplacementChapterIds = if (
            hasWholeMangaContentBinding && storedWholeMangaMatchSignature == wholeMangaMatchSignature
        ) {
            storedWholeMangaMatchedChapterIds.mapNotNull { it.toLongOrNull() }.toSet()
        } else {
            emptySet()
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
''',
)

# Both the reader and manga-detail replacement flows share the same relaxed edition discovery.
replace_once(
    "app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt",
    '''private suspend fun findChapterListReplacementMangaCandidates(
    targetSource: HttpSource,
    manga: Manga,
): List<SManga> {
    val expectedTitles = sequenceOf(manga.title, manga.ogTitle)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .toList()

    val candidates = linkedMapOf<String, SManga>()
    for (query in expectedTitles) {
        var pageNumber = 1
        var hasNext = true
        while (hasNext && pageNumber <= 2) {
            val page = targetSource.getSearchManga(pageNumber, query, targetSource.getFilterList())
            page.mangas.forEach { candidate ->
                candidates.putIfAbsent(candidate.url, candidate)
            }
            hasNext = page.hasNextPage
            pageNumber++
        }
    }
    return candidates.values.toList()
}

private fun selectBestChapterListReplacementManga(
    candidates: List<SManga>,
    manga: Manga,
): SManga? {
    val normalizedExpected = sequenceOf(manga.title, manga.ogTitle)
        .map(String::trim)
        .filter(String::isNotBlank)
        .map(::normalizeChapterListMatchText)
        .filter(String::isNotBlank)
        .toSet()

    val exactMatches = candidates.filter {
        normalizeChapterListMatchText(it.title) in normalizedExpected
    }
    if (exactMatches.size == 1) return exactMatches.single()
    if (exactMatches.size > 1) return null

    val containmentMatches = candidates.filter { candidate ->
        val normalized = normalizeChapterListMatchText(candidate.title)
        normalizedExpected.any { expected ->
            normalized.isNotBlank() &&
                expected.isNotBlank() &&
                (normalized.contains(expected) || expected.contains(normalized))
        }
    }
    return containmentMatches.singleOrNull()
}
''',
    '''private suspend fun findChapterListReplacementMangaCandidates(
    targetSource: HttpSource,
    manga: Manga,
): List<SManga> {
    val expectedTitles = sequenceOf(manga.title, manga.ogTitle)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .toList()

    val candidates = linkedMapOf<String, SManga>()
    for (query in buildReplacementMangaSearchQueries(expectedTitles)) {
        var pageNumber = 1
        var hasNext = true
        while (hasNext && pageNumber <= 2) {
            val page = targetSource.getSearchManga(pageNumber, query, targetSource.getFilterList())
            page.mangas.forEach { candidate ->
                candidates.putIfAbsent(candidate.url, candidate)
            }
            hasNext = page.hasNextPage
            pageNumber++
        }
    }
    return rankReplacementMangaCandidates(candidates.values.toList(), expectedTitles)
}

private fun selectBestChapterListReplacementManga(
    candidates: List<SManga>,
    manga: Manga,
): SManga? {
    val expectedTitles = sequenceOf(manga.title, manga.ogTitle)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .toList()
    return selectBestReplacementMangaCandidate(candidates, expectedTitles)
}
''',
)

replace_once(
    "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt",
    '''    private suspend fun findReplacementMangaCandidates(
        targetSource: HttpSource,
        manga: Manga,
    ): List<SManga> {
        val expectedTitles = sequenceOf(manga.title, manga.ogTitle)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()

        val candidates = linkedMapOf<String, SManga>()
        for (query in expectedTitles) {
            var pageNumber = 1
            var hasNext = true
            while (hasNext && pageNumber <= 2) {
                val page = targetSource.getSearchManga(pageNumber, query, targetSource.getFilterList())
                page.mangas.forEach { candidate ->
                    candidates.putIfAbsent(candidate.url, candidate)
                }
                hasNext = page.hasNextPage
                pageNumber++
            }
        }
        return candidates.values.toList()
    }

    private fun selectBestReplacementManga(
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
''',
    '''    private suspend fun findReplacementMangaCandidates(
        targetSource: HttpSource,
        manga: Manga,
    ): List<SManga> {
        val expectedTitles = sequenceOf(manga.title, manga.ogTitle)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()

        val candidates = linkedMapOf<String, SManga>()
        for (query in buildReplacementMangaSearchQueries(expectedTitles)) {
            var pageNumber = 1
            var hasNext = true
            while (hasNext && pageNumber <= 2) {
                val page = targetSource.getSearchManga(pageNumber, query, targetSource.getFilterList())
                page.mangas.forEach { candidate ->
                    candidates.putIfAbsent(candidate.url, candidate)
                }
                hasNext = page.hasNextPage
                pageNumber++
            }
        }
        return rankReplacementMangaCandidates(candidates.values.toList(), expectedTitles)
    }

    private fun selectBestReplacementManga(
        candidates: List<SManga>,
        manga: Manga,
    ): SManga? {
        val expectedTitles = sequenceOf(manga.title, manga.ogTitle)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        return selectBestReplacementMangaCandidate(candidates, expectedTitles)
    }
''',
)

# Runtime success is stronger evidence than a pre-check. Merge the successfully loaded chapter
# into the binding-scoped snapshot so chapter list UI cannot remain 🔒 for content that just opened.
replace_once(
    "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt",
    '''        saveChapterContentBinding(manga, selection, chapterOffset)
        val binding = getChapterContentBinding(manga)
''',
    '''        saveChapterContentBinding(manga, selection, chapterOffset)
        if (replaceCurrentChapter) {
            currentChapter.chapter.id?.let { chapterId ->
                readerPreferences.markChapterContentWholeMangaMatch(
                    mangaId = manga.id,
                    signature = readerPreferences.buildChapterContentWholeMangaMatchSignature(
                        sourceId = selection.source.id,
                        mangaUrl = selection.manga.url,
                        mangaTitle = selection.manga.title,
                        mangaMemo = selection.manga.memo.toString(),
                        chapterOffset = chapterOffset,
                    ),
                    chapterId = chapterId,
                )
            }
        }
        val binding = getChapterContentBinding(manga)
''',
)
replace_once(
    "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt",
    '''            loader.loadReplacementChapter(
                chapter = chapter,
                replacementSource = cache.source,
                replacementChapter = targetChapter,
                page = page,
            )

            ChapterContentLoadResult(
''',
    '''            loader.loadReplacementChapter(
                chapter = chapter,
                replacementSource = cache.source,
                replacementChapter = targetChapter,
                page = page,
            )
            if (manga != null) {
                chapter.chapter.id?.let { matchedChapterId ->
                    readerPreferences.markChapterContentWholeMangaMatch(
                        mangaId = manga.id,
                        signature = readerPreferences.buildChapterContentWholeMangaMatchSignature(
                            sourceId = binding.sourceId,
                            mangaUrl = binding.mangaUrl,
                            mangaTitle = binding.mangaTitle,
                            mangaMemo = binding.mangaMemo,
                            chapterOffset = binding.chapterOffset,
                        ),
                        chapterId = matchedChapterId,
                    )
                }
            }

            ChapterContentLoadResult(
''',
)
replace_once(
    "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt",
    '''    ) {
        readerPreferences.chapterContentBindingSourceId(manga.id).set(selection.source.id)
''',
    '''    ) {
        val nextSignature = readerPreferences.buildChapterContentWholeMangaMatchSignature(
            sourceId = selection.source.id,
            mangaUrl = selection.manga.url,
            mangaTitle = selection.manga.title,
            mangaMemo = selection.manga.memo.toString(),
            chapterOffset = chapterOffset,
        )
        if (readerPreferences.chapterContentWholeMangaMatchSignature(manga.id).get() != nextSignature) {
            readerPreferences.clearChapterContentWholeMangaMatchSnapshot(manga.id)
        }
        readerPreferences.chapterContentBindingSourceId(manga.id).set(selection.source.id)
''',
)

# Keep the maintenance doc additive so unrelated roadmap wording changes cannot block code patching.
doc_path = ROOT / "docs/CHAPTER_CONTENT.md"
doc = doc_path.read_text(encoding="utf-8")
section = r'''

## 2026-09-08 replacement marker authority and edition matching

- Whole-manga marker refresh is atomic: the last successfully verified binding-scoped match set remains visible while target chapters refresh and is preserved on transient target/network failure.
- A chapter that successfully loads from the bound replacement source is immediately merged into that verified set, so actual reader success is authoritative and the chapter list cannot remain `🔒` for content that just opened successfully.
- The match snapshot is scoped to replacement source, manga URL/title/memo, and chapter offset, and is cleared when the binding changes or is removed.
- Whole-manga edition discovery searches safe edition-stripped title aliases and ranks candidates by normalized title similarity. High-confidence mostly-matching titles may be selected automatically; ambiguous editions intentionally open the manual picker instead of being guessed.
- An explicit user-selected manga URL remains authoritative. Fuzzy confidence controls automatic selection only and never rejects a concrete picker choice.
'''
if "## 2026-09-08 replacement marker authority and edition matching" not in doc:
    doc += section
doc_path.write_text(doc, encoding="utf-8")

require("app/build.gradle.kts", "versionCode = 94")
require("app/build.gradle.kts", 'versionName = "1.14.14"')
require("app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt", "keeping last verified markers")
require("app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt", "markChapterContentWholeMangaMatch")
require("app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReplacementMangaMatcher.kt", "AUTO_MANGA_MATCH_THRESHOLD")
require("app/src/test/java/eu/kanade/tachiyomi/ui/reader/ReplacementMangaMatcherTest.kt", "ambiguous editions stay in manual picker")

print("MX 1.14.14 / v94 chapter replacement reliability patch prepared")
