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
    "                    url = fresh.manga.url\n",
    "                    url = readerChapterUrl(fresh.manga.url)\n",
    "reader cache revision",
)

old_reader = '''    override suspend fun getImageUrl(page: Page): String = client.get(page.url).use { response ->
        normalizeImageUrl(EHParser.parseImageInfo(response, originalImages()).imageUrl)
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: throw IllegalStateException("正文图片地址尚未解析")
        return Request.Builder()
            .url(normalizeImageUrl(imageUrl))
            .header("Referer", page.url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "image/*,*/*;q=0.8")
            .build()
    }

    private fun normalizeImageUrl(raw: String): String {
        val url = raw.toHttpUrlOrNull() ?: return raw
        if (url.host != "s.exhentai.org") return raw
        return url.newBuilder().host("ehgt.org").build().toString()
    }
'''
new_reader = '''    override suspend fun getImageUrl(page: Page): String = client.get(page.url).use { response ->
        val info = EHParser.parseImageInfo(response, originalImages())
        info.reloadViewerUrl?.let { page.url = it }
        info.imageUrl
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl ?: throw IllegalStateException("正文图片地址尚未解析")
        return buildImageRequest(imageUrl, page.url)
    }

    override suspend fun getImage(page: Page): Response {
        val imageUrl = page.imageUrl ?: throw IllegalStateException("正文图片地址尚未解析")
        val candidates = listOfNotNull(imageUrl, alternateImageUrl(imageUrl)).distinct()
        var lastFailure = ""

        for (candidate in candidates) {
            val response = runCatching {
                client.newCall(buildImageRequest(candidate, page.url)).awaitSuccess()
            }.getOrElse { error ->
                lastFailure = error.message.orEmpty()
                continue
            }
            val contentType = response.header("Content-Type").orEmpty().substringBefore(';').trim()
            if (contentType.startsWith("image/", ignoreCase = true)) {
                page.imageUrl = candidate
                return response
            }
            lastFailure = "HTTP ${response.code} · ${contentType.ifBlank { "未知 Content-Type" }}"
            response.close()
        }

        throw IOException(
            "正文图片服务器没有返回有效图片" +
                lastFailure.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty() +
                "。请重试；若刚从旧测试版升级，请刷新章节并清除一次阅读缓存。",
        )
    }

    private fun buildImageRequest(imageUrl: String, referer: String): Request = Request.Builder()
        .url(imageUrl)
        .header("Referer", referer)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        .apply {
            cookieHeader().takeIf(String::isNotBlank)?.let { header("Cookie", it) }
        }
        .build()

    private fun alternateImageUrl(raw: String): String? {
        val url = raw.toHttpUrlOrNull() ?: return null
        val host = when (url.host) {
            "s.exhentai.org" -> "ehgt.org"
            "ehgt.org" -> "s.exhentai.org"
            else -> return null
        }
        return url.newBuilder().host(host).build().toString()
    }

    private fun readerChapterUrl(raw: String): String = Uri.parse(raw)
        .buildUpon()
        .appendQueryParameter("mx_reader", READER_REVISION)
        .build()
        .toString()
'''
s = replace_once(s, old_reader, new_reader, "reader image pipeline")

old_status = '''        val accountStatusPreference = Preference(screen.context).apply {
            key = "ehp_account_status"
            title = "账号状态"
            summary = accountStatusSummary()
        }.also(screen::addPreference)
'''
new_status = '''        val accountStatusPreference = EditTextPreference(screen.context).apply {
            key = "ehp_account_status"
            title = "账号状态（只读）"
            summary = accountStatusSummary()
            isEnabled = false
        }.also(screen::addPreference)
'''
s = replace_once(s, old_status, new_status, "persistent account status row")

old_login = '''        actionPreference(screen, "ehp_login", "账号密码登录", "按 JHenTai 的官方论坛登录流程获取 ipb_member_id / ipb_pass_hash，并检测里站权限") {
            val result = runCatching { loginWithPassword() }
            updateAccountStatusPreference(accountStatusPreference)
            showResult(screen, result, "登录成功")
        }
'''
new_login = '''        actionPreference(screen, "ehp_login", "账号密码登录", "按 JHenTai 的官方论坛登录流程获取 ipb_member_id / ipb_pass_hash，并检测里站权限") {
            val result = runCatching { loginWithPassword() }
            showResult(screen, result, "登录成功")
            updateAccountStatusPreference(accountStatusPreference)
        }
'''
s = replace_once(s, old_login, new_login, "login status ordering")

old_sync = '''        actionPreference(screen, "ehp_sync_webview", "同步 WebView Cookie", "如果你已经在宿主 WebView 登录 E-Hentai，可将 Cookie 同步到扩展") {
            val count = syncWebViewCookies(save = true)
            updateAccountStatusPreference(accountStatusPreference)
            showToast(screen, if (count > 0) "已同步 $count 个 Cookie" else "没有检测到可同步的登录 Cookie")
        }
        actionPreference(screen, "ehp_validate", "验证账号与里站权限", "读取论坛账号，并检测 ExHentai 是否可用") {
            val result = runCatching { validateSession() }
            updateAccountStatusPreference(accountStatusPreference)
            showResult(screen, result, "验证完成")
        }
'''
new_sync = '''        actionPreference(screen, "ehp_sync_webview", "同步 WebView Cookie", "如果你已经在宿主 WebView 登录 E-Hentai，可将 Cookie 同步到扩展") {
            val count = syncWebViewCookies(save = true)
            val status = if (count > 0) "已同步 $count 个 Cookie" else "没有检测到可同步的登录 Cookie"
            preferences.edit().putString(PREF_AUTH_RESULT, status).apply()
            updateAccountStatusPreference(accountStatusPreference)
            showToast(screen, status)
        }
        actionPreference(screen, "ehp_validate", "验证账号与里站权限", "读取论坛账号，并检测 ExHentai 是否可用") {
            val result = runCatching { validateSession() }
            showResult(screen, result, "验证完成")
            updateAccountStatusPreference(accountStatusPreference)
        }
'''
s = replace_once(s, old_sync, new_sync, "sync/validate status persistence")

old_logout = '''        actionPreference(screen, "ehp_logout", "退出登录", "清除扩展保存的账号 Cookie 和登录状态") {
            logout()
            updateAccountStatusPreference(accountStatusPreference)
            showToast(screen, "已退出登录")
        }
'''
new_logout = '''        actionPreference(screen, "ehp_logout", "退出登录", "清除扩展保存的账号 Cookie 和登录状态") {
            logout()
            preferences.edit().putString(PREF_AUTH_RESULT, "已退出登录").apply()
            updateAccountStatusPreference(accountStatusPreference)
            showToast(screen, "已退出登录")
        }
'''
s = replace_once(s, old_logout, new_logout, "logout status persistence")

old_summary = '''    private fun accountStatusSummary(): String {
        if (!isLoggedIn()) return "未登录 · 可使用账号密码、WebView Cookie 或手动 Cookie 登录"
        val name = preferences.getString(PREF_ACCOUNT_NAME, "").orEmpty().ifBlank { "E-Hentai 用户 #${memberId()}" }
        val exStatus = if (preferences.getBoolean(PREF_EX_ACCESS, false)) "ExHentai 里站可用" else "里站未验证通过"
        return "已登录：$name · $exStatus"
    }
'''
new_summary = '''    private fun accountStatusSummary(): String {
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
'''
s = replace_once(s, old_summary, new_summary, "account status summary")

old_show_result = '''    private fun showResult(screen: PreferenceScreen, result: Result<String>, prefix: String) {
        showToast(screen, result.fold({ "$prefix：$it" }, { "操作失败：${it.message}" }))
    }
'''
new_show_result = '''    private fun showResult(screen: PreferenceScreen, result: Result<String>, prefix: String) {
        val text = result.fold({ "$prefix：$it" }, { "操作失败：${it.message}" })
        preferences.edit().putString(PREF_AUTH_RESULT, text).apply()
        showToast(screen, text)
    }
'''
s = replace_once(s, old_show_result, new_show_result, "persist auth result")

s = replace_once(
    s,
    '        private const val PREF_ACCOUNT_AVATAR = "ehp.account_avatar"\n',
    '        private const val PREF_ACCOUNT_AVATAR = "ehp.account_avatar"\n        private const val PREF_AUTH_RESULT = "ehp.auth_result"\n',
    "auth result preference key",
)
s = replace_once(
    s,
    '        private const val DETAIL_CACHE_MS = 5 * 60 * 1000L\n',
    '        private const val DETAIL_CACHE_MS = 5 * 60 * 1000L\n        private const val READER_REVISION = "3"\n',
    "reader revision constant",
)

KT.write_text(s, "utf-8")

g = GRADLE.read_text("utf-8")
g = replace_once(g, "versionCode = 2", "versionCode = 3", "versionCode")
GRADLE.write_text(g, "utf-8")

d = DOC.read_text("utf-8")
marker = "## v3 实机修复候选（2026-09-05）"
if marker not in d:
    d += '''

## v3 实机修复候选（2026-09-05）

根据 v1.6.2 实机反馈，正文仍出现 `IllegalStateException: Failed to initialize decoder`，同时账号登录结果仍不够直观。本轮进一步对齐 MX 内置 E-Hentai 的真实阅读链路，并修正上一轮 CI 与扩展实际请求头不一致的问题：

- 正文图片请求对任意实际图片 CDN（包括 `*.hath.network`）显式携带当前 E-Hentai Cookie。v2 的公共 smoke test 会给最终图片请求发送 Cookie，但扩展自身只给 EH/EX 域名注入 Cookie，两者行为并不等价。
- `getImageUrl(Page)` 保留站点返回的原始 `#img` URL，并消费 `nl(...)` 重试地址更新 `page.url`，与 MX 内置 E-Hentai 的重试模型保持一致。
- 图片下载新增 Content-Type 校验；非 `image/*` 响应不再被当作图片写入阅读缓存，从而避免 HTML/错误页最终表现成模糊的 decoder 异常。
- `s.exhentai.org` / `ehgt.org` 只作为互相回退，不再无条件提前改写。
- 章节 URL 增加固定 `mx_reader=3` 阅读修订标记，以在刷新章节后绕开 v1/v2 已缓存的旧 PageList。旧版本已经产生的章节/图片磁盘缓存仍可能存在，因此实机复测同一本时需要先刷新章节；最可靠的验证方式是同时测试一本此前从未打开过的画廊。
- 设置页账号状态改用宿主稳定支持的 `EditTextPreference(Context)` 只读行，不再依赖 `PreferenceCompat` 的无 Context 基类兼容构造。
- 登录、验证、同步 Cookie、退出登录的最近结果会持久显示在“账号状态（只读）”摘要中，不再只依赖瞬时 Toast。

验证边界：构建、Lint、固定签名和公共 reader smoke 只能验证构建/网络链；本轮必须继续以 MX 实机正文解码和账号状态显示为最终判断。
'''
DOC.write_text(d, "utf-8")

print("E-Hentai Plus v3 patch applied")
