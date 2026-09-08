package eu.kanade.tachiyomi.extension.zh.mengxige

import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.EditTextPreference

internal const val DOMAIN_PREF = "DOMAIN_SUFFIX"
internal const val ACCOUNT_PREF = "ACCOUNT"
internal const val PASSWORD_PREF = "PASSWORD"
internal const val TOKEN_PREF = "TOKEN"
internal const val USER_PREF = "API_USER"
internal const val USER_ID_PREF = "USER_ID"
internal const val DEVICE_ID_PREF = "DEVICE_ID"
internal const val CREDENTIAL_KEY_PREF = "CREDENTIAL_KEY"

internal fun buildPreferences(
    context: Context,
    preferences: SharedPreferences,
) = arrayOf(
    EditTextPreference(context).apply {
        key = DOMAIN_PREF
        title = "服务域名后缀"
        dialogTitle = title
        setDefaultValue(DEFAULT_DOMAIN)
        summary = preferences.getString(key, DEFAULT_DOMAIN)
        setOnPreferenceChangeListener { _, newValue ->
            summary = (newValue as String).trim().ifBlank { DEFAULT_DOMAIN }
            true
        }
    },
    EditTextPreference(context).apply {
        key = ACCOUNT_PREF
        title = "梦溪阁账号（可选）"
        dialogTitle = title
        summary = preferences.getString(key, "").orEmpty().ifBlank {
            "未设置；若接口要求登录，请先在梦溪阁 APP 注册"
        }
        setOnPreferenceChangeListener { _, newValue ->
            summary = (newValue as String).trim().ifBlank {
                "未设置；若接口要求登录，请先在梦溪阁 APP 注册"
            }
            true
        }
    },
    EditTextPreference(context).apply {
        key = PASSWORD_PREF
        title = "梦溪阁密码（可选）"
        dialogTitle = title
        summary = if (preferences.getString(key, "").isNullOrBlank()) "未设置" else "********"
        setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        setOnPreferenceChangeListener { _, newValue ->
            summary = if ((newValue as String).isBlank()) "未设置" else "********"
            true
        }
    },
)
