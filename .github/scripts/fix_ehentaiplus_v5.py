from pathlib import Path

ROOT = Path("source")
SRC = ROOT / "src/zh/ehentaiplus/src/eu/kanade/tachiyomi/extension/zh/ehentaiplus/EHentaiPlus.kt"
GRADLE = ROOT / "src/zh/ehentaiplus/build.gradle.kts"
DOC = ROOT / "docs/sources/ehentaiplus.md"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


s = SRC.read_text("utf-8")

s = replace_once(
    s,
    "import android.content.SharedPreferences\n",
    "import android.content.Intent\nimport android.content.SharedPreferences\n",
    "Intent import",
)

s = replace_once(
    s,
    "    private val detailCache = ConcurrentHashMap<String, CachedDetail>()\n    private val translationMutex = Mutex()\n",
    "    private val detailCache = ConcurrentHashMap<String, CachedDetail>()\n    private val imageUrlCache = ConcurrentHashMap<String, CachedImageUrl>()\n    private val translationMutex = Mutex()\n",
    "image URL cache field",
)

s = replace_once(
    s,
    "    private var lastGalleryId = \"\"\n",
    "    private var lastGalleryId = \"\"\n    @Volatile private var webLoginWatchGeneration = 0\n",
    "web login generation",
)

s = replace_once(
    s,
    "    override fun getHomeUrl(): String = baseUrl\n",
    "    override fun getHomeUrl(): String = if (isLoggedIn()) baseUrl else FORUM_LOGIN_URL\n",
    "web login home URL",
)

s = replace_once(
    s,
    "            if (host in EH_COOKIE_HOSTS || host.endsWith(\".e-hentai.org\") || host == \"exhentai.org\") {\n                val cookie = cookieHeader()\n",
    "            if (host in EH_COOKIE_HOSTS || host.endsWith(\".e-hentai.org\") || host == \"exhentai.org\") {\n                syncWebViewCookies(save = true, onlyMissing = true)\n                val cookie = cookieHeader()\n",
    "automatic WebView cookie import",
)

s = replace_once(
    s,
    "    override fun getFilterList(data: JsonElement?) = FilterList(\n        ChineseOnlyFilter(chineseOnly()),\n",
    "    override fun getFilterList(data: JsonElement?) = FilterList(\n        Filter.Header(accountFilterStatus()),\n        ChineseOnlyFilter(chineseOnly()),\n",
    "filter account status",
)

old_image = '''    override suspend fun getImageUrl(page: Page): String {
        val request = Request.Builder()
            .url(page.url)
            .headers(headers)
            .header("Cache-Control", "no-cache")
            .build()
        return client.newCall(request).awaitSuccess().use { response ->
            decorateImageUrl(EHParser.parseImageInfo(response, originalImages()).imageUrl)
        }
    }
'''
new_image = '''    override suspend fun getImageUrl(page: Page): String {
        imageUrlCache[page.url]
            ?.takeIf { System.currentTimeMillis() - it.time < IMAGE_URL_CACHE_MS }
            ?.let { return it.url }

        val request = Request.Builder()
            .url(page.url)
            .headers(headers)
            .build()
        val imageUrl = client.newCall(request).awaitSuccess().use { response ->
            decorateImageUrl(EHParser.parseImageInfo(response, originalImages()).imageUrl)
        }
        imageUrlCache[page.url] = CachedImageUrl(System.currentTimeMillis(), imageUrl)
        return imageUrl
    }
'''
s = replace_once(s, old_image, new_image, "viewer image URL cache")

s = replace_once(
    s,
    '''    private fun buildRawImageRequest(imageUrl: String, referer: String): Request = Request.Builder()
        .url(imageUrl)
        .header("Referer", referer)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        .header("Cache-Control", "no-cache")
        .apply {
''',
    '''    private fun buildRawImageRequest(imageUrl: String, referer: String): Request = Request.Builder()
        .url(imageUrl)
        .header("Referer", referer)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        .apply {
''',
    "restore normal image caching",
)

old_account = '''    override suspend fun getSourceAccount(): SourceAccount? {
        syncWebViewCookies(save = true, onlyMissing = true)
        val id = memberId()
        if (id.isBlank() || passHash().isBlank()) return null
        val cachedName = preferences.getString(PREF_ACCOUNT_NAME, "").orEmpty()
        val cachedAvatar = preferences.getString(PREF_ACCOUNT_AVATAR, "").orEmpty().takeIf(String::isNotBlank)
        if (cachedName.isNotBlank()) {
            return SourceAccount(id, cachedName, cachedAvatar, "$FORUMS_URL/index.php?showuser=$id")
        }
        return runCatching { fetchForumProfile(id, true) }.getOrElse {
            SourceAccount(id, "EH 用户 #$id", null, "$FORUMS_URL/index.php?showuser=$id")
        }
    }
'''
new_account = '''    override suspend fun getSourceAccount(): SourceAccount? {
        syncWebViewCookies(save = true)
        val id = memberId()
        if (id.isBlank() || passHash().isBlank()) return null
        if (!sessionValidated()) {
            runCatching { validateSession(syncFromWebView = false) }.getOrElse { return null }
        }
        val cachedName = preferences.getString(PREF_ACCOUNT_NAME, "").orEmpty()
        val cachedAvatar = preferences.getString(PREF_ACCOUNT_AVATAR, "").orEmpty().takeIf(String::isNotBlank)
        if (cachedName.isNotBlank()) {
            return SourceAccount(id, cachedName, cachedAvatar, "$FORUMS_URL/index.php?showuser=$id")
        }
        return runCatching { fetchForumProfile(id, true) }.getOrElse {
            SourceAccount(id, "EH 用户 #$id", null, "$FORUMS_URL/index.php?showuser=$id")
        }
    }
'''
s = replace_once(s, old_account, new_account, "AccountSource validation")

old_status_pref = '''        val accountStatusPreference = Preference(screen.context).apply {
            key = "ehp_account_status"
            title = accountStatusTitle()
            summary = accountStatusSummary()
            setOnPreferenceClickListener { true }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
'''
new_status_pref = '''        val accountStatusPreference = Preference(screen.context).apply {
            key = "ehp_account_status"
            title = accountStatusTitle()
            summary = accountStatusSummary()
        }.also(screen::addPreference)
        accountStatusPreference.setOnPreferenceClickListener {
            refreshAccountFromWebView(screen, accountStatusPreference, showToastOnResult = true)
            true
        }

        Preference(screen.context).apply {
            key = "ehp_web_login"
            title = "网页登录（推荐）"
            summary = "打开宿主内置 WebView 的 E-Hentai 论坛登录页；登录成功后自动获取 Cookie、验证账号并刷新上方状态"
            setOnPreferenceClickListener {
                openWebLogin(screen, accountStatusPreference)
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
'''
s = replace_once(s, old_status_pref, new_status_pref, "web login preference")

old_manual = '''        EditTextPreference(screen.context).apply {
            key = PREF_MEMBER_ID
            title = "ipb_member_id"
            summary = "也可直接粘贴 Cookie 值"
        }.also(screen::addPreference)
        EditTextPreference(screen.context).apply {
            key = PREF_PASS_HASH
            title = "ipb_pass_hash"
            summary = "也可直接粘贴 Cookie 值"
        }.also(screen::addPreference)
        EditTextPreference(screen.context).apply {
            key = PREF_IGNEOUS
            title = "igneous"
            summary = "里站常用 Cookie；没有可先留空并执行验证"
        }.also(screen::addPreference)
        EditTextPreference(screen.context).apply {
            key = PREF_STAR
            title = "star"
            summary = "可选 Cookie，兼容 Venera / JHenTai 登录数据"
        }.also(screen::addPreference)
'''
new_manual = '''        EditTextPreference(screen.context).apply {
            key = PREF_MEMBER_ID
            title = "ipb_member_id"
            summary = "也可直接粘贴 Cookie 值；填写完整后会自动同步到网页并尝试验证"
            installManualCookieListener(screen, accountStatusPreference)
        }.also(screen::addPreference)
        EditTextPreference(screen.context).apply {
            key = PREF_PASS_HASH
            title = "ipb_pass_hash"
            summary = "也可直接粘贴 Cookie 值；填写完整后会自动同步到网页并尝试验证"
            installManualCookieListener(screen, accountStatusPreference)
        }.also(screen::addPreference)
        EditTextPreference(screen.context).apply {
            key = PREF_IGNEOUS
            title = "igneous"
            summary = "里站常用 Cookie；修改后自动同步到宿主 WebView"
            installManualCookieListener(screen, accountStatusPreference)
        }.also(screen::addPreference)
        EditTextPreference(screen.context).apply {
            key = PREF_STAR
            title = "star"
            summary = "可选 Cookie，兼容 Venera / JHenTai 登录数据；修改后自动同步"
            installManualCookieListener(screen, accountStatusPreference)
        }.also(screen::addPreference)
'''
s = replace_once(s, old_manual, new_manual, "manual cookie listeners")

s = replace_once(
    s,
    '''        actionPreference(screen, "ehp_sync_webview", "同步 WebView Cookie", "如果你已经在宿主 WebView 登录 E-Hentai，可将 Cookie 同步到扩展") {
            val count = syncWebViewCookies(save = true)
            val status = if (count > 0) "已同步 $count 个 Cookie，请继续点击验证账号" else "没有检测到可同步的登录 Cookie"
            preferences.edit()
                .putBoolean(PREF_SESSION_VALIDATED, false)
                .putString(PREF_AUTH_RESULT, status)
                .apply()
            updateAccountStatusPreference(accountStatusPreference)
            showToast(screen, status)
        }
''',
    '''        actionPreference(screen, "ehp_sync_webview", "重新读取网页登录 Cookie", "强制从宿主 WebView 覆盖同步 Cookie，并自动验证账号状态") {
            val result = runCatching { importAndValidateWebViewCookies(force = true) }
            showResult(screen, result, "网页登录检测完成")
            updateAccountStatusPreference(accountStatusPreference)
        }
''',
    "explicit WebView sync",
)

s = replace_once(
    s,
    '''        actionPreference(screen, "ehp_logout", "退出登录", "清除扩展保存的账号 Cookie 和登录状态") {
            logout()
            preferences.edit().putString(PREF_AUTH_RESULT, "已退出登录").apply()
            updateAccountStatusPreference(accountStatusPreference)
            showToast(screen, "已退出登录")
        }
    }

    private fun loginWithPassword(): String {
''',
    '''        actionPreference(screen, "ehp_logout", "退出登录", "清除扩展保存的账号 Cookie 和登录状态") {
            logout()
            preferences.edit().putString(PREF_AUTH_RESULT, "已退出登录").apply()
            updateAccountStatusPreference(accountStatusPreference)
            showToast(screen, "已退出登录")
        }

        refreshAccountFromWebView(screen, accountStatusPreference, showToastOnResult = false)
    }

    private fun openWebLogin(screen: PreferenceScreen, accountStatusPreference: Preference) {
        val context = screen.context
        val intent = Intent().apply {
            setClassName(context.packageName, HOST_WEBVIEW_ACTIVITY)
            putExtra(HOST_WEBVIEW_URL_KEY, FORUM_LOGIN_URL)
            putExtra(HOST_WEBVIEW_SOURCE_KEY, id)
            putExtra(HOST_WEBVIEW_TITLE_KEY, "E-Hentai 账号登录")
        }
        val opened = runCatching { context.startActivity(intent) }.isSuccess
        if (!opened) {
            val text = "当前宿主无法从扩展直接打开内置 WebView。请回到漫画源页面点击“网页”，未登录时会直接进入论坛登录页；登录完成后再点本页登录状态刷新。"
            preferences.edit().putString(PREF_AUTH_RESULT, text).apply()
            updateAccountStatusPreference(accountStatusPreference)
            showToast(screen, text)
            return
        }
        preferences.edit()
            .putBoolean(PREF_SESSION_VALIDATED, false)
            .putString(PREF_AUTH_RESULT, "已打开网页登录，正在等待 Cookie…")
            .apply()
        updateAccountStatusPreference(accountStatusPreference)
        startWebLoginCookieWatch(screen, accountStatusPreference)
    }

    private fun startWebLoginCookieWatch(screen: PreferenceScreen, accountStatusPreference: Preference) {
        val generation = ++webLoginWatchGeneration
        var lastAttemptSignature = Int.MIN_VALUE
        var validationRunning = false

        fun poll(attempt: Int) {
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    if (generation != webLoginWatchGeneration) return@postDelayed
                    val webCookies = readWebViewAuthCookies()
                    val member = webCookies["ipb_member_id"].orEmpty()
                    val hash = webCookies["ipb_pass_hash"].orEmpty()
                    val signature = webCookies.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value}" }.hashCode()
                    if (member.isNotBlank() && hash.isNotBlank() && signature != lastAttemptSignature && !validationRunning) {
                        lastAttemptSignature = signature
                        saveWebViewAuthCookies(webCookies, onlyMissing = false)
                        preferences.edit()
                            .putBoolean(PREF_SESSION_VALIDATED, false)
                            .putString(PREF_AUTH_RESULT, "已自动获取网页登录 Cookie，正在验证账号…")
                            .apply()
                        updateAccountStatusPreference(accountStatusPreference)
                        validationRunning = true
                        client.dispatcher.executorService.execute {
                            val result = runCatching { validateSession(syncFromWebView = false) }
                            if (result.isSuccess) {
                                ++webLoginWatchGeneration
                                showResult(screen, result, "网页登录成功")
                                updateAccountStatusPreference(accountStatusPreference)
                            } else {
                                validationRunning = false
                                showResult(screen, result, "网页登录尚未完成")
                                updateAccountStatusPreference(accountStatusPreference)
                                if (generation == webLoginWatchGeneration && attempt < WEB_LOGIN_MAX_POLLS) poll(attempt + 1)
                            }
                        }
                    } else if (attempt < WEB_LOGIN_MAX_POLLS) {
                        poll(attempt + 1)
                    } else {
                        val text = if (member.isBlank() || hash.isBlank()) {
                            "网页登录等待超时，尚未检测到完整登录 Cookie；登录完成后点击上方“登录状态”即可重新检测。"
                        } else {
                            "检测到 Cookie，但自动验证未完成；点击上方“登录状态”可重新验证。"
                        }
                        preferences.edit().putString(PREF_AUTH_RESULT, text).apply()
                        updateAccountStatusPreference(accountStatusPreference)
                    }
                },
                WEB_LOGIN_POLL_MS,
            )
        }
        poll(0)
    }

    private fun refreshAccountFromWebView(
        screen: PreferenceScreen,
        accountStatusPreference: Preference,
        showToastOnResult: Boolean,
    ) {
        val webCookies = readWebViewAuthCookies()
        if (webCookies.isNotEmpty()) saveWebViewAuthCookies(webCookies, onlyMissing = false)
        updateAccountStatusPreference(accountStatusPreference)
        if (!isLoggedIn()) {
            if (showToastOnResult) showToast(screen, "未检测到完整登录 Cookie")
            return
        }
        client.dispatcher.executorService.execute {
            val result = runCatching { validateSession(syncFromWebView = false) }
            val prefix = if (showToastOnResult) "登录状态刷新" else "自动检测网页登录"
            val text = result.fold({ "$prefix：$it" }, { "$prefix失败：${it.message}" })
            preferences.edit().putString(PREF_AUTH_RESULT, text).apply()
            updateAccountStatusPreference(accountStatusPreference)
            if (showToastOnResult) showToast(screen, text)
        }
    }

    private fun installManualCookieListener(screen: PreferenceScreen, accountStatusPreference: Preference) {
        setOnPreferenceChangeListener { _, _ ->
            Handler(Looper.getMainLooper()).post {
                promoteManualCookies()
                syncStoredCookiesToWebView()
                preferences.edit()
                    .putBoolean(PREF_SESSION_VALIDATED, false)
                    .putString(PREF_AUTH_RESULT, "手动 Cookie 已更新并同步到网页")
                    .apply()
                updateAccountStatusPreference(accountStatusPreference)
                if (isLoggedIn()) {
                    client.dispatcher.executorService.execute {
                        val result = runCatching { validateSession(syncFromWebView = false) }
                        showResult(screen, result, "手动 Cookie 自动验证")
                        updateAccountStatusPreference(accountStatusPreference)
                    }
                }
            }
            true
        }
    }

    private fun importAndValidateWebViewCookies(force: Boolean): String {
        val webCookies = readWebViewAuthCookies()
        if (webCookies.isEmpty()) throw IOException("没有检测到宿主 WebView Cookie")
        saveWebViewAuthCookies(webCookies, onlyMissing = !force)
        if (memberId().isBlank() || passHash().isBlank()) throw IOException("已读取 WebView Cookie，但缺少 ipb_member_id / ipb_pass_hash")
        preferences.edit().putBoolean(PREF_SESSION_VALIDATED, false).apply()
        return validateSession(syncFromWebView = false)
    }

    private fun loginWithPassword(): String {
''',
    "web login helpers",
)

old_sync = '''    private fun syncWebViewCookies(save: Boolean, onlyMissing: Boolean = false): Int {
        var count = 0
        listOf(FORUMS_URL, EH_URL, EX_URL).forEach { url ->
            webViewCookies.getCookie(url)?.split(';')?.forEach { raw ->
                val part = raw.trim()
                val index = part.indexOf('=')
                if (index <= 0) return@forEach
                val name = part.substring(0, index)
                val value = part.substring(index + 1)
                if (name in AUTH_COOKIE_KEYS && value.isNotBlank()) {
                    if (save && (!onlyMissing || authCookieValue(name).isBlank())) {
                        preferences.edit().putString(cookiePref(name), value).apply()
                    }
                    count++
                }
            }
        }
        return count
    }
'''
new_sync = '''    private fun readWebViewAuthCookies(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        listOf(FORUMS_URL, EH_URL, EX_URL).forEach { url ->
            webViewCookies.getCookie(url)?.split(';')?.forEach { raw ->
                val part = raw.trim()
                val index = part.indexOf('=')
                if (index <= 0) return@forEach
                val name = part.substring(0, index)
                val value = part.substring(index + 1)
                if (name in AUTH_COOKIE_KEYS && value.isNotBlank()) result[name] = value
            }
        }
        return result
    }

    private fun saveWebViewAuthCookies(cookies: Map<String, String>, onlyMissing: Boolean) {
        if (cookies.isEmpty()) return
        val editor = preferences.edit()
        cookies.forEach { (name, value) ->
            if (!onlyMissing || authCookieValue(name).isBlank()) editor.putString(cookiePref(name), value)
        }
        editor.apply()
    }

    private fun syncWebViewCookies(save: Boolean, onlyMissing: Boolean = false): Int {
        val cookies = readWebViewAuthCookies()
        if (save) saveWebViewAuthCookies(cookies, onlyMissing)
        return cookies.size
    }
'''
s = replace_once(s, old_sync, new_sync, "WebView cookie reader")

s = replace_once(
    s,
    '''    private fun accountStatusTitle(): String = when {
        sessionValidated() -> "账号状态：已登录"
        isLoggedIn() -> "账号状态：待验证"
        else -> "账号状态：未登录"
    }

    private fun accountStatusSummary(): String {
        val state = when {
            sessionValidated() -> {
                val name = preferences.getString(PREF_ACCOUNT_NAME, "").orEmpty().ifBlank { "E-Hentai 用户 #${memberId()}" }
                val exStatus = if (preferences.getBoolean(PREF_EX_ACCESS, false)) "ExHentai：可用" else "ExHentai：无权限或未通过验证"
                "$name\\nE-Hentai：已验证 · $exStatus"
            }
            isLoggedIn() -> "已检测到登录 Cookie，但尚未验证。请点击“验证账号与里站权限”。"
            else -> "未登录。可使用账号密码、WebView Cookie 或手动 Cookie 登录。"
        }
        val lastResult = preferences.getString(PREF_AUTH_RESULT, "").orEmpty()
        return if (lastResult.isBlank()) state else "$state\\n最近操作：$lastResult"
    }
''',
    '''    private fun accountStatusTitle(): String = when {
        sessionValidated() -> "✅ 登录状态：已登录"
        isLoggedIn() -> "⚠️ 登录状态：已获取 Cookie，待验证"
        else -> "❌ 登录状态：未登录"
    }

    private fun accountStatusSummary(): String {
        val state = when {
            sessionValidated() -> {
                val name = preferences.getString(PREF_ACCOUNT_NAME, "").orEmpty().ifBlank { "E-Hentai 用户 #${memberId()}" }
                val exStatus = if (preferences.getBoolean(PREF_EX_ACCESS, false)) "ExHentai：可用" else "ExHentai：无权限或未通过验证"
                "账号：$name\\nE-Hentai：已验证 · $exStatus\\n点击此项可重新读取网页登录 Cookie 并验证"
            }
            isLoggedIn() -> "已检测到 ipb_member_id / ipb_pass_hash，但还没有完成有效性验证。点击此项立即验证。"
            else -> "未登录。推荐点击下一项“网页登录（推荐）”；也支持账号密码或手动 Cookie。"
        }
        val lastResult = preferences.getString(PREF_AUTH_RESULT, "").orEmpty()
        return if (lastResult.isBlank()) state else "$state\\n最近操作：$lastResult"
    }

    private fun accountFilterStatus(): String = when {
        sessionValidated() -> {
            val name = preferences.getString(PREF_ACCOUNT_NAME, "").orEmpty().ifBlank { "EH 用户 #${memberId()}" }
            "账号：已登录 · $name · ${if (preferences.getBoolean(PREF_EX_ACCESS, false)) "里站可用" else "仅表站/里站未验证"}"
        }
        isLoggedIn() -> "账号：已获取 Cookie，待验证"
        else -> "账号：未登录 · 可在漫画源设置中使用网页登录"
    }
''',
    "visible account status",
)

s = replace_once(
    s,
    "    private data class CachedDetail(val time: Long, val value: DetailBundle)\n",
    "    private data class CachedDetail(val time: Long, val value: DetailBundle)\n    private data class CachedImageUrl(val time: Long, val url: String)\n",
    "image cache model",
)

s = replace_once(
    s,
    '''        private const val FORUMS_URL = "https://forums.e-hentai.org"
        private const val DETAIL_CACHE_MS = 5 * 60 * 1000L
        private const val READER_REVISION = "4"
''',
    '''        private const val FORUMS_URL = "https://forums.e-hentai.org"
        private const val FORUM_LOGIN_URL = "$FORUMS_URL/index.php?act=Login&CODE=00"
        private const val DETAIL_CACHE_MS = 5 * 60 * 1000L
        private const val IMAGE_URL_CACHE_MS = 30 * 60 * 1000L
        private const val READER_REVISION = "4"
        private const val HOST_WEBVIEW_ACTIVITY = "eu.kanade.tachiyomi.ui.webview.WebViewActivity"
        private const val HOST_WEBVIEW_URL_KEY = "url_key"
        private const val HOST_WEBVIEW_SOURCE_KEY = "source_key"
        private const val HOST_WEBVIEW_TITLE_KEY = "title_key"
        private const val WEB_LOGIN_POLL_MS = 1500L
        private const val WEB_LOGIN_MAX_POLLS = 120
''',
    "web login constants",
)

SRC.write_text(s, "utf-8")


g = GRADLE.read_text("utf-8")
g = replace_once(g, "    versionCode = 4\n", "    versionCode = 5\n", "versionCode bump")
GRADLE.write_text(g, "utf-8")


d = DOC.read_text("utf-8")
d = d.replace(
    "2026-09-05 当前状态：**E-Hentai Plus v1.6.4（Android `versionCode=106004`，源码递增号 `versionCode=4`）的当前 Keiyoushi Spotless、Release 构建、Lint、公共 reader 实链路、长期固定签名、测试仓库生成和公开 `repo/test` 发布均已验证通过；正文解码、账号登录和里站权限仍必须以用户 MX 实机复测为最终判据。**",
    "2026-09-05 当前状态：**E-Hentai Plus v1.6.5（Android `versionCode=106005`，源码递增号 `versionCode=5`）进入实机优化候选：v1.6.4 已由用户确认正文恢复可读，但图片加载速度仍偏慢，且登录状态/网页登录体验仍需继续优化。v1.6.5 构建与发布验证完成前不得视为已验证。**",
)
if "## v5 实机优化候选（2026-09-05）" not in d:
    d += '''\n\n## v5 实机优化候选（2026-09-05）\n\n基于 v1.6.4 用户实机反馈：正文已经恢复可读，decoder 问题暂时视为实机修复成功；本轮继续优化加载速度与账号登录体验。\n\n- 正常 viewer 与正文图片请求恢复 HTTP/宿主缓存，不再对每一页无条件发送 `Cache-Control: no-cache`；v4 的图片文件头校验、错误响应拦截和 `nl(...)` 备用图片服务器重试继续保留。\n- 新增 30 分钟 viewer→最终图片 URL 内存缓存，重复打开、返回上一页或宿主重试时不再重复请求同一 viewer 页面。\n- `READER_REVISION` 保持 4，不主动清掉已经确认可用的 v4 图片缓存，避免升级后重新全量冷加载。\n- 未登录时漫画源“网页”主页改为 E-Hentai 论坛登录页，便于直接在宿主内置 WebView 登录。\n- 设置页新增“网页登录（推荐）”：通过宿主自己的 WebViewActivity 打开论坛登录页；登录过程中轮询 Android WebView CookieManager，一旦获得 `ipb_member_id` / `ipb_pass_hash` 就自动写入扩展、验证账号、检测 ExHentai 权限并刷新登录状态。\n- 如果宿主不兼容该内部 WebView Activity，保留“漫画源页面 → 网页”登录的兼容回退，不直接崩溃。\n- 手动 Cookie 字段修改后自动同步回宿主 WebView；当 `ipb_member_id` / `ipb_pass_hash` 齐全时自动尝试验证，避免“字段填好了但网页仍显示未登录”。\n- `AccountSource.getSourceAccount()` 在检测到 Cookie 但未验证时会自动验证；失效 Cookie 不再作为已登录账号返回给 MX。\n- 设置页账号状态改为醒目的 `✅ 已登录 / ⚠️ 已获取 Cookie，待验证 / ❌ 未登录`，点击状态本身即可强制重新读取网页登录 Cookie 并验证。\n- 搜索筛选顶部同步显示当前账号状态，便于不进入设置页时快速确认。\n\n验证边界：v1.6.5 仍需当前 Keiyoushi Spotless、Release、Lint、固定签名、公共 reader smoke 与测试仓库发布验证；网页登录自动 Cookie 捕获、状态刷新和实际加载速度改善必须以 MX 实机结果为准。\n'''
DOC.write_text(d, "utf-8")

print("E-Hentai Plus v5 patch applied")
