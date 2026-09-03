package eu.kanade.tachiyomi.source.mx

data class CommentTarget(
    val id: String,
    val url: String? = null,
    val kind: CommentTargetKind = CommentTargetKind.MANGA,
)

enum class CommentTargetKind {
    MANGA,
    CHAPTER,
}

data class Comment(
    val id: String,
    val author: CommentAuthor,
    val content: String,
    val createdAt: Long,
    val displayTime: String? = null,
    val likeCount: Long = 0,
    val replyCount: Long = 0,
    val likedByMe: Boolean = false,
    val parentId: String? = null,
)

data class CommentAuthor(
    val id: String? = null,
    val name: String,
    val avatarUrl: String? = null,
    val profileUrl: String? = null,
)

data class CommentPage(
    val comments: List<Comment>,
    val hasNextPage: Boolean,
    val totalCount: Long? = null,
)
