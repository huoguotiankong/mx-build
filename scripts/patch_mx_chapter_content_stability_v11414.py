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


def require(relative: str, needle: str) -> None:
    path = ROOT / relative
    text = path.read_text(encoding="utf-8")
    if needle not in text:
        raise SystemExit(f"{relative}: required postcondition missing: {needle}")


# The serial-roadmap test build must be able to update legacy Preview installs that have
# already reached versionCode 93.
replace_once(
    "app/build.gradle.kts",
    '        versionCode = 91\n        versionName = "1.14.11"',
    '        versionCode = 94\n        versionName = "1.14.14"',
)

# Persist only the last successfully verified whole-manga match set. The snapshot is scoped
# to the exact binding signature and is deleted when the binding is cleared.
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

    fun saveChapterContentWholeMangaMatchSnapshot(
        mangaId: Long,
        signature: String,
        chapterIds: Set<Long>,
    ) {
        chapterContentWholeMangaMatchSignature(mangaId).set(signature)
        chapterContentWholeMangaMatchedChapterIds(mangaId).set(chapterIds.map(Long::toString).toSet())
    }

    fun clearChapterContentWholeMangaMatchSnapshot(mangaId: Long) {
        chapterContentWholeMangaMatchSignature(mangaId).delete()
        chapterContentWholeMangaMatchedChapterIds(mangaId).delete()
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

# Start the chapter-list marker state from a snapshot that belongs to the active binding.
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
        }''',
    '''        val hasWholeMangaContentBinding = wholeMangaBindingSourceId >= 0L && wholeMangaBindingUrl.isNotBlank()
        val wholeMangaMatchSignature = remember(
            wholeMangaBindingSourceId,
            wholeMangaBindingUrl,
            wholeMangaBindingTitle,
            wholeMangaBindingMemo,
            wholeMangaBindingOffset,
        ) {
            listOf(
                wholeMangaBindingSourceId.toString(),
                wholeMangaBindingUrl,
                wholeMangaBindingTitle,
                wholeMangaBindingMemo,
                wholeMangaBindingOffset.toString(),
            ).joinToString("\\u001F")
        }
        val wholeMangaChapterMatchInputs = remember(successState.chapters) {
            successState.chapters.map { item ->
                Triple(item.chapter.id, item.chapter.name, item.chapter.chapterNumber)
            }
        }
        var wholeMangaReplacementChapterIds by remember(
            successState.manga.id,
            hasWholeMangaContentBinding,
            wholeMangaMatchSignature,
        ) {
            val cachedSignature = readerPreferences
                .chapterContentWholeMangaMatchSignature(successState.manga.id)
                .get()
            val cachedIds = if (hasWholeMangaContentBinding && cachedSignature == wholeMangaMatchSignature) {
                readerPreferences
                    .chapterContentWholeMangaMatchedChapterIds(successState.manga.id)
                    .get()
                    .mapNotNull { it.toLongOrNull() }
                    .toSet()
            } else {
                emptySet()
            }
            mutableStateOf(cachedIds)
        }''',
)

# Refresh atomically: do not clear a known-good marker set before the network request starts.
replace_once(
    "app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt",
    '''            if (!hasWholeMangaContentBinding) {
                wholeMangaReplacementChapterIds = emptySet()
                return@LaunchedEffect
            }

            wholeMangaReplacementChapterIds = emptySet()
            wholeMangaReplacementChapterIds = runCatching {''',
    '''            if (!hasWholeMangaContentBinding) {
                wholeMangaReplacementChapterIds = emptySet()
                readerPreferences.clearChapterContentWholeMangaMatchSnapshot(successState.manga.id)
                return@LaunchedEffect
            }

            runCatching {''',
)

replace_once(
    "app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt",
    '''                        .toSet()
                }
            }.onFailure { error ->
                logcat(LogPriority.WARN, error) {
                    "Failed to refresh MX whole-manga replacement availability"
                }
            }.getOrDefault(emptySet())
        }''',
    '''                        .toSet()
                }
            }.onSuccess { verifiedChapterIds ->
                wholeMangaReplacementChapterIds = verifiedChapterIds
                readerPreferences.saveChapterContentWholeMangaMatchSnapshot(
                    successState.manga.id,
                    wholeMangaMatchSignature,
                    verifiedChapterIds,
                )
            }.onFailure { error ->
                logcat(LogPriority.WARN, error) {
                    "Failed to refresh MX whole-manga replacement availability; keeping last verified markers"
                }
            }
        }''',
)

# Keep documentation aligned with the actual fallback semantics.
replace_once(
    "docs/CHAPTER_CONTENT.md",
    '''After a manga refresh finishes, MX re-fetches the bound replacement manga's chapter list and recalculates the replacement set. Therefore a newly updated original chapter remains `🔒` while the replacement source is behind, and automatically changes to the replacement marker after the replacement source later publishes the matching chapter and the user refreshes again. If the target-source refresh fails, the safe presentation is no replacement marker; reader loading continues to retain its existing original-source fallback on replacement failure.''',
    '''After a manga refresh finishes, MX re-fetches the bound replacement manga's chapter list and recalculates the replacement set. Therefore a newly updated original chapter remains `🔒` while the replacement source is behind, and automatically changes to the replacement marker after the replacement source later publishes the matching chapter and the user refreshes again. Marker refresh is atomic: MX keeps the last successfully verified marker set while a new target-catalog request is in flight, and a transient target-source/network failure preserves that last verified set instead of temporarily falling back to `🔒`. The persisted marker snapshot is scoped to the exact bound source/url/title/memo/offset signature and is cleared when the whole-manga binding is removed, so stale markers cannot leak across binding changes. Reader loading continues to retain its existing original-source fallback on replacement failure.''',
)

replace_once(
    "docs/MX_EXTENSION_CHANGELOG.md",
    '''Failure remains conservative: a target refresh/match failure produces no whole-manga replacement marker, and reader replacement failures continue to fall back to the original source body.''',
    '''Marker refresh is now atomic. MX keeps the last successfully verified whole-manga marker set while the bound target catalog is reloaded, persists that verified set as an app-state snapshot, and preserves it on transient target/network failures instead of flashing back to `🔒`. The snapshot is keyed by the complete binding source/url/title/memo/offset signature and is cleared with the binding, so it cannot be reused after a binding change. Reader replacement failures continue to fall back to the original source body.''',
)

replace_once(
    "docs/CAPABILITY_MATRIX.md",
    '''| Chapter-content replacement | Host implemented | Opt-in enhanced sources | Partial device validation | Generic single-chapter and persistent whole-manga binding have initial validation; other flows vary |''',
    '''| Chapter-content replacement | Host implemented | Opt-in enhanced sources | Partial device validation | Generic single-chapter and persistent whole-manga binding have initial validation; safe-tail matching and atomic marker refresh remain pending device validation |''',
)

# Hard postconditions make the helper safe to re-run only when the exact expected branch state is present.
require("app/build.gradle.kts", "versionCode = 94")
require("app/build.gradle.kts", 'versionName = "1.14.14"')
require(
    "app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt",
    "keeping last verified markers",
)
require(
    "app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderPreferences.kt",
    "chapterContentWholeMangaMatchSignature",
)
require("docs/CHAPTER_CONTENT.md", "Marker refresh is atomic")

print("MX chapter-content marker stability patch prepared for 1.14.14 / versionCode 94")
