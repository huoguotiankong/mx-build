from pathlib import Path
import re
import subprocess
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("source")

STAGE2_REF = "origin/integration/mx-stage2-comment-clean-20260906"
EH_REF = "origin/feat/ehentai-jhentai-enhancements"
FINAL_EH_REF = "origin/integration/mx-final-ehentai-comment-rich-20260905-v2"


def git_show(ref: str, path: str) -> str:
    return subprocess.check_output(
        ["git", "-C", str(root), "show", f"{ref}:{path}"],
        text=True,
        encoding="utf-8",
    )


def matching_brace_end(text: str, marker: str) -> int:
    start = text.index(marker)
    brace = text.index("{", start)
    depth = 0
    for index in range(brace, len(text)):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index + 1
    raise ValueError(f"Unclosed block for marker: {marker}")


# Stage 3 must install over the already-published Stage 2 package.
build = root / "app/build.gradle.kts"
build_text = build.read_text(encoding="utf-8")
build_text, code_count = re.subn(r"versionCode\s*=\s*\d+", "versionCode = 88", build_text, count=1)
build_text, name_count = re.subn(r'versionName\s*=\s*"[^"]+"', 'versionName = "1.14.8"', build_text, count=1)
if code_count != 1 or name_count != 1:
    raise SystemExit("Unable to set MX Stage 3 version to 1.14.8/88")
build.write_text(build_text, encoding="utf-8")

# Keep both Stage-1 generic rating call sites and the richer EHentai/JHenTai native API.
rating = root / "source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/mx/MangaRatingSource.kt"
rating.parent.mkdir(parents=True, exist_ok=True)
rating.write_text(
    '''package eu.kanade.tachiyomi.source.mx

import eu.kanade.tachiyomi.source.model.SManga

/**
 * Optional MX capability for source-owned manga/gallery ratings.
 *
 * The compatibility aliases intentionally support both the generic MX detail renderer and
 * the richer EHentai/JHenTai native rating implementation.
 */
interface MangaRatingSource {
    val ratingFieldLabel: String?
        get() = null

    val canRateManga: Boolean
        get() = true

    suspend fun getMangaRatingInfo(manga: SManga): MangaRatingInfo = MangaRatingInfo()

    suspend fun submitMangaRating(manga: SManga, rating: Double): MangaRatingInfo =
        throw UnsupportedOperationException("Rating submission is not supported")
}

data class MangaRatingInfo(
    val averageRating: Double? = null,
    val ratingCount: Long? = null,
    val userRating: Double? = null,
    val canRate: Boolean = true,
    val unavailableReason: String? = null,
    val minimum: Double = 0.5,
    val maximum: Double = 5.0,
    val step: Double = 0.5,
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

# Comment UI: use the EH-enabled UI/state surface, but graft back the richer Stage-2 parser
# and image-load fallback so the verified Tencent/Kuaikan rich-media behavior is not regressed.
comment_path = "app/src/main/java/eu/kanade/presentation/manga/comments/CommentScreen.kt"
stage2_comment = git_show(STAGE2_REF, comment_path)
eh_comment = git_show(FINAL_EH_REF, comment_path)

parser_marker = "internal data class CommentRichContent("
format_marker = "private fun formatCommentTime"
stage2_parser_start = stage2_comment.index(parser_marker)
stage2_parser_end = stage2_comment.index(format_marker, stage2_parser_start)
eh_parser_start = eh_comment.index(parser_marker)
eh_parser_end = eh_comment.index(format_marker, eh_parser_start)
merged_comment = (
    eh_comment[:eh_parser_start]
    + stage2_comment[stage2_parser_start:stage2_parser_end]
    + eh_comment[eh_parser_end:]
)

image_marker = "richContent.imageUrls.forEach { imageUrl ->"
stage2_image_start = stage2_comment.index(image_marker)
stage2_image_end = matching_brace_end(stage2_comment, image_marker)
eh_image_start = merged_comment.index(image_marker)
eh_image_end = matching_brace_end(merged_comment, image_marker)
merged_comment = (
    merged_comment[:eh_image_start]
    + stage2_comment[stage2_image_start:stage2_image_end]
    + merged_comment[eh_image_end:]
)
if "URI(" in merged_comment and "import java.net.URI" not in merged_comment:
    merged_comment = merged_comment.replace("import java.time.Instant", "import java.net.URI\nimport java.time.Instant", 1)
(root / comment_path).write_text(merged_comment, encoding="utf-8")

# Detail UI: start from EHentai's rating/tag-interaction implementation, then restore the
# Stage-1/2 three-row collapsible chip layout. Each measured chip keeps the normal provider
# action plus a separate EH tag-management action button.
detail_path = "app/src/main/java/eu/kanade/presentation/manga/components/MangaDetailInfoSection.kt"
eh_detail = git_show(EH_REF, detail_path)
merged_detail = eh_detail.replace("import androidx.compose.foundation.layout.FlowRow\n", "")

imports_to_add = [
    "import androidx.compose.material3.TextButton",
    "import androidx.compose.runtime.getValue",
    "import androidx.compose.runtime.mutableStateOf",
    "import androidx.compose.runtime.saveable.rememberSaveable",
    "import androidx.compose.runtime.setValue",
    "import androidx.compose.ui.layout.Layout",
    "import androidx.compose.ui.layout.Placeable",
    "import kotlin.math.max",
]
for import_line in imports_to_add:
    if import_line not in merged_detail:
        merged_detail = merged_detail.replace(
            "import androidx.compose.ui.unit.dp\n",
            f"import androidx.compose.ui.unit.dp\n{import_line}\n",
            1,
        )

row_start = merged_detail.index("@Composable\nprivate fun MangaDetailFieldRow")
row_end = merged_detail.index("private fun showMangaRatingDialog", row_start)
combined_rows = r'''@Composable
private fun MangaDetailFieldRow(field: DisplayField) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.widthIn(min = 58.dp),
        ) {
            Text(
                text = field.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }

        var expanded by rememberSaveable(field.label, field.values.map { it.text }.hashCode()) {
            mutableStateOf(false)
        }
        CollapsibleDetailValueFlow(
            values = field.values,
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CollapsibleDetailValueFlow(
    values: List<DisplayValue>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val horizontalSpacing = 8.dp
    val verticalSpacing = 8.dp
    val toggleSpacing = 2.dp

    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            values.forEach { value ->
                MangaDetailValueChip(value)
            }
            TextButton(onClick = { onExpandedChange(!expanded) }) {
                Text(
                    stringResource(
                        if (expanded) {
                            KMR.strings.mx_manga_detail_collapse
                        } else {
                            KMR.strings.mx_manga_detail_show_all
                        },
                    ),
                )
            }
        },
    ) { measurables, constraints ->
        if (measurables.isEmpty()) {
            layout(constraints.minWidth, constraints.minHeight) {}
        } else {
            val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
            val chipMeasurables = measurables.dropLast(1)
            val togglePlaceable = measurables.last().measure(looseConstraints)
            val chipPlaceables = chipMeasurables.map { it.measure(looseConstraints) }
            val maxWidth = constraints.maxWidth
            val horizontalSpacingPx = horizontalSpacing.roundToPx()
            val verticalSpacingPx = verticalSpacing.roundToPx()
            val toggleSpacingPx = toggleSpacing.roundToPx()

            var x = 0
            var y = 0
            var row = 0
            var lineHeight = 0
            val placements = ArrayList<DetailChipPlacement>(chipPlaceables.size)

            chipPlaceables.forEach { placeable ->
                if (x > 0 && x + placeable.width > maxWidth) {
                    y += lineHeight + verticalSpacingPx
                    x = 0
                    row += 1
                    lineHeight = 0
                }
                placements += DetailChipPlacement(placeable, x, y, row)
                x += placeable.width + horizontalSpacingPx
                lineHeight = max(lineHeight, placeable.height)
            }

            val totalRows = if (placements.isEmpty()) 0 else placements.last().row + 1
            val hasOverflow = totalRows > COLLAPSED_DETAIL_ROWS
            val visiblePlacements = if (expanded || !hasOverflow) {
                placements
            } else {
                placements.takeWhile { it.row < COLLAPSED_DETAIL_ROWS }
            }
            val chipBottom = visiblePlacements.maxOfOrNull { it.y + it.placeable.height } ?: 0
            val toggleTop = chipBottom + if (hasOverflow) toggleSpacingPx else 0
            val desiredHeight = if (hasOverflow) toggleTop + togglePlaceable.height else chipBottom
            val layoutHeight = desiredHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
            val layoutWidth = maxWidth.coerceIn(constraints.minWidth, constraints.maxWidth)

            layout(layoutWidth, layoutHeight) {
                visiblePlacements.forEach { placement ->
                    placement.placeable.placeRelative(placement.x, placement.y)
                }
                if (hasOverflow) {
                    togglePlaceable.placeRelative(0, toggleTop)
                }
            }
        }
    }
}

@Composable
private fun MangaDetailValueChip(value: DisplayValue) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (value.onClick != null) {
                Modifier.clickable(onClick = value.onClick)
            } else {
                Modifier
            },
        ) {
            Text(
                text = value.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }

        if (value.onTagActionClick != null) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.clickable(onClick = value.onTagActionClick),
            ) {
                Text(
                    text = value.tagActionLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                )
            }
        }
    }
}

'''
merged_detail = merged_detail[:row_start] + combined_rows + merged_detail[row_end:]

# The EH file already owns DisplayValue and interaction helpers. Add only the placement model
# needed by the restored collapsible layout and its three-row limit.
display_marker = "private data class DisplayField("
placement = '''private data class DetailChipPlacement(\n    val placeable: Placeable,\n    val x: Int,\n    val y: Int,\n    val row: Int,\n)\n\n'''
merged_detail = merged_detail.replace(display_marker, placement + display_marker, 1)
if "private const val COLLAPSED_DETAIL_ROWS" not in merged_detail:
    merged_detail = merged_detail.rstrip() + "\n\nprivate const val COLLAPSED_DETAIL_ROWS = 3\n"
(root / detail_path).write_text(merged_detail, encoding="utf-8")

# Guardrails: Stage 3 is not allowed to regress Stage 2 rich media or Stage 1 detail behavior.
required_paths = [
    "app/src/main/java/eu/kanade/tachiyomi/source/online/all/EHentaiMxFeatureProvider.kt",
    "app/src/main/java/eu/kanade/tachiyomi/source/online/all/EHentaiCommentLikeProvider.kt",
    "app/src/main/java/eu/kanade/tachiyomi/source/online/all/EHentaiGalleryRatingProvider.kt",
    "app/src/main/java/eu/kanade/tachiyomi/source/online/all/EHentaiTagInteractionProvider.kt",
    "source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/mx/CommentVoteSource.kt",
    "source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/mx/MangaTagInteractionSource.kt",
]
missing = [path for path in required_paths if not (root / path).is_file()]
if missing:
    raise SystemExit("Stage 3 missing EHentai files: " + ", ".join(missing))

comment_text = (root / comment_path).read_text(encoding="utf-8")
for marker in (
    "CommentVoteState",
    "supportsVotes",
    "collectExplicitCommentMedia",
    "COMMENT_HTML_MEDIA_TAG_REGEX",
    "imageLoadFailed",
):
    if marker not in comment_text:
        raise SystemExit(f"Stage 3 comment UI missing marker: {marker}")

detail_text = (root / detail_path).read_text(encoding="utf-8")
for marker in (
    "MangaRatingSource",
    "MangaTagInteractionSource",
    "showMangaRatingDialog",
    "CollapsibleDetailValueFlow",
    "onTagActionClick",
    "COLLAPSED_DETAIL_ROWS = 3",
):
    if marker not in detail_text:
        raise SystemExit(f"Stage 3 detail UI missing marker: {marker}")

app_module = (root / "app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt").read_text(encoding="utf-8")
for marker in ("EHentaiCommentLikeProvider", "EHentaiGalleryRatingProvider", "EHentaiTagInteractionProvider"):
    if marker not in app_module:
        raise SystemExit(f"Stage 3 DI graph missing provider: {marker}")

print("Prepared isolated MX Stage 3 EHentai integration at 1.14.8/88")
