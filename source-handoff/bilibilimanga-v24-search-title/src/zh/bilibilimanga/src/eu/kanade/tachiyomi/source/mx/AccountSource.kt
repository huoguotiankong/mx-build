package eu.kanade.tachiyomi.source.mx

/**
 * Optional account capability for extensions with an authenticated source account.
 *
 * Authentication itself remains source-owned. Extensions can keep login flows in their source
 * preferences or web flow while exposing the resulting account state to enhanced hosts.
 */
interface AccountSource {

    /** Current account if authenticated, otherwise null. */
    suspend fun getSourceAccount(): SourceAccount?
}
