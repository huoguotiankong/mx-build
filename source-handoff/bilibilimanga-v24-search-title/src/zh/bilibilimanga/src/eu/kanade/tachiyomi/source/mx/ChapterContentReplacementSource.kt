package eu.kanade.tachiyomi.source.mx

/**
 * Optional MX capability for sources that explicitly want to expose chapter-content replacement UI.
 *
 * Ordinary Tachiyomi/Mihon/Komikku extensions do not implement this interface, so MX keeps all
 * replacement entry points hidden by default.
 */
interface ChapterContentReplacementSource {

    val chapterContentReplacementCapabilities: ChapterContentReplacementCapabilities
        get() = ChapterContentReplacementCapabilities()
}

data class ChapterContentReplacementCapabilities(
    val showInChapterList: Boolean = true,
    val showInReader: Boolean = true,
)
