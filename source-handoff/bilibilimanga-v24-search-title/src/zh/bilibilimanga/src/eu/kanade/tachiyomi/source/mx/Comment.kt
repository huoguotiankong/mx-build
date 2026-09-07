package eu.kanade.tachiyomi.source.mx

/** Provider-neutral identifier for a comment thread. */
data class CommentTarget(
    val id: String,
    val url: String? = null,
    val kind: CommentTargetKind = CommentTargetKind.MANGA,
)

enum class CommentTargetKind {
    MANGA,
    CHAPTER,
}

/** Provider-neutral comment model rendered by the host application. */
data class Comment(
    val id: String,
    val author: CommentAuthor,
    val content: String,
    /** Unix epoch time in milliseconds. Use 0 when the provider does not expose an exact time. */
    val createdAt: Long,
    /** Provider-formatted time such as "6 years ago" when no exact timestamp is available. */
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
