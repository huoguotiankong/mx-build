package eu.kanade.tachiyomi.source.mx

interface AccountSource {
    suspend fun getSourceAccount(): SourceAccount?
}
