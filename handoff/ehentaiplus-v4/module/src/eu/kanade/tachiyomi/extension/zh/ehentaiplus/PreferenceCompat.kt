package eu.kanade.tachiyomi.extension.zh.ehentaiplus

/**
 * Keiyoushi's current extension preference stub exposes Preference() without
 * the Android Context constructor. Keep call sites source-compatible with the
 * regular AndroidX API used by host builds.
 */
@Suppress("UNUSED_PARAMETER", "FunctionName")
internal fun Preference(context: android.content.Context): androidx.preference.Preference = androidx.preference.Preference()
