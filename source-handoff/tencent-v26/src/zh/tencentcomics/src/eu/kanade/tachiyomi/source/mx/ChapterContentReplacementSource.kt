package eu.kanade.tachiyomi.source.mx

/**
 * Optional MX capability for sources that explicitly expose chapter-content replacement UI.
 *
 * Replacement matching remains host-side; implementing this marker only opts the source into
 * the chapter-list and reader replacement entry points.
 */
interface ChapterContentReplacementSource {
    val chapterContentReplacementCapabilities: ChapterContentReplacementCapabilities
        get() = ChapterContentReplacementCapabilities()
}

data class ChapterContentReplacementCapabilities(
    val showInChapterList: Boolean = true,
    val showInReader: Boolean = true,
)
