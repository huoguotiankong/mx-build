from pathlib import Path
import re


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


# New source-owned rating ABI. Implementing this interface opts a source into replacing
# the generic tracking/progress action with a native rating action.
rating_api = Path("source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/mx/MangaRatingSource.kt")
rating_api.write_text(
    '''package eu.kanade.tachiyomi.source.mx

import eu.kanade.tachiyomi.source.model.SManga

/**
 * Optional MX capability for source-owned manga ratings.
 *
 * Implementing this interface makes MX replace the generic tracking action in the manga detail
 * action row with a rating entry for this source. Sources that do not implement it keep the
 * ordinary tracking action unchanged.
 */
interface MangaRatingSource {
    suspend fun getMangaRatingInfo(manga: SManga): MangaRatingInfo = MangaRatingInfo()

    suspend fun submitMangaRating(manga: SManga, score: Double): MangaRatingInfo =
        throw UnsupportedOperationException("Rating submission is not supported")
}

data class MangaRatingInfo(
    val averageScore: Double? = null,
    val minScore: Double = 1.0,
    val maxScore: Double = 10.0,
    val scoreStep: Double = 1.0,
    val userScore: Double? = null,
    val ratingCount: Long? = null,
    val canSubmit: Boolean = false,
)
''',
    encoding="utf-8",
)

# New recommendation capability. It is intentionally separate from community
# recommendations and from title-search fallback suggestions.
recommendation_api = Path("source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/mx/MangaRecommendationSource.kt")
recommendation_api.write_text(
    '''package eu.kanade.tachiyomi.source.mx

import eu.kanade.tachiyomi.source.model.SManga

/**
 * Optional MX capability for recommendations supplied by the current manga source itself.
 */
interface MangaRecommendationSource {
    suspend fun getMangaRecommendations(manga: SManga): List<SManga> = emptyList()
}
''',
    encoding="utf-8",
)

# Native rating dialog.
rating_dialog = Path("app/src/main/java/eu/kanade/presentation/manga/components/MangaRatingDialog.kt")
rating_dialog.write_text(
    '''package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.mx.MangaRatingInfo
import eu.kanade.tachiyomi.source.mx.MangaRatingSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.round

@Composable
fun MangaRatingDialog(
    manga: SManga,
    source: MangaRatingSource,
    onDismissRequest: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val failedText = stringResource(KMR.strings.mx_manga_rating_failed)
    var info by remember(manga.url, source) { mutableStateOf<MangaRatingInfo?>(null) }
    var selectedScore by remember(manga.url, source) { mutableStateOf(0.0) }
    var loading by remember(manga.url, source) { mutableStateOf(true) }
    var submitting by remember(manga.url, source) { mutableStateOf(false) }
    var errorMessage by remember(manga.url, source) { mutableStateOf<String?>(null) }
    var reloadKey by remember(manga.url, source) { mutableStateOf(0) }

    fun applyInfo(value: MangaRatingInfo) {
        info = value
        val min = value.minScore.coerceAtLeast(0.0)
        val max = value.maxScore.coerceAtLeast(min + 1.0)
        selectedScore = (value.userScore ?: value.averageScore ?: min)
            .coerceIn(min, max)
    }

    LaunchedEffect(manga.url, source, reloadKey) {
        loading = true
        errorMessage = null
        try {
            val loaded = withIOContext { source.getMangaRatingInfo(manga) }
            applyInfo(loaded)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            errorMessage = error.message
        } finally {
            loading = false
        }
    }

    val ratingInfo = info
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(KMR.strings.mx_manga_rating_title)) },
        text = {
            when {
                loading -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                }

                ratingInfo == null -> Text(
                    errorMessage ?: failedText,
                    color = MaterialTheme.colorScheme.error,
                )

                else -> {
                    val min = ratingInfo.minScore.coerceAtLeast(0.0)
                    val max = ratingInfo.maxScore.coerceAtLeast(min + 1.0)
                    val step = ratingInfo.scoreStep.coerceAtLeast(0.1)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (ratingInfo.averageScore != null) {
                            Text(
                                "${stringResource(KMR.strings.mx_manga_rating_average)}：" +
                                    "${formatRating(ratingInfo.averageScore)} / ${formatRating(max)}",
                            )
                        } else {
                            Text(stringResource(KMR.strings.mx_manga_rating_no_data))
                        }
                        ratingInfo.ratingCount?.let { count ->
                            Text("${stringResource(KMR.strings.mx_manga_rating_count)}：$count")
                        }
                        ratingInfo.userScore?.let { score ->
                            Text(
                                "${stringResource(KMR.strings.mx_manga_rating_mine)}：" +
                                    "${formatRating(score)} / ${formatRating(max)}",
                            )
                        }

                        if (ratingInfo.canSubmit) {
                            Text(
                                "${stringResource(KMR.strings.mx_manga_rating_selected)}：" +
                                    "${formatRating(selectedScore)} / ${formatRating(max)}",
                            )
                            Slider(
                                value = selectedScore.toFloat(),
                                onValueChange = { raw ->
                                    selectedScore = snapRating(raw.toDouble(), min, max, step)
                                },
                                valueRange = min.toFloat()..max.toFloat(),
                                enabled = !submitting,
                            )
                        }

                        errorMessage?.let { message ->
                            Text(
                                if (message.isBlank()) failedText else message,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                loading -> Unit
                ratingInfo == null -> TextButton(onClick = { reloadKey += 1 }) {
                    Text(stringResource(KMR.strings.mx_manga_rating_retry))
                }
                ratingInfo.canSubmit -> TextButton(
                    enabled = !submitting,
                    onClick = {
                        scope.launch {
                            submitting = true
                            errorMessage = null
                            try {
                                val updated = withIOContext {
                                    source.submitMangaRating(manga, selectedScore)
                                }
                                applyInfo(updated)
                            } catch (error: Throwable) {
                                if (error is CancellationException) throw error
                                errorMessage = error.message
                            } finally {
                                submitting = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(KMR.strings.mx_manga_rating_submit))
                }
                else -> TextButton(onClick = onDismissRequest) {
                    Text(stringResource(KMR.strings.mx_manga_rating_close))
                }
            }
        },
        dismissButton = if (ratingInfo?.canSubmit == true) {
            {
                TextButton(onClick = onDismissRequest, enabled = !submitting) {
                    Text(stringResource(KMR.strings.mx_manga_rating_close))
                }
            }
        } else {
            null
        },
    )
}

private fun snapRating(value: Double, min: Double, max: Double, step: Double): Double {
    val snapped = min + round((value - min) / step) * step
    return snapped.coerceIn(min, max)
}

private fun formatRating(value: Double): String {
    val rounded = round(value * 10.0) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}
''',
    encoding="utf-8",
)

# Structured metadata chips: hard cap collapsed fields to three rows, with explicit
# expand/collapse control so hidden values are always reachable.
detail_section = Path("app/src/main/java/eu/kanade/presentation/manga/components/MangaDetailInfoSection.kt")
text = detail_section.read_text(encoding="utf-8")
runtime_import = "import androidx.compose.runtime.Composable\n"
if text.count(runtime_import) != 1:
    raise SystemExit(f"MangaDetailInfoSection runtime import mismatch: {text.count(runtime_import)}")
text = text.replace(
    runtime_import,
    runtime_import
    + "import androidx.compose.runtime.getValue\n"
    + "import androidx.compose.runtime.mutableStateOf\n"
    + "import androidx.compose.runtime.saveable.rememberSaveable\n"
    + "import androidx.compose.runtime.setValue\n",
    1,
)
old_flow = '''        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            field.values.forEach { value ->
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
            }
        }
'''
new_flow = '''        var expanded by rememberSaveable(field.label, field.values.map { it.text }.hashCode()) {
            mutableStateOf(false)
        }
        val canExpand = field.values.size > 3
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxLines = if (expanded) Int.MAX_VALUE else 3,
            ) {
                field.values.forEach { value ->
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
                }
            }
            if (canExpand) {
                Text(
                    text = stringResource(
                        if (expanded) {
                            KMR.strings.mx_manga_detail_collapse
                        } else {
                            KMR.strings.mx_manga_detail_show_all
                        },
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clickable { expanded = !expanded }
                        .padding(vertical = 4.dp),
                )
            }
        }
'''
if text.count(old_flow) != 1:
    raise SystemExit(f"MangaDetailInfoSection flow block mismatch: {text.count(old_flow)}")
detail_section.write_text(text.replace(old_flow, new_flow, 1), encoding="utf-8")

# Action row: a source that implements MangaRatingSource replaces tracking with rating.
info_header = Path("app/src/main/java/eu/kanade/presentation/manga/components/MangaInfoHeader.kt")
text = info_header.read_text(encoding="utf-8")
import_anchor = "import androidx.compose.material.icons.outlined.Schedule\n"
if text.count(import_anchor) != 1:
    raise SystemExit(f"MangaInfoHeader import anchor mismatch: {text.count(import_anchor)}")
text = text.replace(import_anchor, import_anchor + "import androidx.compose.material.icons.outlined.Star\n", 1)
signature_anchor = "    onTrackingClicked: () -> Unit,\n    // KMK -->\n"
if text.count(signature_anchor) != 1:
    raise SystemExit(f"MangaActionRow signature anchor mismatch: {text.count(signature_anchor)}")
text = text.replace(
    signature_anchor,
    "    onTrackingClicked: () -> Unit,\n"
    "    // MX -->\n"
    "    onRatingClicked: (() -> Unit)?,\n"
    "    // MX <--\n"
    "    // KMK -->\n",
    1,
)
tracking_block = '''        MangaActionButton(
            title = if (trackingCount == 0) {
                stringResource(MR.strings.manga_tracking_tab)
            } else {
                pluralStringResource(MR.plurals.num_trackers, count = trackingCount, trackingCount)
            },
            icon = if (trackingCount == 0) Icons.Outlined.Sync else Icons.Outlined.Done,
            color = if (trackingCount == 0) defaultActionButtonColor else MaterialTheme.colorScheme.primary,
            onClick = onTrackingClicked,
        )
'''
rating_block = '''        // MX -->
        if (onRatingClicked != null) {
            MangaActionButton(
                title = stringResource(KMR.strings.mx_manga_rating_title),
                icon = Icons.Outlined.Star,
                color = MaterialTheme.colorScheme.primary,
                onClick = onRatingClicked,
            )
        } else {
            MangaActionButton(
                title = if (trackingCount == 0) {
                    stringResource(MR.strings.manga_tracking_tab)
                } else {
                    pluralStringResource(MR.plurals.num_trackers, count = trackingCount, trackingCount)
                },
                icon = if (trackingCount == 0) Icons.Outlined.Sync else Icons.Outlined.Done,
                color = if (trackingCount == 0) defaultActionButtonColor else MaterialTheme.colorScheme.primary,
                onClick = onTrackingClicked,
            )
        }
        // MX <--
'''
if text.count(tracking_block) != 1:
    raise SystemExit(f"MangaActionRow tracking block mismatch: {text.count(tracking_block)}")
info_header.write_text(text.replace(tracking_block, rating_block, 1), encoding="utf-8")

# Presentation wiring: propagate rating callback and show the recommendation action only
# for sources that actually expose source-owned recommendations.
presentation = Path("app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt")
text = presentation.read_text(encoding="utf-8")
source_import = "import eu.kanade.tachiyomi.source.mx.MangaDetailInfo\n"
if text.count(source_import) != 1:
    raise SystemExit(f"Presentation recommendation import anchor mismatch: {text.count(source_import)}")
text = text.replace(
    source_import,
    source_import + "import eu.kanade.tachiyomi.source.mx.MangaRecommendationSource\n",
    1,
)
composable_anchor = "@Composable\nfun MangaScreen(\n"
if text.count(composable_anchor) != 1:
    raise SystemExit(f"Presentation MangaScreen anchor mismatch: {text.count(composable_anchor)}")
text = text.replace(
    composable_anchor,
    '''private fun Source.hasMxSourceRecommendations(): Boolean =
    this is MangaRecommendationSource || supportsRelatedMangas

@Composable
fun MangaScreen(
''',
    1,
)
sig = "    onTrackingClicked: () -> Unit,\n    // KMK -->\n"
if text.count(sig) != 3:
    raise SystemExit(f"Presentation rating signature mismatch: {text.count(sig)}")
text = text.replace(
    sig,
    "    onTrackingClicked: () -> Unit,\n"
    "    // MX -->\n"
    "    onRatingClicked: (() -> Unit)?,\n"
    "    // MX <--\n"
    "    // KMK -->\n",
)
pattern = re.compile(r"(?m)^(\s*)onTrackingClicked = onTrackingClicked,\n")
text, count = pattern.subn(
    lambda m: m.group(0)
    + f"{m.group(1)}// MX -->\n"
    + f"{m.group(1)}onRatingClicked = onRatingClicked,\n"
    + f"{m.group(1)}// MX <--\n",
    text,
)
if count != 4:
    raise SystemExit(f"Presentation rating call propagation mismatch: {count}")
old_toolbar = "onClickRecommend = onRecommendClicked.takeIf { state.showRecommendationsInOverflow },"
new_toolbar = (
    "onClickRecommend = onRecommendClicked.takeIf { "
    "state.showRecommendationsInOverflow && state.source.hasMxSourceRecommendations() },"
)
if text.count(old_toolbar) != 2:
    raise SystemExit(f"Presentation recommend toolbar mismatch: {text.count(old_toolbar)}")
text = text.replace(old_toolbar, new_toolbar)
old_info_if = "if (!state.showRecommendationsInOverflow || state.showMergeWithAnother) {"
new_info_if = '''if (
                        (!state.showRecommendationsInOverflow && state.source.hasMxSourceRecommendations()) ||
                        state.showMergeWithAnother
                    ) {'''
if text.count(old_info_if) != 2:
    raise SystemExit(f"Presentation recommend section mismatch: {text.count(old_info_if)}")
text = text.replace(old_info_if, new_info_if)
old_show = "showRecommendsButton = !state.showRecommendationsInOverflow,"
new_show = (
    "showRecommendsButton = !state.showRecommendationsInOverflow && "
    "state.source.hasMxSourceRecommendations(),"
)
if text.count(old_show) != 2:
    raise SystemExit(f"Presentation recommend button mismatch: {text.count(old_show)}")
text = text.replace(old_show, new_show)
presentation.write_text(text, encoding="utf-8")

# Source recommendation loader: never invoke the community engines or title-search
# fallback for the detail page's recommendation action.
screen_model = Path("app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreenModel.kt")
text = screen_model.read_text(encoding="utf-8")
model_import = "import eu.kanade.tachiyomi.source.Source\n"
if text.count(model_import) != 1:
    raise SystemExit(f"MangaScreenModel Source import mismatch: {text.count(model_import)}")
text = text.replace(
    model_import,
    model_import + "import eu.kanade.tachiyomi.source.mx.MangaRecommendationSource\n",
    1,
)
model_anchor = "    // KMK <--\n\n    /**\n     * @throws IllegalStateException if the swipe action"
if text.count(model_anchor) != 1:
    raise SystemExit(f"MangaScreenModel source recommendation insertion anchor mismatch: {text.count(model_anchor)}")
source_loader = '''    // MX -->
    internal fun prepareSourceRecommendations() {
        updateSuccessState {
            it.copy(
                relatedMangaCollection = null,
                isRelatedMangasFetched = false,
            )
        }
    }

    internal suspend fun fetchSourceRecommendations() {
        val state = successState ?: return
        if (state.manga.source == MERGED_SOURCE_ID || state.source is StubSource) return

        setRelatedMangasFetchedStatus(false)

        fun exceptionHandler(error: Throwable) {
            logcat(LogPriority.ERROR, error)
            val message = with(context) { error.formattedMessage }
            screenModelScope.launch {
                snackbarHostState.showSnackbar(message = message)
            }
        }

        try {
            val source = state.source
            val smanga = state.manga.toSManga()
            val sourceRecommendations = when {
                source is MangaRecommendationSource -> source.getMangaRecommendations(smanga)
                source.supportsRelatedMangas -> source.fetchRelatedMangaList(smanga)
                else -> emptyList()
            }
            val relatedManga = RelatedManga.Success.fromPair("" to sourceRecommendations) { mangaList ->
                mangaList
                    .map { it.toDomainManga(source.id) }
                    .distinctBy { it.url }
                    .let { networkToLocalManga(manga = it, updateInfo = false) }
            }
            updateSuccessState {
                it.copy(relatedMangaCollection = listOf(relatedManga))
            }
        } catch (error: Exception) {
            exceptionHandler(error)
        } finally {
            setRelatedMangasFetchedStatus(true)
        }
    }
    // MX <--

'''
text = text.replace(
    model_anchor,
    "    // KMK <--\n\n"
    + source_loader
    + "    /**\n     * @throws IllegalStateException if the swipe action",
    1,
)
screen_model.write_text(text, encoding="utf-8")

# Host screen wiring: detect rating capability, open the native rating dialog, and route
# the existing 查看推荐 action to source-owned related manga results.
screen = Path("app/src/main/java/eu/kanade/tachiyomi/ui/manga/MangaScreen.kt")
text = screen.read_text(encoding="utf-8")
cover_import = "import eu.kanade.presentation.manga.components.MangaCoverDialog\n"
if text.count(cover_import) != 1:
    raise SystemExit(f"UI MangaRatingDialog import anchor mismatch: {text.count(cover_import)}")
text = text.replace(
    cover_import,
    cover_import + "import eu.kanade.presentation.manga.components.MangaRatingDialog\n",
    1,
)
detail_import = "import eu.kanade.tachiyomi.source.mx.MangaDetailSource\n"
if text.count(detail_import) != 1:
    raise SystemExit(f"UI MangaRatingSource import anchor mismatch: {text.count(detail_import)}")
text = text.replace(
    detail_import,
    detail_import + "import eu.kanade.tachiyomi.source.mx.MangaRatingSource\n",
    1,
)
if text.count("import exh.recs.RecommendsScreen\n") != 1:
    raise SystemExit("UI community recommendation import mismatch")
text = text.replace("import exh.recs.RecommendsScreen\n", "", 1)
if text.count("import exh.source.getMainSource\n") != 1:
    raise SystemExit("UI getMainSource import mismatch")
text = text.replace("import exh.source.getMainSource\n", "", 1)
detail_source_anchor = '''        val detailSource = remember(successState.source, successState.mergedData) {
            (successState.source as? MangaDetailSource)
                ?.takeIf { successState.mergedData == null }
        }
'''
if text.count(detail_source_anchor) != 1:
    raise SystemExit(f"UI detailSource anchor mismatch: {text.count(detail_source_anchor)}")
text = text.replace(
    detail_source_anchor,
    detail_source_anchor
    + '''        val ratingSource = remember(successState.source, successState.mergedData) {
            (successState.source as? MangaRatingSource)
                ?.takeIf { successState.mergedData == null }
        }
        var ratingDialogVisible by rememberSaveable(successState.manga.id, successState.source.id) {
            mutableStateOf(false)
        }
''',
    1,
)
tracking_callback = '''            onTrackingClicked = {
                if (!successState.hasLoggedInTrackers) {
                    navigator.push(SettingsScreen(SettingsScreen.Destination.Tracking))
                } else {
                    screenModel.showTrackDialog()
                }
            },
'''
if text.count(tracking_callback) != 1:
    raise SystemExit(f"UI tracking callback mismatch: {text.count(tracking_callback)}")
text = text.replace(
    tracking_callback,
    tracking_callback
    + '''            // MX -->
            onRatingClicked = {
                ratingDialogVisible = true
            }.takeIf { ratingSource != null },
            // MX <--
''',
    1,
)
old_recommend = '''            onRecommendClicked = {
                openRecommends(navigator, screenModel.source?.getMainSource(), successState.manga)
            },
'''
new_recommend = '''            onRecommendClicked = {
                screenModel.prepareSourceRecommendations()
                showRelatedMangasScreen()
                scope.launchIO { screenModel.fetchSourceRecommendations() }
            },
'''
if text.count(old_recommend) != 1:
    raise SystemExit(f"UI recommendation callback mismatch: {text.count(old_recommend)}")
text = text.replace(old_recommend, new_recommend, 1)
post_screen_anchor = '''        )

        // MX -->
        val replacementChapter = successState.chapters
'''
if text.count(post_screen_anchor) != 1:
    raise SystemExit(f"UI rating dialog insertion anchor mismatch: {text.count(post_screen_anchor)}")
text = text.replace(
    post_screen_anchor,
    '''        )

        // MX -->
        if (ratingDialogVisible && ratingSource != null) {
            MangaRatingDialog(
                manga = successState.manga.toSManga(),
                source = ratingSource,
                onDismissRequest = { ratingDialogVisible = false },
            )
        }

        val replacementChapter = successState.chapters
''',
    1,
)
old_open_recommends = '''    // AZ -->
    private fun openRecommends(navigator: Navigator, source: Source?, manga: Manga) {
        source ?: return
        RecommendsScreen.Args.SingleSourceManga(manga.id, source.id)
            .let(::RecommendsScreen)
            .let(navigator::push)
    }
    // AZ <--
'''
if text.count(old_open_recommends) != 1:
    raise SystemExit(f"UI legacy community recommendation helper mismatch: {text.count(old_open_recommends)}")
text = text.replace(old_open_recommends, "", 1)
screen.write_text(text, encoding="utf-8")

# MX-only strings live in KMR base resources only.
strings = Path("i18n-kmk/src/commonMain/moko-resources/base/strings.xml")
text = strings.read_text(encoding="utf-8")
strings_anchor = '    <string name="mx_manga_detail_status">状态</string>\n'
if text.count(strings_anchor) != 1:
    raise SystemExit(f"KMR MX detail string anchor mismatch: {text.count(strings_anchor)}")
text = text.replace(
    strings_anchor,
    strings_anchor
    + '''    <string name="mx_manga_detail_show_all">显示全部</string>
    <string name="mx_manga_detail_collapse">收起</string>
    <string name="mx_manga_rating_title">评分</string>
    <string name="mx_manga_rating_average">当前评分</string>
    <string name="mx_manga_rating_count">评分人数</string>
    <string name="mx_manga_rating_mine">我的评分</string>
    <string name="mx_manga_rating_selected">选择评分</string>
    <string name="mx_manga_rating_submit">提交评分</string>
    <string name="mx_manga_rating_close">关闭</string>
    <string name="mx_manga_rating_retry">重试</string>
    <string name="mx_manga_rating_no_data">暂无评分数据</string>
    <string name="mx_manga_rating_failed">评分加载失败</string>
''',
    1,
)
strings.write_text(text, encoding="utf-8")

# Formal app update.
build_gradle = Path("app/build.gradle.kts")
replace_once(
    build_gradle,
    '        versionCode = 83\n        versionName = "1.14.3"\n',
    '        versionCode = 84\n        versionName = "1.14.4"\n',
    "MX app 1.14.4 version bump",
)

# Maintenance documentation.
doc = Path("docs/MANGA_DETAILS.md")
text = doc.read_text(encoding="utf-8")
heading = "## 2026-09-05 source-owned detail actions and compact tags"
if heading in text:
    raise SystemExit("MANGA_DETAILS enhancement documentation already exists")
text = text.rstrip() + '''

## 2026-09-05 source-owned detail actions and compact tags

MX detail-page enhancement hooks are source opt-in and keep ordinary extensions unchanged:

- `MangaRatingSource` lets an enhanced source replace the generic tracking/progress action slot with a native **评分** entry. The source supplies the rating scale/current values and decides whether submitting a score is allowed. Sources that do not implement the interface keep the original tracking action.
- `MangaRecommendationSource` lets an enhanced source return recommendations from its own website/API. The detail-page **查看推荐** entry now opens only source-owned recommendations. It no longer routes to the AniList/MangaUpdates/MyAnimeList community recommendation screen. Existing Komikku sources that explicitly expose `supportsRelatedMangas` are also accepted, but only their `fetchRelatedMangaList` result is used; title-search fallback is not mixed into this action.
- Structured metadata value chips are collapsed to at most three rows by default. Fields with more than three values expose **显示全部**, and the expanded state exposes **收起**.

The ABI additions are optional and additive. Android behavior remains pending device validation after the stable-signed Preview is installed.
'''
doc.write_text(text, encoding="utf-8")
