from pathlib import Path
import re
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")

build = root / "app/build.gradle.kts"
rating = root / "source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/mx/MangaRatingSource.kt"

build_text = build.read_text(encoding="utf-8")
build_text, code_count = re.subn(r'versionCode\s*=\s*\d+', 'versionCode = 86', build_text, count=1)
build_text, name_count = re.subn(r'versionName\s*=\s*"[^"]+"', 'versionName = "1.14.6"', build_text, count=1)
if code_count != 1 or name_count != 1:
    raise SystemExit("Unable to set MX final version to 1.14.6/86")
build.write_text(build_text, encoding="utf-8")

# The library/detail branch and the EHentai/JHenTai enhancement branch independently
# introduced MangaRatingSource with different field names. Keep one additive ABI that
# accepts both call sites, so the final build retains the generic MX rating dialog and
# the richer EHentai native rating implementation without forcing either feature back.
rating.write_text(
    '''package eu.kanade.tachiyomi.source.mx

import eu.kanade.tachiyomi.source.model.SManga

/**
 * Optional MX capability for source-owned manga/gallery ratings.
 *
 * This compatibility shape intentionally keeps both the generic MX naming used by the
 * detail-page rating dialog and the richer EHentai/JHenTai naming used by native gallery
 * interactions. New providers may use either set; the aliases keep existing callers source-
 * compatible while the project converges on one naming scheme later.
 */
interface MangaRatingSource {
    /** Optional structured-detail field label that should open a provider-native rating action. */
    val ratingFieldLabel: String?
        get() = null

    /** Whether the current source session is generally allowed to submit ratings. */
    val canRateManga: Boolean
        get() = true

    suspend fun getMangaRatingInfo(manga: SManga): MangaRatingInfo = MangaRatingInfo()

    suspend fun submitMangaRating(manga: SManga, rating: Double): MangaRatingInfo =
        throw UnsupportedOperationException("Rating submission is not supported")
}

data class MangaRatingInfo(
    // EHentai/JHenTai/native naming.
    val averageRating: Double? = null,
    val ratingCount: Long? = null,
    val userRating: Double? = null,
    val canRate: Boolean = true,
    val unavailableReason: String? = null,
    val minimum: Double = 0.5,
    val maximum: Double = 5.0,
    val step: Double = 0.5,
    // Generic MX detail-page aliases. Defaults mirror the native values when a provider
    // populates only the EH/native naming above.
    val averageScore: Double? = averageRating,
    val minScore: Double = minimum,
    val maxScore: Double = maximum,
    val scoreStep: Double = step,
    val userScore: Double? = userRating,
    val canSubmit: Boolean = canRate,
)
''',
    encoding="utf-8",
)

# Guardrails: final integration must contain the EHentai interaction surface and library work.
required_paths = [
    "app/src/main/java/eu/kanade/tachiyomi/source/online/all/EHentaiMxFeatureProvider.kt",
    "app/src/main/java/eu/kanade/tachiyomi/source/online/all/EHentaiCommentLikeProvider.kt",
    "app/src/main/java/eu/kanade/tachiyomi/source/online/all/EHentaiGalleryRatingProvider.kt",
    "app/src/main/java/eu/kanade/tachiyomi/source/online/all/EHentaiTagInteractionProvider.kt",
    "app/src/main/java/eu/kanade/presentation/library/components/LibraryToolbar.kt",
    "app/src/main/java/eu/kanade/presentation/manga/components/MangaRatingDialog.kt",
    "source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/mx/CommentVoteSource.kt",
    "source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/mx/MangaTagInteractionSource.kt",
]
missing = [path for path in required_paths if not (root / path).is_file()]
if missing:
    raise SystemExit("Final integration is missing required files: " + ", ".join(missing))

comment_screen = (root / "app/src/main/java/eu/kanade/presentation/manga/comments/CommentScreen.kt").read_text(encoding="utf-8")
for marker in ("CommentVoteState", "supportsVotes", "parseCommentRichContent"):
    if marker not in comment_screen:
        raise SystemExit(f"Final comment UI missing marker: {marker}")

app_module = (root / "app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt").read_text(encoding="utf-8")
for marker in ("EHentaiCommentLikeProvider", "EHentaiGalleryRatingProvider", "EHentaiTagInteractionProvider"):
    if marker not in app_module:
        raise SystemExit(f"Final DI graph missing EHentai provider: {marker}")

print("Prepared MX 1.14.6/86 final integration compatibility layer")
