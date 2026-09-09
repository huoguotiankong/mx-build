from pathlib import Path

ROOT = Path(".")
MANGA_SCREEN = ROOT / "app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt"
READER_VM = ROOT / "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt"
MATCHER_TEST = ROOT / "app/src/test/java/eu/kanade/tachiyomi/ui/reader/ChapterContentMatcherTest.kt"
BUILD_GRADLE = ROOT / "app/build.gradle.kts"
DOC = ROOT / "docs/CHAPTER_CONTENT.md"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one match in {path}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# 1) Manga-detail preflight: recover the exact bound SManga from source search when a reconstructed
# persisted SManga cannot provide a chapter catalog. Some extensions keep transient fields outside
# url/title/memo; the reader can still work from its in-memory selection while detail preflight fails.
old_detail_catalog = '''                    val targetChapters = targetSource.getMangaUpdate(
                        manga = targetManga,
                        chapters = emptyList(),
                        fetchDetails = false,
                        fetchChapters = true,
                    ).chapters
                    if (targetChapters.isEmpty()) {
                        error("Bound replacement source returned an empty chapter catalog")
                    }
'''
new_detail_catalog = '''                    val directTargetChapters = runCatching {
                        targetSource.getMangaUpdate(
                            manga = targetManga,
                            chapters = emptyList(),
                            fetchDetails = false,
                            fetchChapters = true,
                        ).chapters
                    }.getOrDefault(emptyList())
                    val targetChapters = if (directTargetChapters.isNotEmpty()) {
                        directTargetChapters
                    } else {
                        val recoveryQueries = sequenceOf(
                            wholeMangaBindingTitle,
                            successState.manga.title,
                            successState.manga.ogTitle,
                        )
                            .map(String::trim)
                            .filter(String::isNotBlank)
                            .distinct()
                            .toList()
                        val recoveredCandidates = linkedMapOf<String, SManga>()
                        for (query in recoveryQueries) {
                            var pageNumber = 1
                            var hasNext = true
                            while (hasNext && pageNumber <= 2) {
                                val page = targetSource.getSearchManga(
                                    pageNumber,
                                    query,
                                    targetSource.getFilterList(),
                                )
                                page.mangas.forEach { candidate ->
                                    recoveredCandidates.putIfAbsent(candidate.url, candidate)
                                }
                                hasNext = page.hasNextPage
                                pageNumber++
                            }
                        }
                        val recoveredManga = recoveredCandidates[wholeMangaBindingUrl]
                            ?: selectBestReplacementMangaCandidate(
                                candidates = recoveredCandidates.values.toList(),
                                expectedTitles = sequenceOf(
                                    wholeMangaBindingTitle,
                                    successState.manga.title,
                                    successState.manga.ogTitle,
                                ),
                            )
                        recoveredManga?.let { manga ->
                            targetSource.getMangaUpdate(
                                manga = manga,
                                chapters = emptyList(),
                                fetchDetails = false,
                                fetchChapters = true,
                            ).chapters
                        }.orEmpty()
                    }
                    if (targetChapters.isEmpty()) {
                        error("Bound replacement source returned an empty chapter catalog")
                    }
'''
replace_once(MANGA_SCREEN, old_detail_catalog, new_detail_catalog)

# 2) Reader runtime: remember whether the full original chapter list has already been evaluated
# against the current target catalog during this reader session.
old_runtime_fields = '''    private var chapterContentBindingCache: ChapterContentBindingCache? = null
    private var pendingChapterContentSelection: ReplacementSelection? = null
'''
new_runtime_fields = '''    private var chapterContentBindingCache: ChapterContentBindingCache? = null
    private var chapterContentCatalogSnapshotSignature: String? = null
    private var pendingChapterContentSelection: ReplacementSelection? = null
'''
replace_once(READER_VM, old_runtime_fields, new_runtime_fields)

# 3) As soon as a whole-manga binding is created, calculate markers for every original chapter from
# the already-loaded target catalog. This avoids requiring each chapter to be opened once.
old_binding_snapshot = '''        saveChapterContentBinding(manga, selection, chapterOffset)
        val binding = getChapterContentBinding(manga)
        if (replaceCurrentChapter && binding != null) {
'''
new_binding_snapshot = '''        saveChapterContentBinding(manga, selection, chapterOffset)
        val binding = getChapterContentBinding(manga)
        if (binding != null) {
            runCatching {
                refreshWholeMangaMatchSnapshotFromCatalog(
                    manga = manga,
                    binding = binding,
                    targetChapters = selection.chapters,
                    force = true,
                )
            }.onFailure { error ->
                logcat(LogPriority.WARN, error) {
                    "Failed to precompute MX whole-manga markers from selected target catalog"
                }
            }
        }
        if (replaceCurrentChapter && binding != null) {
'''
replace_once(READER_VM, old_binding_snapshot, new_binding_snapshot)

# 4) Existing bindings (including users upgrading from v94) self-heal on the first reader load by
# evaluating all database chapters against the same catalog the reader is actually using.
old_load_cache = '''            val cache = getChapterContentBindingCache(binding)
            val targetChapter = findReplacementChapter(
'''
new_load_cache = '''            val cache = getChapterContentBindingCache(binding)
            runCatching {
                refreshWholeMangaMatchSnapshotFromCatalog(
                    manga = manga,
                    binding = binding,
                    targetChapters = cache.chapters,
                )
            }.onFailure { snapshotError ->
                logcat(LogPriority.WARN, snapshotError) {
                    "Failed to refresh MX whole-manga marker snapshot from reader catalog"
                }
            }
            val targetChapter = findReplacementChapter(
'''
replace_once(READER_VM, old_load_cache, new_load_cache)

# 5) Binding cache reconstruction gets the same exact-url search recovery as manga-detail preflight,
# so replacement remains reliable after process/app restart even for extensions with transient manga fields.
old_cache_chapters = '''        val chapters = getReplacementChapters(source, targetManga)
        return ChapterContentBindingCache(
'''
new_cache_chapters = '''        val directChapters = runCatching {
            getReplacementChapters(source, targetManga)
        }.getOrDefault(emptyList())
        val chapters = if (directChapters.isNotEmpty()) {
            directChapters
        } else {
            val originalManga = manga
            val recoveryQueries = sequenceOf(
                binding.mangaTitle,
                originalManga?.title.orEmpty(),
                originalManga?.ogTitle.orEmpty(),
            )
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .toList()
            val recoveredCandidates = linkedMapOf<String, SManga>()
            for (query in recoveryQueries) {
                var pageNumber = 1
                var hasNext = true
                while (hasNext && pageNumber <= 2) {
                    val page = source.getSearchManga(pageNumber, query, source.getFilterList())
                    page.mangas.forEach { candidate ->
                        recoveredCandidates.putIfAbsent(candidate.url, candidate)
                    }
                    hasNext = page.hasNextPage
                    pageNumber++
                }
            }
            val recoveredManga = recoveredCandidates[binding.mangaUrl]
                ?: selectBestReplacementMangaCandidate(
                    candidates = recoveredCandidates.values.toList(),
                    expectedTitles = sequenceOf(
                        binding.mangaTitle,
                        originalManga?.title.orEmpty(),
                        originalManga?.ogTitle.orEmpty(),
                    ),
                )
            recoveredManga?.let { getReplacementChapters(source, it) }.orEmpty()
        }
        if (chapters.isEmpty()) {
            error(Injekt.get<Application>().stringResource(KMR.strings.reader_replace_content_chapter_not_found, binding.mangaTitle))
        }
        return ChapterContentBindingCache(
'''
replace_once(READER_VM, old_cache_chapters, new_cache_chapters)

# 6) Shared runtime precomputation helper. It writes only the catalog/preflight channel; per-chapter
# runtime successes remain separately preserved by the v94 fix and are unioned in MangaScreen.
helper_anchor = '''    private fun getPreferredReplacementManga(
        mangaId: Long,
        sourceId: Long,
    ): SManga? {
'''
helper = '''    private suspend fun refreshWholeMangaMatchSnapshotFromCatalog(
        manga: Manga,
        binding: ChapterContentBinding,
        targetChapters: List<SChapter>,
        force: Boolean = false,
    ) {
        if (targetChapters.isEmpty()) return
        val signature = readerPreferences.chapterContentWholeMangaBindingSignature(
            sourceId = binding.sourceId,
            mangaUrl = binding.mangaUrl,
            mangaTitle = binding.mangaTitle,
            mangaMemo = binding.mangaMemo,
            chapterOffset = binding.chapterOffset,
        )
        if (!force && chapterContentCatalogSnapshotSignature == signature) return

        val verifiedChapterIds = getChaptersByMangaId.await(manga.id, applyFilter = false)
            .asSequence()
            .filter { isWholeMangaReplacementEligible(it.name) }
            .mapNotNull { chapter ->
                chapter.id.takeIf {
                    findReplacementChapter(
                        currentName = chapter.name,
                        currentNumber = chapter.chapterNumber.toFloat(),
                        candidates = targetChapters,
                        offset = binding.chapterOffset,
                    ) != null
                }
            }
            .toSet()
        readerPreferences.saveChapterContentWholeMangaMatchSnapshot(
            mangaId = manga.id,
            signature = signature,
            chapterIds = verifiedChapterIds,
        )
        chapterContentCatalogSnapshotSignature = signature
    }

'''
replace_once(READER_VM, helper_anchor, helper + helper_anchor)

# Reset session precomputation whenever a new binding is saved.
old_save_cache = '''        chapterContentBindingCache = ChapterContentBindingCache(
            binding = ChapterContentBinding(
'''
new_save_cache = '''        chapterContentCatalogSnapshotSignature = null
        chapterContentBindingCache = ChapterContentBindingCache(
            binding = ChapterContentBinding(
'''
replace_once(READER_VM, old_save_cache, new_save_cache)

# Focused matcher regression using the numbering/title shape reported by device testing.
test_anchor = '''    @Test
    fun `manga edition matching accepts a clear version suffix`() {
'''
test_case = '''    @Test
    fun `locked numbered chapter matches exact target number even when descriptive titles differ`() {
        val target = chapter("第1824话 不同标题", 1824f)

        val result = findBestReplacementChapter(
            currentName = "🔒 1824 慕强之人",
            currentNumber = 1824f,
            candidates = listOf(target),
        )

        assertEquals(target.url, result?.url)
    }

'''
replace_once(MATCHER_TEST, test_anchor, test_case + test_anchor)

replace_once(
    BUILD_GRADLE,
    '        versionCode = 94\n        versionName = "1.14.14"\n',
    '        versionCode = 95\n        versionName = "1.14.15"\n',
)

doc_text = DOC.read_text(encoding="utf-8")
heading = "## 2026-09-09 full-catalog marker recovery"
if heading not in doc_text:
    addition = '''

## 2026-09-09 full-catalog marker recovery

Device testing exposed a source-dependent case where the reader could replace a chapter from its in-memory target catalog, while manga-detail preflight reconstructed the persisted target manga and failed to obtain the same complete catalog. The visible result was that only chapters opened in the reader gained `🔁`, while other replaceable chapters remained `🔒`.

The host now closes that gap in two ways:

- when a whole-manga binding is selected, the already-loaded target chapter catalog is immediately matched against every original database chapter and the complete verified marker snapshot is persisted;
- existing bindings self-heal on the first reader load by performing the same all-chapter calculation from the reader's actual target catalog;
- manga-detail preflight and reader cache reconstruction retry by searching the target source and recovering the exact persisted target manga URL when a minimally reconstructed `SManga` cannot return chapters;
- runtime-proven per-chapter success remains a separate evidence channel and cannot be erased by catalog recalculation.

This keeps marker presentation derived from the same catalog that can actually supply reader pages, without requiring every chapter to be opened once.
'''
    DOC.write_text(doc_text.rstrip() + addition + "\n", encoding="utf-8")
