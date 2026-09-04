package eu.kanade.tachiyomi.source.mx

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga

interface CommentSource {
    val commentCapabilities: CommentCapabilities
        get() = CommentCapabilities()

    suspend fun getMangaCommentTarget(manga: SManga): CommentTarget

    suspend fun getChapterCommentTarget(manga: SManga, chapter: SChapter): CommentTarget = throw UnsupportedOperationException("Chapter comments are not supported")

    suspend fun getComments(target: CommentTarget, page: Int): CommentPage

    suspend fun getCommentReplies(target: CommentTarget, comment: Comment, page: Int): CommentPage = throw UnsupportedOperationException("Comment replies are not supported")

    suspend fun postComment(target: CommentTarget, content: String): Comment = throw UnsupportedOperationException("Posting comments is not supported")

    suspend fun postCommentReply(target: CommentTarget, parent: Comment, content: String): Comment = throw UnsupportedOperationException("Comment replies are not supported")

    suspend fun setCommentLiked(target: CommentTarget, comment: Comment, liked: Boolean): Comment = throw UnsupportedOperationException("Comment likes are not supported")
}

data class CommentCapabilities(
    val supportsMangaComments: Boolean = true,
    val supportsChapterComments: Boolean = false,
    val canPost: Boolean = false,
    val canReply: Boolean = false,
    val canLike: Boolean = false,
    val requiresLoginToPost: Boolean = true,
)
