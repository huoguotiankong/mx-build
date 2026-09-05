from pathlib import Path
import re

DEV = Path("dev")
MODULE = DEV / "src/zh/zaimanhuaplus"
KOTLIN = MODULE / "src/eu/kanade/tachiyomi/extension/zh/zaimanhuaplus/ZaimanhuaPlus.kt"
DTO = MODULE / "src/eu/kanade/tachiyomi/extension/zh/zaimanhuaplus/ZaimanhuaDto.kt"
GRADLE = MODULE / "build.gradle.kts"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"patch target missing: {label}")
    return text.replace(old, new, 1)


def patch_gradle() -> None:
    text = GRADLE.read_text("utf-8")
    match = re.search(r"versionCode\s*=\s*(\d+)", text)
    if not match:
        raise SystemExit("versionCode missing")
    current = int(match.group(1))
    if current > 2:
        raise SystemExit(f"refusing to downgrade existing versionCode={current}")
    text = re.sub(r"versionCode\s*=\s*\d+", "versionCode = 2", text, count=1)
    GRADLE.write_text(text, "utf-8")


def patch_dto() -> None:
    text = DTO.read_text("utf-8")
    if "class LoginResponseDto(" not in text:
        marker = "@Serializable\nclass SimpleResponseDto(\n"
        insert = """@Serializable
class LoginResponseDto(
    val errno: Int? = 0,
    val errmsg: String = "",
    val data: UserDto? = null,
)

"""
        text = replace_once(text, marker, insert + marker, "login response dto")
    DTO.write_text(text, "utf-8")


def patch_kotlin() -> None:
    text = KOTLIN.read_text("utf-8")

    text = text.replace("import android.util.Base64\n", "")
    text = text.replace("import androidx.preference.Preference\n", "")
    if "import android.os.Handler\n" not in text:
        text = replace_once(
            text,
            "import android.content.SharedPreferences\n",
            "import android.content.SharedPreferences\nimport android.os.Handler\nimport android.os.Looper\n",
            "android handler imports",
        )
    if "import java.util.UUID\n" not in text:
        text = replace_once(
            text,
            "import java.security.MessageDigest\n",
            "import java.security.MessageDigest\nimport java.util.UUID\nimport java.util.concurrent.atomic.AtomicBoolean\n",
            "uuid imports",
        )

    old_props = """    private val preferences: SharedPreferences = getPreferences()

    override val client: OkHttpClient = network.client.newBuilder()
"""
    new_props = """    private val preferences: SharedPreferences = getPreferences()
    private val loginRunning = AtomicBoolean(false)
    private val clientId: String by lazy {
        preferences.getString(CLIENT_ID_PREF, "")
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().replace("-", "").also {
                preferences.edit().putString(CLIENT_ID_PREF, it).apply()
            }
    }

    override val client: OkHttpClient = network.client.newBuilder()
"""
    if "private val loginRunning = AtomicBoolean(false)" not in text:
        text = replace_once(text, old_props, new_props, "login state properties")

    old_auth = """        if (url.contains(tryLoginRegex) && request.header("authorization") == null && username.isNotBlank() && password.isNotBlank()) {
            token = getToken(username, password)
            apiHeaders = apiHeaders.newBuilder().setToken(token).build()
            hasTriedLogin = true
            preferences.edit().apply {
                if (token.isBlank()) {
                    putString(TOKEN_PREF, "")
                    putString(USERNAME_PREF, "")
                    putString(PASSWORD_PREF, "").apply()
                } else {
                    putString(TOKEN_PREF, token).apply()
                    request = request.newBuilder().headers(apiHeaders).build()
                }
            }
        }
"""
    new_auth = """        if (url.contains(tryLoginRegex) && request.header("authorization") == null && username.isNotBlank() && password.isNotBlank()) {
            hasTriedLogin = true
            token = runCatching { getToken(username, password) }
                .onFailure { saveLoginStatus("登录失败：${it.message ?: it.javaClass.simpleName}") }
                .getOrDefault("")
            apiHeaders = apiHeaders.newBuilder().setToken(token).build()
            if (token.isNotBlank()) {
                preferences.edit().putString(TOKEN_PREF, token).apply()
                saveLoginStatus("登录成功")
                request = request.newBuilder().headers(apiHeaders).build()
            } else {
                preferences.edit().putString(TOKEN_PREF, "").apply()
            }
        }
"""
    if old_auth in text:
        text = replace_once(text, old_auth, new_auth, "initial auth login")

    old_retry = """        if (!isValid(token) && !hasTriedLogin) {
            token = getToken(username, password)
            apiHeaders = apiHeaders.newBuilder().setToken(token).build()
            preferences.edit().apply {
                if (token.isBlank()) {
                    putString(TOKEN_PREF, "")
                    putString(USERNAME_PREF, "")
                    putString(PASSWORD_PREF, "")
                } else {
                    putString(TOKEN_PREF, token)
                }
            }.apply()
            if (token.isBlank()) return response
        } else if (request.header("authorization") == "Bearer $token") {
"""
    new_retry = """        if (!isValid(token) && !hasTriedLogin) {
            token = runCatching { getToken(username, password) }
                .onFailure { saveLoginStatus("登录失败：${it.message ?: it.javaClass.simpleName}") }
                .getOrDefault("")
            apiHeaders = apiHeaders.newBuilder().setToken(token).build()
            preferences.edit().putString(TOKEN_PREF, token).apply()
            if (token.isBlank()) return response
            saveLoginStatus("登录成功")
        } else if (request.header("authorization") == "Bearer $token") {
"""
    if old_retry in text:
        text = replace_once(text, old_retry, new_retry, "retry auth login")

    start = text.index("    private fun isValid(token: String): Boolean {")
    end = text.index("\n    // Detail", start)
    auth_helpers = """    private fun saveLoginStatus(status: String) {
        preferences.edit().putString(LAST_LOGIN_STATUS_PREF, status).apply()
    }

    private fun loginSummary(): String {
        val token = preferences.getString(TOKEN_PREF, "").orEmpty()
        val status = preferences.getString(LAST_LOGIN_STATUS_PREF, "尚未登录").orEmpty()
        return if (token.isBlank()) "当前状态：未登录\\n$status" else "当前状态：已登录\\n$status"
    }

    private fun accountUrl(path: String): HttpUrl = "$accountApiUrl$path".toHttpUrl().newBuilder()
        .addQueryParameter("platform", "android")
        .addQueryParameter("timestamp", (System.currentTimeMillis() / 1000).toString())
        .addQueryParameter("_v", ACCOUNT_APP_VERSION)
        .addQueryParameter("_c", ACCOUNT_APP_CHANNEL)
        .build()

    private fun accountHeaders(token: String = ""): Headers = headersBuilder()
        .set("Platform", "android")
        .set("X-Client-ID", clientId)
        .set("AppVersion", ACCOUNT_APP_VERSION)
        .set("BuildNumber", ACCOUNT_BUILD_NUMBER)
        .set("Channel", ACCOUNT_APP_CHANNEL)
        .set("Accept", "application/json, text/plain, */*")
        .set("Accept-Encoding", "identity")
        .setToken(token)
        .build()

    private fun isValid(token: String): Boolean {
        if (token.isBlank()) return false
        return runCatching {
            val response = network.client.newCall(
                GET(
                    accountUrl("/userInfo/get"),
                    accountHeaders(token),
                    cache = CacheControl.FORCE_NETWORK,
                ),
            ).execute().use { it.parseAs<SimpleResponseDto>() }
            response.errno == 0
        }.getOrDefault(false)
    }

    private fun getToken(username: String, password: String): String {
        if (username.isBlank() || password.isBlank()) throw IOException("用户名或密码不能为空")
        val passwordEncoded = MessageDigest.getInstance("MD5")
            .digest(password.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val formBody: RequestBody = FormBody.Builder()
            .add("username", username)
            .add("passwd", passwordEncoded)
            .build()
        val result = network.client.newCall(
            POST(
                accountUrl("/login/passwd").toString(),
                accountHeaders(),
                formBody,
            ),
        ).execute().use { response ->
            if (!response.isSuccessful) throw IOException("登录请求失败：HTTP ${response.code}")
            response.parseAs<LoginResponseDto>()
        }
        if (result.errno != null && result.errno != 0) {
            throw IOException(result.errmsg.ifBlank { "登录失败(${result.errno})" })
        }
        return result.data?.user?.token?.takeIf { it.isNotBlank() }
            ?: throw IOException("登录成功但服务端未返回 Token")
    }
"""
    text = text[:start] + auth_helpers + text[end:]

    old_companion = """        const val USERNAME_PREF = "USERNAME"
        const val PASSWORD_PREF = "PASSWORD"
        const val TOKEN_PREF = "TOKEN"
        const val COMMENTS_PREF = "COMMENTS"
"""
    new_companion = """        const val USERNAME_PREF = "USERNAME"
        const val PASSWORD_PREF = "PASSWORD"
        const val TOKEN_PREF = "TOKEN"
        const val CLIENT_ID_PREF = "CLIENT_ID"
        const val LAST_LOGIN_STATUS_PREF = "LAST_LOGIN_STATUS"
        const val ACCOUNT_APP_VERSION = "2.3.7"
        const val ACCOUNT_BUILD_NUMBER = "1502277"
        const val ACCOUNT_APP_CHANNEL = "101_01_01_000"
        const val COMMENTS_PREF = "COMMENTS"
"""
    if "const val CLIENT_ID_PREF" not in text:
        text = replace_once(text, old_companion, new_companion, "account constants")

    old_user_listener = """                setOnPreferenceChangeListener { _, _ ->
                    // clean token after username/password changed
                    preferences.edit().putString(TOKEN_PREF, "").apply()
                    apiHeaders = apiHeaders.newBuilder().setToken("").build()
                    true
                }
"""
    new_user_listener = """                setOnPreferenceChangeListener { _, _ ->
                    preferences.edit()
                        .putString(TOKEN_PREF, "")
                        .putString(LAST_LOGIN_STATUS_PREF, "登录信息已修改，请点击“立即登录 / 检查登录”")
                        .apply()
                    apiHeaders = apiHeaders.newBuilder().setToken("").build()
                    true
                }
"""
    if text.count(old_user_listener) != 2:
        raise SystemExit(f"expected two credential listeners, got {text.count(old_user_listener)}")
    text = text.replace(old_user_listener, new_user_listener, 2)

    old_token_pref = """            EditTextPreference(screen.context).apply {
                key = TOKEN_PREF
                title = "令牌(Token)"
                summary = "当前登录状态：${
                    if (preferences.getString(TOKEN_PREF, "").isNullOrEmpty()) "未登录" else "已登录"
                }\\n填写用户名和密码后，不会立刻尝试登录，会在下次请求时自动尝试"

                setEnabled(false)
            }.let(screen::addPreference)
"""
    new_login_prefs = """            EditTextPreference(screen.context).apply {
                key = "LOGIN_ACTION"
                title = "立即登录 / 检查登录"
                summary = loginSummary()
                setOnPreferenceClickListener { preference ->
                    if (!loginRunning.compareAndSet(false, true)) return@setOnPreferenceClickListener true
                    preference.setEnabled(false)
                    preference.summary = "正在登录…"
                    Thread {
                        val status = runCatching {
                            val username = preferences.getString(USERNAME_PREF, "").orEmpty()
                            val password = preferences.getString(PASSWORD_PREF, "").orEmpty()
                            val token = getToken(username, password)
                            preferences.edit().putString(TOKEN_PREF, token).apply()
                            apiHeaders = apiHeaders.newBuilder().setToken(token).build()
                            saveLoginStatus("登录成功")
                            autoSign.runIfNeeded(token)
                            "当前状态：已登录\\n登录成功"
                        }.getOrElse {
                            preferences.edit().putString(TOKEN_PREF, "").apply()
                            val message = "登录失败：${it.message ?: it.javaClass.simpleName}"
                            saveLoginStatus(message)
                            "当前状态：未登录\\n$message"
                        }
                        Handler(Looper.getMainLooper()).post {
                            preference.summary = status
                            preference.setEnabled(true)
                            loginRunning.set(false)
                        }
                    }.start()
                    true
                }
            }.let(screen::addPreference)

            EditTextPreference(screen.context).apply {
                key = "LOGIN_STATUS_VIEW"
                title = "登录状态"
                summary = loginSummary()
                setEnabled(false)
            }.let(screen::addPreference)
"""
    if old_token_pref not in text:
        raise SystemExit("token preference block missing")
    text = replace_once(text, old_token_pref, new_login_prefs, "login preferences")

    KOTLIN.write_text(text, "utf-8")


patch_gradle()
patch_dto()
patch_kotlin()
print("Prepared Zaimanhua Plus v2 source patch")
