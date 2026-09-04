package eu.kanade.tachiyomi.extension.zh.jmcomicplus

import android.content.Context
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Small compatibility helpers for the current Keiyoushi compile stubs. */
internal fun Preference(context: Context): Preference = EditTextPreference(context)

internal fun JsonObject?.string(vararg keys: String): String? = this?.let { obj ->
    keys.firstNotNullOfOrNull { key ->
        (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
