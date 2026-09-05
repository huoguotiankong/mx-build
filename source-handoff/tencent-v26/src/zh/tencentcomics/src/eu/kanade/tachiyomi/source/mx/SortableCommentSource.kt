package eu.kanade.tachiyomi.source.mx

interface SortableCommentSource {
    val commentSortOptions: List<CommentSortOption>

    val defaultCommentSortId: String
        get() = commentSortOptions.firstOrNull()?.id.orEmpty()

    suspend fun getComments(
        target: CommentTarget,
        page: Int,
        sortId: String,
    ): CommentPage
}

data class CommentSortOption(
    val id: String,
    val label: String,
)
