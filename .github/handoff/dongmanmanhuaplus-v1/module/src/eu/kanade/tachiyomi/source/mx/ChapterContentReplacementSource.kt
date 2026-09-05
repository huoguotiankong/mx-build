package eu.kanade.tachiyomi.source.mx

interface ChapterContentReplacementSource {
    val chapterContentReplacementCapabilities: ChapterContentReplacementCapabilities
        get() = ChapterContentReplacementCapabilities()
}

data class ChapterContentReplacementCapabilities(
    val showInChapterList: Boolean = true,
    val showInReader: Boolean = true,
)
