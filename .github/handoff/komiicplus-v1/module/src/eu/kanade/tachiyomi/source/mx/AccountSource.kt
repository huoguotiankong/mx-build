package eu.kanade.tachiyomi.source.mx

interface AccountSource {
    suspend fun getSourceAccount(): SourceAccount?
}

data class SourceAccount(
    val id: String? = null,
    val name: String,
    val avatarUrl: String? = null,
    val profileUrl: String? = null,
)
