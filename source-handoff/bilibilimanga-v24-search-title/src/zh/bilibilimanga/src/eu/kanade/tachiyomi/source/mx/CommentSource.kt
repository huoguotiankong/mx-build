package eu.kanade.tachiyomi.source.mx

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga

/**
 * Optional capability implemented by extensions that expose a native comment/community API.
 *
 * This is intentionally separate from [Source] so existing extensions keep their ABI and sources
 * without comments do not need to implement no-op methods.
 */
interface CommentSource {

    /** Capabilities exposed by this source's comment backend. */
    val commentCapabilities: CommentCapabilities
        get() = CommentCapabilities()

    /** Resolve the provider-specific target used for manga comments. */
    suspend fun getMangaCommentTarget(manga: SManga): CommentTarget

    /** Resolve the provider-specific target used for chapter comments. */
    suspend fun getChapterCommentTarget(manga: SManga, chapter: SChapter): CommentTarget = throw UnsupportedOperationException("Chapter comments are not supported")

    /** Fetch one page of top-level comments. */
    suspend fun getComments(target: CommentTarget, page: Int): CommentPage

    /** Fetch replies for a top-level comment. */
    suspend fun getCommentReplies(target: CommentTarget, comment: Comment, page: Int): CommentPage = throw UnsupportedOperationException("Comment replies are not supported")

    /** Publish a top-level comment and return the server representation. */
    suspend fun postComment(target: CommentTarget, content: String): Comment = throw UnsupportedOperationException("Posting comments is not supported")

    /** Publish a reply and return the server representation. */
    suspend fun postCommentReply(target: CommentTarget, parent: Comment, content: String): Comment = throw UnsupportedOperationException("Comment replies are not supported")

    /** Set the like state and return the updated comment. */
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
