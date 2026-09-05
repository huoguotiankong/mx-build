from pathlib import Path

ROOT = Path("source")
KT = ROOT / "src/zh/ehentaiplus/src/eu/kanade/tachiyomi/extension/zh/ehentaiplus/EHentaiPlus.kt"
GRADLE = ROOT / "src/zh/ehentaiplus/build.gradle.kts"
DOC = ROOT / "docs/sources/ehentaiplus.md"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, found {count}")
    return text.replace(old, new, 1)


s = KT.read_text("utf-8")

s = replace_once(
    s,
    "import okhttp3.HttpUrl\n",
    "import okhttp3.HttpUrl\nimport okhttp3.Interceptor\n",
    "Interceptor import",
)
s = replace_once(
    s,
    "import org.jsoup.Jsoup\n",
    "import org.jsoup.Jsoup\nimport org.jsoup.nodes.Document\n",
    "Document import",
)

old_client = '''    override fun OkHttpClient.Builder.configureClient() = apply {
        connectTimeout(12, TimeUnit.SECONDS)
        readTimeout(60, TimeUnit.SECONDS)
        addInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host.lowercase(Locale.ROOT)
            val builder = request.newBuilder()
            if (host in EH_COOKIE_HOSTS || host.endsWith(".e-hentai.org") || host == "exhentai.org") {
                val cookie = cookieHeader()
                if (cookie.isNotBlank()) builder.header("Cookie", cookie)
                if (request.header("Referer").isNullOrBlank()) {
                    val referer = if (host.contains("forums")) {
                        FORUMS_URL
                    } else if (host == "exhentai.org") {
                        "$EX_URL/"
                    } else {
                        "$EH_URL/"
                    }
                    builder.header("Referer", referer)
                }
            }
            val isImageRequest = request.header(IMAGE_REQUEST_HEADER) == "1"
            if (isImageRequest) builder.removeHeader(IMAGE_REQUEST_HEADER)
            val response = chain.proceed(builder.build())
            captureSetCookies(response)
            if (isImageRequest) validateImageResponse(response) else response
        }
    }

    private fun validateImageResponse(response: Response): Response {
        val contentType = response.header("Content-Type").orEmpty().substringBefore(';').trim()
        if (contentType.startsWith("image/", ignoreCase = true)) return response
        val message = "正文图片服务器没有返回有效图片：HTTP ${response.code} · ${contentType.ifBlank { "未知 Content-Type" }}。若刚从旧测试版升级，请刷新章节并清除一次阅读缓存。"
        response.close()
        throw IOException(message)
    }
'''
new_client = '''    override fun OkHttpClient.Builder.configureClient() = apply {
        connectTimeout(12, TimeUnit.SECONDS)
        readTimeout(60, TimeUnit.SECONDS)
        addInterceptor { chain ->
            val request = chain.request()
            val isImageRequest = request.header(IMAGE_REQUEST_HEADER) == "1"
            val viewerUrl = request.header(IMAGE_VIEWER_HEADER)
            val host = request.url.host.lowercase(Locale.ROOT)
            val builder = request.newBuilder()
                .removeHeader(IMAGE_REQUEST_HEADER)
                .removeHeader(IMAGE_VIEWER_HEADER)
            if (host in EH_COOKIE_HOSTS || host.endsWith(".e-hentai.org") || host == "exhentai.org") {
                val cookie = cookieHeader()
                if (cookie.isNotBlank()) builder.header("Cookie", cookie)
                if (request.header("Referer").isNullOrBlank()) {
                    val referer = if (host.contains("forums")) {
                        FORUMS_URL
                    } else if (host == "exhentai.org") {
                        "$EX_URL/"
                    } else {
                        "$EH_URL/"
                    }
                    builder.header("Referer", referer)
                }
            }
            val response = chain.proceed(builder.build())
            captureSetCookies(response)
            when {
                !isImageRequest -> response
                isDecodableImageResponse(response) -> response
                else -> {
                    val firstFailure = imageFailureDescription(response)
                    response.close()
                    retryImageResponse(chain, viewerUrl, firstFailure)
                }
            }
        }
    }

    private fun isDecodableImageResponse(response: Response): Boolean {
        if (!response.isSuccessful) return false
        val contentType = response.header("Content-Type").orEmpty().substringBefore(';').trim()
        if (!contentType.startsWith("image/", ignoreCase = true)) return false
        return hasImageMagic(response)
    }

    private fun hasImageMagic(response: Response): Boolean = runCatching {
        val bytes = response.body.source().peek().readByteArray(16)
        fun b(index: Int) = bytes[index].toInt() and 0xFF
        when {
            bytes.size >= 3 && b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF -> true // JPEG
            bytes.size >= 8 && b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E && b(3) == 0x47 && b(4) == 0x0D && b(5) == 0x0A && b(6) == 0x1A && b(7) == 0x0A -> true // PNG
            bytes.size >= 6 && b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x38 && (b(4) == 0x37 || b(4) == 0x39) && b(5) == 0x61 -> true // GIF
            bytes.size >= 12 && b(0) == 0x52 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x46 && b(8) == 0x57 && b(9) == 0x45 && b(10) == 0x42 && b(11) == 0x50 -> true // WebP
            bytes.size >= 12 && b(4) == 0x66 && b(5) == 0x74 && b(6) == 0x79 && b(7) == 0x70 -> true // AVIF/HEIF family
            else -> false
        }
    }.getOrDefault(false)

    private fun imageFailureDescription(response: Response): String {
        val contentType = response.header("Content-Type").orEmpty().substringBefore(';').trim().ifBlank { "未知 Content-Type" }
        return "HTTP ${response.code} · $contentType · ${response.request.url.host}"
    }

    private fun retryImageResponse(chain: Interceptor.Chain, viewerUrl: String?, firstFailure: String): Response {
        val viewer = viewerUrl?.takeIf(String::isNotBlank)
            ?: throw IOException("正文图片无效：$firstFailure；缺少 viewer 地址，无法自动切换图片服务器")
        val firstDoc = fetchViewerDocument(chain, viewer)
        val reloadToken = Regex("nl\\('([^']+)'\\)")
            .find(firstDoc.selectFirst("#loadfail")?.attr("onclick").orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?: throw IOException("正文图片无效：$firstFailure；当前页面没有备用图片服务器 token")
        val retryViewerUrl = Uri.parse(viewer).buildUpon().appendQueryParameter("nl", reloadToken).build().toString()
        val retryDoc = fetchViewerDocument(chain, retryViewerUrl)
        val retryImageUrl = retryDoc.selectFirst("#img")?.attr("abs:src")?.takeIf(String::isNotBlank)
            ?: throw IOException("正文图片无效：$firstFailure；备用 viewer 未返回图片地址")
        if (retryImageUrl == "https://ehgt.org/g/509.gif" || retryImageUrl == "https://exhentai.org/img/509.gif") {
            throw IOException("E-Hentai 图片配额已用尽，请稍后再试或在 My Home 检查 Image Limits")
        }
        val retryRequest = buildRawImageRequest(retryImageUrl, retryViewerUrl)
        val retryResponse = chain.proceed(retryRequest)
        captureSetCookies(retryResponse)
        if (isDecodableImageResponse(retryResponse)) return retryResponse
        val retryFailure = imageFailureDescription(retryResponse)
        retryResponse.close()
        throw IOException("正文图片服务器返回无效数据：首次 $firstFailure；备用线路 $retryFailure")
    }

    private fun fetchViewerDocument(chain: Interceptor.Chain, url: String): Document {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Cache-Control", "no-cache")
            .apply {
                cookieHeader().takeIf(String::isNotBlank)?.let { header("Cookie", it) }
            }
            .build()
        return chain.proceed(request).use { response ->
            captureSetCookies(response)
            if (!response.isSuccessful) throw IOException("备用 viewer 请求失败：HTTP ${response.code}")
            Jsoup.parse(response.body.string(), response.request.url.toString())
        }
    }
'''
s = replace_once(s, old_client, new_client, "reader interceptor hardening")

old_reader = '''    override suspend fun getImageUrl(page: Page): String = client.get(page.url).use { response ->
        EHParser.parseImageInfo(response, originalImages()).imageUrl
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: throw IllegalStateException("正文图片地址尚未解析")
        return buildImageRequest(imageUrl, page.url)
    }

    private fun buildImageRequest(imageUrl: String, referer: String): Request = Request.Builder()
        .url(imageUrl)
        .header("Referer", referer)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        .header(IMAGE_REQUEST_HEADER, "1")
        .apply {
            cookieHeader().takeIf(String::isNotBlank)?.let { header("Cookie", it) }
        }
        .build()

    private fun readerChapterUrl(raw: String): String = Uri.parse(raw)
        .buildUpon()
        .appendQueryParameter("mx_reader", READER_REVISION)
        .build()
        .toString()
'''
new_reader = '''    override suspend fun getImageUrl(page: Page): String {
        val request = Request.Builder()
            .url(page.url)
            .headers(headers)
            .header("Cache-Control", "no-cache")
            .build()
        return client.newCall(request).awaitSuccess().use { response ->
            decorateImageUrl(EHParser.parseImageInfo(response, originalImages()).imageUrl)
        }
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: throw IllegalStateException("正文图片地址尚未解析")
        return buildImageRequest(imageUrl, page.url)
    }

    private fun buildImageRequest(imageUrl: String, referer: String): Request = buildRawImageRequest(networkImageUrl(imageUrl), referer)
        .newBuilder()
        .header(IMAGE_REQUEST_HEADER, "1")
        .header(IMAGE_VIEWER_HEADER, referer)
        .build()

    private fun buildRawImageRequest(imageUrl: String, referer: String): Request = Request.Builder()
        .url(imageUrl)
        .header("Referer", referer)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        .header("Cache-Control", "no-cache")
        .apply {
            cookieHeader().takeIf(String::isNotBlank)?.let { header("Cookie", it) }
        }
        .build()

    private fun decorateImageUrl(raw: String): String {
        val url = raw.toHttpUrlOrNull() ?: return raw
        return url.newBuilder().fragment("mxeh-$READER_REVISION").build().toString()
    }

    private fun networkImageUrl(raw: String): String {
        val url = raw.toHttpUrlOrNull() ?: return raw
        return url.newBuilder().fragment(null).build().toString()
    }

    private fun readerChapterUrl(raw: String): String = Uri.parse(raw)
        .buildUpon()
        .appendQueryParameter("mx_reader", READER_REVISION)
        .build()
        .toString()
'''
s = replace_once(s, old_reader, new_reader, "reader cache bust and retry metadata")

s = replace_once(
    s,
    "            canPost = isLoggedIn(),\n",
    "            canPost = sessionValidated(),\n",
    "comment capability validation",
)
s = replace_once(
    s,
    '        if (!isLoggedIn()) throw IOException("请先在 E-Hentai Plus 设置中登录账号")\n',
    '        if (!sessionValidated()) throw IOException("请先在 E-Hentai Plus 设置中登录并验证账号")\n',
    "comment posting validation",
)
s = replace_once(
    s,
    "        syncWebViewCookies(save = true)\n        val id = memberId()\n",
    "        syncWebViewCookies(save = true, onlyMissing = true)\n        val id = memberId()\n",
    "account source cookie merge",
)

old_status_pref = '''        val accountStatusPreference = EditTextPreference(screen.context).apply {
            key = "ehp_account_status"
            title = "账号状态（只读）"
            summary = accountStatusSummary()
            setOnPreferenceClickListener { true }
        }.also(screen::addPreference)
'''
new_status_pref = '''        val accountStatusPreference = Preference(screen.context).apply {
            key = "ehp_account_status"
            title = accountStatusTitle()
            summary = accountStatusSummary()
            isSelectable = false
        }.also(screen::addPreference)
'''
s = replace_once(s, old_status_pref, new_status_pref, "account status preference")

old_sync_action = '''        actionPreference(screen, "ehp_sync_webview", "同步 WebView Cookie", "如果你已经在宿主 WebView 登录 E-Hentai，可将 Cookie 同步到扩展") {
            val count = syncWebViewCookies(save = true)
            val status = if (count > 0) "已同步 $count 个 Cookie" else "没有检测到可同步的登录 Cookie"
            preferences.edit().putString(PREF_AUTH_RESULT, status).apply()
            updateAccountStatusPreference(accountStatusPreference)
            showToast(screen, status)
        }
'''
new_sync_action = '''        actionPreference(screen, "ehp_sync_webview", "同步 WebView Cookie", "如果你已经在宿主 WebView 登录 E-Hentai，可将 Cookie 同步到扩展") {
            val count = syncWebViewCookies(save = true)
            val status = if (count > 0) "已同步 $count 个 Cookie，请继续点击验证账号" else "没有检测到可同步的登录 Cookie"
            preferences.edit()
                .putBoolean(PREF_SESSION_VALIDATED, false)
                .putString(PREF_AUTH_RESULT, status)
                .apply()
            updateAccountStatusPreference(accountStatusPreference)
            showToast(screen, status)
        }
'''
s = replace_once(s, old_sync_action, new_sync_action, "webview sync status")

old_login_validate = '''    private fun loginWithPassword(): String {
        val username = preferences.getString(PREF_USERNAME, "").orEmpty().trim()
        val password = preferences.getString(PREF_PASSWORD, "").orEmpty()
        if (username.isBlank() || password.isBlank()) throw IOException("请输入论坛账号和密码")

        clearAuthCookies()
        val form = FormBody.Builder()
            .add("referer", "$FORUMS_URL/index.php?")
            .add("b", "")
            .add("bt", "")
            .add("UserName", username)
            .add("PassWord", password)
            .add("CookieDate", "365")
            .build()
        val request = Request.Builder()
            .url("$FORUMS_URL/index.php?act=Login&CODE=01")
            .header("Referer", "$FORUMS_URL/index.php?act=Login&CODE=00")
            .post(form)
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw IOException("论坛登录 HTTP ${response.code}")
            if (memberId().isBlank() || passHash().isBlank()) {
                val error = Jsoup.parse(body).selectFirst(".errorwrap,.message,.maintitle + div")?.text()?.trim()
                throw IOException(error?.takeIf(String::isNotBlank) ?: "账号或密码错误，未获取到登录 Cookie")
            }
        }
        preferences.edit().remove(PREF_PASSWORD).apply()
        syncStoredCookiesToWebView()
        return validateSession()
    }

    private fun validateSession(): String {
        syncWebViewCookies(save = true)
        val id = memberId()
        if (id.isBlank() || passHash().isBlank()) throw IOException("缺少 ipb_member_id / ipb_pass_hash")

        val profileRequest = Request.Builder().url("$FORUMS_URL/index.php?showuser=${enc(id)}").header("Referer", "$FORUMS_URL/").build()
        val name = client.newCall(profileRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException("论坛验证 HTTP ${response.code}")
            val doc = Jsoup.parse(response.body.string(), FORUMS_URL)
            doc.selectFirst(".home > b > a")?.text()?.trim()?.takeIf(String::isNotBlank)
                ?: doc.selectFirst("#profilename")?.text()?.trim()?.takeIf(String::isNotBlank)
                ?: throw IOException("Cookie 无效或登录已过期")
        }
        preferences.edit().putString(PREF_ACCOUNT_NAME, name).apply()

        val exOk = runCatching {
            val request = Request.Builder().url("$EX_URL/").header("Referer", "$EH_URL/").build()
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                response.isSuccessful && response.request.url.host == "exhentai.org" && body.trimStart().startsWith("<") && body.contains("itg")
            }
        }.getOrDefault(false)
        preferences.edit().putBoolean(PREF_EX_ACCESS, exOk).apply()
        syncStoredCookiesToWebView()
        return "$name · ${if (exOk) "ExHentai 里站可用" else "E-Hentai 登录有效；里站未验证通过"}"
    }
'''
new_login_validate = '''    private fun loginWithPassword(): String {
        val username = preferences.getString(PREF_USERNAME, "").orEmpty().trim()
        val password = preferences.getString(PREF_PASSWORD, "").orEmpty()
        if (username.isBlank() || password.isBlank()) throw IOException("请输入论坛账号和密码")

        clearAuthCookies(clearManual = true)
        val form = FormBody.Builder()
            .add("referer", "$FORUMS_URL/index.php?")
            .add("b", "")
            .add("bt", "")
            .add("UserName", username)
            .add("PassWord", password)
            .add("CookieDate", "365")
            .build()
        val request = Request.Builder()
            .url("$FORUMS_URL/index.php?act=Login&CODE=01")
            .header("Referer", "$FORUMS_URL/index.php?act=Login&CODE=00")
            .post(form)
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw IOException("论坛登录 HTTP ${response.code}")
            if (memberId().isBlank() || passHash().isBlank()) {
                val error = Jsoup.parse(body).selectFirst(".errorwrap,.message,.maintitle + div")?.text()?.trim()
                throw IOException(error?.takeIf(String::isNotBlank) ?: "账号或密码错误，未获取到登录 Cookie")
            }
        }
        preferences.edit().remove(PREF_PASSWORD).apply()
        val result = validateSession(syncFromWebView = false)
        syncStoredCookiesToWebView()
        return result
    }

    private fun validateSession(syncFromWebView: Boolean = true): String {
        if (syncFromWebView) {
            promoteManualCookies()
            syncWebViewCookies(save = true, onlyMissing = true)
        }
        val id = memberId()
        if (id.isBlank() || passHash().isBlank()) {
            preferences.edit().putBoolean(PREF_SESSION_VALIDATED, false).apply()
            throw IOException("缺少 ipb_member_id / ipb_pass_hash")
        }

        val name = runCatching {
            val profileRequest = Request.Builder().url("$FORUMS_URL/index.php?showuser=${enc(id)}").header("Referer", "$FORUMS_URL/").build()
            client.newCall(profileRequest).execute().use { response ->
                if (!response.isSuccessful) throw IOException("论坛验证 HTTP ${response.code}")
                val doc = Jsoup.parse(response.body.string(), FORUMS_URL)
                doc.selectFirst(".home > b > a")?.text()?.trim()?.takeIf(String::isNotBlank)
                    ?: doc.selectFirst("#profilename")?.text()?.trim()?.takeIf(String::isNotBlank)
                    ?: throw IOException("Cookie 无效或登录已过期")
            }
        }.getOrElse { error ->
            preferences.edit().putBoolean(PREF_SESSION_VALIDATED, false).apply()
            throw error
        }
        preferences.edit().putString(PREF_ACCOUNT_NAME, name).apply()

        val exOk = runCatching {
            val request = Request.Builder().url("$EX_URL/").header("Referer", "$EH_URL/").header("Cache-Control", "no-cache").build()
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                response.isSuccessful && response.request.url.host == "exhentai.org" && body.trimStart().startsWith("<") && body.contains("itg")
            }
        }.getOrDefault(false)
        preferences.edit()
            .putBoolean(PREF_SESSION_VALIDATED, true)
            .putBoolean(PREF_EX_ACCESS, exOk)
            .apply()
        syncStoredCookiesToWebView()
        return "$name · E-Hentai 已验证 · ${if (exOk) "ExHentai 里站可用" else "里站无权限或未通过验证"}"
    }
'''
s = replace_once(s, old_login_validate, new_login_validate, "login and validation flow")

old_logout_and_sync = '''    private fun logout() {
        clearAuthCookies()
        preferences.edit()
            .remove(PREF_ACCOUNT_NAME)
            .remove(PREF_ACCOUNT_AVATAR)
            .remove(PREF_EX_ACCESS)
            .remove(PREF_PASSWORD)
            .apply()
        listOf(EH_URL, EX_URL, FORUMS_URL).forEach { url ->
            AUTH_COOKIE_KEYS.forEach { key -> webViewCookies.setCookie(url, "$key=; Max-Age=0; path=/") }
        }
        webViewCookies.flush()
    }

    private fun clearAuthCookies() {
        val editor = preferences.edit()
        AUTH_COOKIE_KEYS.forEach { editor.remove(cookiePref(it)) }
        editor.apply()
    }

    private fun syncWebViewCookies(save: Boolean): Int {
        var count = 0
        listOf(FORUMS_URL, EH_URL, EX_URL).forEach { url ->
            webViewCookies.getCookie(url)?.split(';')?.forEach { raw ->
                val part = raw.trim()
                val index = part.indexOf('=')
                if (index <= 0) return@forEach
                val name = part.substring(0, index)
                val value = part.substring(index + 1)
                if (name in AUTH_COOKIE_KEYS && value.isNotBlank()) {
                    if (save) preferences.edit().putString(cookiePref(name), value).apply()
                    count++
                }
            }
        }
        return count
    }
'''
new_logout_and_sync = '''    private fun logout() {
        clearAuthCookies(clearManual = true)
        preferences.edit()
            .remove(PREF_ACCOUNT_NAME)
            .remove(PREF_ACCOUNT_AVATAR)
            .remove(PREF_EX_ACCESS)
            .remove(PREF_PASSWORD)
            .putBoolean(PREF_SESSION_VALIDATED, false)
            .apply()
        listOf(EH_URL, EX_URL, FORUMS_URL).forEach { url ->
            AUTH_COOKIE_KEYS.forEach { key -> webViewCookies.setCookie(url, "$key=; Max-Age=0; path=/") }
        }
        webViewCookies.flush()
    }

    private fun clearAuthCookies(clearManual: Boolean = false) {
        val editor = preferences.edit()
        AUTH_COOKIE_KEYS.forEach { editor.remove(cookiePref(it)) }
        if (clearManual) {
            listOf(PREF_MEMBER_ID, PREF_PASS_HASH, PREF_IGNEOUS, PREF_STAR).forEach(editor::remove)
        }
        editor.putBoolean(PREF_SESSION_VALIDATED, false).apply()
    }

    private fun syncWebViewCookies(save: Boolean, onlyMissing: Boolean = false): Int {
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
s = replace_once(s, old_logout_and_sync, new_logout_and_sync, "logout and cookie sync")

s = replace_once(
    s,
    '''            AUTH_COOKIE_KEYS.forEach { key ->
                val value = preferences.getString(cookiePref(key), "").orEmpty()
                if (value.isNotBlank()) webViewCookies.setCookie(url, "$key=$value; path=/")
            }
''',
    '''            AUTH_COOKIE_KEYS.forEach { key ->
                val value = authCookieValue(key)
                if (value.isNotBlank()) webViewCookies.setCookie(url, "$key=$value; path=/")
            }
''',
    "stored cookie webview sync",
)

old_cookie_access = '''    private fun cookieHeader(): String = buildList {
        add("nw=1")
        add("uconfig=${uconfigValue()}")
        AUTH_COOKIE_KEYS.forEach { key ->
            preferences.getString(cookiePref(key), "").orEmpty().takeIf(String::isNotBlank)?.let { add("$key=$it") }
        }
    }.joinToString("; ")
'''
new_cookie_access = '''    private fun cookieHeader(): String = buildList {
        add("nw=1")
        add("uconfig=${uconfigValue()}")
        AUTH_COOKIE_KEYS.forEach { key ->
            authCookieValue(key).takeIf(String::isNotBlank)?.let { add("$key=$it") }
        }
    }.joinToString("; ")

    private fun promoteManualCookies() {
        val editor = preferences.edit()
        mapOf(
            "ipb_member_id" to PREF_MEMBER_ID,
            "ipb_pass_hash" to PREF_PASS_HASH,
            "igneous" to PREF_IGNEOUS,
            "star" to PREF_STAR,
        ).forEach { (cookie, pref) ->
            preferences.getString(pref, "").orEmpty().trim().takeIf(String::isNotBlank)?.let { editor.putString(cookiePref(cookie), it) }
        }
        editor.apply()
    }

    private fun authCookieValue(name: String): String {
        preferences.getString(cookiePref(name), "").orEmpty().takeIf(String::isNotBlank)?.let { return it }
        val manualPref = when (name) {
            "ipb_member_id" -> PREF_MEMBER_ID
            "ipb_pass_hash" -> PREF_PASS_HASH
            "igneous" -> PREF_IGNEOUS
            "star" -> PREF_STAR
            else -> null
        }
        return manualPref?.let { preferences.getString(it, "").orEmpty().trim() }.orEmpty()
    }
'''
s = replace_once(s, old_cookie_access, new_cookie_access, "manual cookie request support")

old_accessors = '''    private fun memberId() = preferences.getString(cookiePref("ipb_member_id"), preferences.getString(PREF_MEMBER_ID, "").orEmpty()).orEmpty()
        .ifBlank { preferences.getString(PREF_MEMBER_ID, "").orEmpty() }
    private fun passHash() = preferences.getString(cookiePref("ipb_pass_hash"), preferences.getString(PREF_PASS_HASH, "").orEmpty()).orEmpty()
        .ifBlank { preferences.getString(PREF_PASS_HASH, "").orEmpty() }
    private fun igneous() = preferences.getString(cookiePref("igneous"), preferences.getString(PREF_IGNEOUS, "").orEmpty()).orEmpty()
        .ifBlank { preferences.getString(PREF_IGNEOUS, "").orEmpty() }
    private fun star() = preferences.getString(cookiePref("star"), preferences.getString(PREF_STAR, "").orEmpty()).orEmpty()
        .ifBlank { preferences.getString(PREF_STAR, "").orEmpty() }

    private fun isLoggedIn() = memberId().isNotBlank() && passHash().isNotBlank()
'''
new_accessors = '''    private fun memberId() = authCookieValue("ipb_member_id")
    private fun passHash() = authCookieValue("ipb_pass_hash")
    private fun igneous() = authCookieValue("igneous")
    private fun star() = authCookieValue("star")

    private fun isLoggedIn() = memberId().isNotBlank() && passHash().isNotBlank()
    private fun sessionValidated() = isLoggedIn() && preferences.getBoolean(PREF_SESSION_VALIDATED, false)
'''
s = replace_once(s, old_accessors, new_accessors, "cookie accessors")

old_status = '''    private fun accountStatusSummary(): String {
        val state = if (!isLoggedIn()) {
            "未登录 · 可使用账号密码、WebView Cookie 或手动 Cookie 登录"
        } else {
            val name = preferences.getString(PREF_ACCOUNT_NAME, "").orEmpty().ifBlank { "E-Hentai 用户 #${memberId()}" }
            val exStatus = if (preferences.getBoolean(PREF_EX_ACCESS, false)) "ExHentai 里站可用" else "里站未验证通过"
            "已登录：$name · $exStatus"
        }
        val lastResult = preferences.getString(PREF_AUTH_RESULT, "").orEmpty()
        return if (lastResult.isBlank()) state else "$state\\n最近操作：$lastResult"
    }

    private fun updateAccountStatusPreference(preference: Preference) {
        Handler(Looper.getMainLooper()).post { preference.summary = accountStatusSummary() }
    }
'''
new_status = '''    private fun accountStatusTitle(): String = when {
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

    private fun updateAccountStatusPreference(preference: Preference) {
        Handler(Looper.getMainLooper()).post {
            preference.title = accountStatusTitle()
            preference.summary = accountStatusSummary()
        }
    }
'''
s = replace_once(s, old_status, new_status, "explicit account state")

s = replace_once(
    s,
    '        private const val READER_REVISION = "3"\n        private const val IMAGE_REQUEST_HEADER = "X-MX-EH-Image"\n',
    '        private const val READER_REVISION = "4"\n        private const val IMAGE_REQUEST_HEADER = "X-MX-EH-Image"\n        private const val IMAGE_VIEWER_HEADER = "X-MX-EH-Viewer"\n',
    "reader revision",
)
s = replace_once(
    s,
    '        private const val PREF_AUTH_RESULT = "ehp.auth_result"\n',
    '        private const val PREF_AUTH_RESULT = "ehp.auth_result"\n        private const val PREF_SESSION_VALIDATED = "ehp.session_validated"\n',
    "session validation preference",
)

KT.write_text(s, "utf-8")

g = GRADLE.read_text("utf-8")
g = replace_once(g, "versionCode = 3", "versionCode = 4", "versionCode")
GRADLE.write_text(g, "utf-8")

d = DOC.read_text("utf-8")
marker = "## v4 实机修复候选（2026-09-05）"
if marker not in d:
    d += '''

## v4 实机修复候选（2026-09-05）

继续针对实机 `Failed to initialize decoder` 与账号状态不明确做防御性修复：

- 直接图片 URL 增加仅用于宿主缓存键的 `#mxeh-4` fragment。fragment 不会发送给图片服务器，但会让 MX 章节图片缓存与 v1-v3 的旧错误缓存彻底分离；此前只修改章节 URL，旧的图片 URL 缓存仍可能被复用。
- 图片响应不再只检查 `Content-Type: image/*`，同时检查 JPEG / PNG / GIF / WebP / AVIF/HEIF 文件头。站点/CDN 即使把错误页伪装成图片 MIME，也不会再写入阅读缓存。
- 图片响应无效时，扩展会利用当前 viewer 的 `nl(...)` reload token 自动请求备用图片服务器，再把备用图片响应交给宿主；不依赖 MX 内置 E-Hentai 专用的 retry 特判。
- viewer 和正文图片请求显式 `no-cache`，减少旧 viewer / CDN 错误响应被网络缓存复用。
- 手动填写的 `ipb_member_id` / `ipb_pass_hash` / `igneous` / `star` 现在真正进入请求 Cookie。v1-v3 虽然 UI 能读到手动字段，但 `cookieHeader()` 只发送捕获到的 Cookie，导致“看起来已登录、实际请求未登录”。
- 密码登录成功后不再立即从 WebView 反向同步 Cookie，避免刚获取的新论坛 Cookie 被 WebView 中的旧值覆盖；验证成功后再把已确认 Cookie 同步回 WebView。
- WebView Cookie 的普通后台同步改为“只补缺失值”；只有用户显式点击“同步 WebView Cookie”时才覆盖现有 Cookie。
- 新增 `sessionValidated` 状态，把“存在 Cookie”和“账号已验证”分开。设置页标题直接显示“账号状态：未登录 / 待验证 / 已登录”，摘要显示用户名、E-Hentai 验证结果、ExHentai 权限和最近操作。
- 发表评论能力只在账号真正验证成功后开启，避免仅存在失效 Cookie 时宿主误判为可发评论。
- 退出登录同时清除自动 Cookie、手动 Cookie 和验证状态，避免手动字段残留导致退出后仍显示已登录。

验证边界：本轮 CI 将继续验证当前 Keiyoushi Spotless、Release、Lint、固定签名、公开 reader 实链路和测试仓库；正文解码与账号状态仍以 MX 实机为最终判据。
'''
DOC.write_text(d, "utf-8")

print("E-Hentai Plus v4 patch applied")
