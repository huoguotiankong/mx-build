package eu.kanade.tachiyomi.extension.zh.komiicplus

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequestDto(
    val email: String,
    val password: String,
)

@Serializable
data class LoginResponseDto(
    val token: String? = null,
    val message: String? = null,
    val error: String? = null,
)

@Serializable
data class ImageLimitDataDto(
    val getImageLimit: ImageLimitDto? = null,
)

@Serializable
data class ImageLimitDto(
    val limit: Long = 0,
    val usage: Long = 0,
    val resetInSeconds: Long = 0,
)

@Serializable
data class CommentDataDto(
    val getMessagesByComicId: List<KomiicCommentDto>? = null,
    val messageChan: List<KomiicCommentDto>? = null,
    val messageCountByComicId: Long? = null,
)

@Serializable
data class KomiicCommentDto(
    val id: String = "",
    val comicId: String? = null,
    val account: KomiicCommentAccountDto? = null,
    val message: String = "",
    val replyTo: KomiicCommentReplyToDto? = null,
    val dateUpdated: String? = null,
    val dateCreated: String? = null,
)

@Serializable
data class KomiicCommentAccountDto(
    val id: String? = null,
    val nickname: String? = null,
    val profileImageUrl: String? = null,
)

@Serializable
data class KomiicCommentReplyToDto(
    val id: String? = null,
)
