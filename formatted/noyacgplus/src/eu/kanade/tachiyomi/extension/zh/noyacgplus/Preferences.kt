package eu.kanade.tachiyomi.extension.zh.noyacgplus

import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.SwitchPreferenceCompat

const val POPULAR_PREF = "popular"
const val ADULT_PREF = "adult"
const val USERNAME_PREF = "username"
const val PASSWORD_PREF = "password"
const val SIMPLIFY_PREF = "simplify"
const val AUTO_SIGN_PREF = "auto_sign"

fun preferences(context: Context, prefs: SharedPreferences) = arrayOf(
    EditTextPreference(context).apply {
        key = USERNAME_PREF
        title = "账号 / 邮箱"
        dialogTitle = title
        summary = prefs.getString(key, "")?.takeIf { it.isNotBlank() } ?: "未设置（也可在 WebView 登录）"
        setOnPreferenceChangeListener { _, value ->
            summary = (value as String).takeIf { it.isNotBlank() } ?: "未设置"
            true
        }
    },
    EditTextPreference(context).apply {
        key = PASSWORD_PREF
        title = "密码"
        dialogTitle = title
        summary = if (prefs.getString(key, "").isNullOrBlank()) "未设置" else "********"
        setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        setOnPreferenceChangeListener { _, value ->
            summary = if ((value as String).isBlank()) "未设置" else "********"
            true
        }
    },
    SwitchPreferenceCompat(context).apply {
        key = AUTO_SIGN_PREF
        title = "自动签到"
        summary = "登录后每天首次使用源时自动检查并签到"
        setDefaultValue(true)
    },
    SwitchPreferenceCompat(context).apply {
        key = SIMPLIFY_PREF
        title = "繁体转简体"
        summary = "漫画标题、作者、标签、简介、章节名和评论转为简体中文"
        setDefaultValue(true)
    },
    ListPreference(context).apply {
        key = POPULAR_PREF
        title = "热门漫画"
        summary = "%s"
        setDefaultValue("day")
        entries = arrayOf("日阅读榜", "周阅读榜", "月阅读榜")
        entryValues = arrayOf("day", "week", "month")
    },
    ListPreference(context).apply {
        key = ADULT_PREF
        title = "内容类型"
        summary = "%s"
        setDefaultValue("both")
        entries = arrayOf("仅全年龄", "仅限制级", "全部")
        entryValues = arrayOf("false", "true", "both")
    },
)
