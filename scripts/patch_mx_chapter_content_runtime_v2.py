from pathlib import Path

ROOT = Path(".")
MANGA_SCREEN = ROOT / "app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt"
READER_PREFS = ROOT / "app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderPreferences.kt"
BUILD_GRADLE = ROOT / "app/build.gradle.kts"
DOC = ROOT / "docs/CHAPTER_CONTENT.md"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one match in {path}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


old_prefs = '''    fun chapterContentWholeMangaMatchedChapterIds(mangaId: Long) = preferenceStore.getStringSet(
        Preference.appStateKey("mx_reader_content_whole_match_ids_$mangaId"),
        emptySet(),
    )

    fun chapterContentWholeMangaBindingSignature(
'''
new_prefs = '''    fun chapterContentWholeMangaMatchedChapterIds(mangaId: Long) = preferenceStore.getStringSet(
        Preference.appStateKey("mx_reader_content_whole_match_ids_$mangaId"),
        emptySet(),
    )

    fun chapterContentWholeMangaRuntimeSignature(mangaId: Long) = preferenceStore.getString(
        Preference.appStateKey("mx_reader_content_whole_runtime_signature_$mangaId"),
        "",
    )

    fun chapterContentWholeMangaRuntimeChapterIds(mangaId: Long) = preferenceStore.getStringSet(
        Preference.appStateKey("mx_reader_content_whole_runtime_ids_$mangaId"),
        emptySet(),
    )

    fun chapterContentWholeMangaBindingSignature(
'''
replace_once(READER_PREFS, old_prefs, new_prefs)

old_mark = '''    fun markChapterContentWholeMangaReplacementSucceeded(
        mangaId: Long,
        signature: String,
        chapterId: Long,
    ) {
        val currentSignature = chapterContentWholeMangaMatchSignature(mangaId).get()
        val currentIds = if (currentSignature == signature) {
            chapterContentWholeMangaMatchedChapterIds(mangaId).get().mapNotNull(String::toLongOrNull).toSet()
        } else {
            emptySet()
        }
        saveChapterContentWholeMangaMatchSnapshot(mangaId, signature, currentIds + chapterId)
    }

    fun clearChapterContentWholeMangaMatchSnapshot(mangaId: Long) {
        chapterContentWholeMangaMatchSignature(mangaId).delete()
        chapterContentWholeMangaMatchedChapterIds(mangaId).delete()
    }
'''
new_mark = '''    fun markChapterContentWholeMangaReplacementSucceeded(
        mangaId: Long,
        signature: String,
        chapterId: Long,
    ) {
        val currentSignature = chapterContentWholeMangaRuntimeSignature(mangaId).get()
        val currentIds = if (currentSignature == signature) {
            chapterContentWholeMangaRuntimeChapterIds(mangaId).get().mapNotNull(String::toLongOrNull).toSet()
        } else {
            emptySet()
        }
        chapterContentWholeMangaRuntimeChapterIds(mangaId).set((currentIds + chapterId).map(Long::toString).toSet())
        chapterContentWholeMangaRuntimeSignature(mangaId).set(signature)
    }

    fun clearChapterContentWholeMangaMatchSnapshot(mangaId: Long) {
        chapterContentWholeMangaMatchSignature(mangaId).delete()
        chapterContentWholeMangaMatchedChapterIds(mangaId).delete()
        chapterContentWholeMangaRuntimeSignature(mangaId).delete()
        chapterContentWholeMangaRuntimeChapterIds(mangaId).delete()
    }
'''
replace_once(READER_PREFS, old_mark, new_mark)

old_ui = '''        val persistedWholeMangaMatchedChapterIds by readerPreferences
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
'''
new_ui = '''        val persistedWholeMangaMatchedChapterIds by readerPreferences
            .chapterContentWholeMangaMatchedChapterIds(successState.manga.id)
            .changes()
            .collectAsState(
                initial = readerPreferences.chapterContentWholeMangaMatchedChapterIds(successState.manga.id).get(),
            )
        val runtimeWholeMangaMatchSignature by readerPreferences
            .chapterContentWholeMangaRuntimeSignature(successState.manga.id)
            .changes()
            .collectAsState(
                initial = readerPreferences.chapterContentWholeMangaRuntimeSignature(successState.manga.id).get(),
            )
        val runtimeWholeMangaMatchedChapterIds by readerPreferences
            .chapterContentWholeMangaRuntimeChapterIds(successState.manga.id)
            .changes()
            .collectAsState(
                initial = readerPreferences.chapterContentWholeMangaRuntimeChapterIds(successState.manga.id).get(),
            )
        val wholeMangaReplacementChapterIds = remember(
            hasWholeMangaContentBinding,
            wholeMangaMatchSignature,
            persistedWholeMangaMatchSignature,
            persistedWholeMangaMatchedChapterIds,
            runtimeWholeMangaMatchSignature,
            runtimeWholeMangaMatchedChapterIds,
        ) {
            if (!hasWholeMangaContentBinding) {
                emptySet()
            } else {
                val catalogVerified = if (persistedWholeMangaMatchSignature == wholeMangaMatchSignature) {
                    persistedWholeMangaMatchedChapterIds.mapNotNull(String::toLongOrNull).toSet()
                } else {
                    emptySet()
                }
                val runtimeVerified = if (runtimeWholeMangaMatchSignature == wholeMangaMatchSignature) {
                    runtimeWholeMangaMatchedChapterIds.mapNotNull(String::toLongOrNull).toSet()
                } else {
                    emptySet()
                }
                catalogVerified + runtimeVerified
            }
        }
'''
replace_once(MANGA_SCREEN, old_ui, new_ui)

replace_once(
    BUILD_GRADLE,
    '        versionCode = 91\n        versionName = "1.14.11"\n',
    '        versionCode = 94\n        versionName = "1.14.14"\n',
)

append_text = '''

## 2026-09-09 runtime-success marker evidence

Whole-manga replacement marker state now keeps two binding-scoped evidence channels instead of one mutable set:

- catalog preflight verification remains refreshable and may be recalculated from the target chapter catalog;
- a chapter that the reader actually loaded successfully from the replacement source is recorded separately as runtime-verified evidence;
- the manga chapter list displays the union of catalog-verified and runtime-verified chapters for the current binding signature;
- refreshing or recalculating the target catalog can no longer overwrite a reader-proven success and make a readable replaced chapter fall back to `🔒`;
- changing or removing the whole-manga binding clears both evidence channels, so runtime evidence cannot leak across editions or sources.

This gives actual reader success higher authority than a speculative/preflight marker calculation while preserving binding isolation.
'''
text = DOC.read_text(encoding="utf-8")
if "## 2026-09-09 runtime-success marker evidence" not in text:
    DOC.write_text(text.rstrip() + append_text.rstrip() + "\n", encoding="utf-8")
