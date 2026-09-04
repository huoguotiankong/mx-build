from pathlib import Path

ROOT = Path("source")
KT = ROOT / "src/zh/ehentaiplus/src/eu/kanade/tachiyomi/extension/zh/ehentaiplus/EHentaiPlus.kt"
MODELS = ROOT / "src/zh/ehentaiplus/src/eu/kanade/tachiyomi/extension/zh/ehentaiplus/Models.kt"
GRADLE = ROOT / "src/zh/ehentaiplus/build.gradle.kts"
DOC = ROOT / "docs/sources/ehentaiplus.md"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, found {count}")
    return text.replace(old, new, 1)


s = KT.read_text("utf-8")
s = s.replace("import kotlinx.coroutines.runBlocking\n", "")

loopback_start = """        addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host != IMAGE_LOOPBACK_HOST) return@addInterceptor chain.proceed(request)
"""
start = s.find(loopback_start)
if start < 0:
    raise SystemExit("loopback image interceptor start not found")
next_interceptor = s.find("        addInterceptor { chain ->", start + len(loopback_start))
if next_interceptor < 0:
    raise SystemExit("cookie interceptor after loopback interceptor not found")
s = s[:start] + s[next_interceptor:]

old_pages = '''        return viewers.distinct().mapIndexed { index, viewerUrl ->
            Page(index, viewerUrl, "https://$IMAGE_LOOPBACK_HOST/#$viewerUrl")
        }
    }

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .header("Referer", page.url)
        .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        .build()
'''
new_pages = '''        return viewers.distinct().mapIndexed { index, viewerUrl ->
            Page(index, viewerUrl)
        }
    }

    override suspend fun getImageUrl(page: Page): String = client.get(page.url).use { response ->
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
s = replace_once(s, old_pages, new_pages, "reader page/image block")
s = replace_once(
    s,
    "            requiresLoginToPost = true,",
    "            requiresLoginToPost = false,",
    "comment login prompt capability",
)

settings_anchor = '''    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
'''
settings_replacement = '''    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val accountStatusPreference = Preference(screen.context).apply {
            key = "ehp_account_status"
            title = "账号状态"
            summary = accountStatusSummary()
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
'''
s = replace_once(s, settings_anchor, settings_replacement, "account status preference")

old_login = '''        actionPreference(screen, "ehp_login", "账号密码登录", "按 JHenTai 的官方论坛登录流程获取 ipb_member_id / ipb_pass_hash，并检测里站权限") {
            showResult(screen, runCatching { loginWithPassword() }, "登录成功")
        }
'''
new_login = '''        actionPreference(screen, "ehp_login", "账号密码登录", "按 JHenTai 的官方论坛登录流程获取 ipb_member_id / ipb_pass_hash，并检测里站权限") {
            val result = runCatching { loginWithPassword() }
            updateAccountStatusPreference(accountStatusPreference)
            showResult(screen, result, "登录成功")
        }
'''
s = replace_once(s, old_login, new_login, "login status refresh")

old_sync_validate = '''        actionPreference(screen, "ehp_sync_webview", "同步 WebView Cookie", "如果你已经在宿主 WebView 登录 E-Hentai，可将 Cookie 同步到扩展") {
            val count = syncWebViewCookies(save = true)
            showToast(screen, if (count > 0) "已同步 $count 个 Cookie" else "没有检测到可同步的登录 Cookie")
        }
        actionPreference(screen, "ehp_validate", "验证账号与里站权限", "读取论坛账号，并检测 ExHentai 是否可用") {
            showResult(screen, runCatching { validateSession() }, "验证完成")
        }
'''
new_sync_validate = '''        actionPreference(screen, "ehp_sync_webview", "同步 WebView Cookie", "如果你已经在宿主 WebView 登录 E-Hentai，可将 Cookie 同步到扩展") {
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
s = replace_once(s, old_sync_validate, new_sync_validate, "sync/validate status refresh")

old_logout = '''        actionPreference(screen, "ehp_logout", "退出登录", "清除扩展保存的账号 Cookie 和登录状态") {
            logout()
            showToast(screen, "已退出登录")
        }
'''
new_logout = '''        actionPreference(screen, "ehp_logout", "退出登录", "清除扩展保存的账号 Cookie 和登录状态") {
            logout()
            updateAccountStatusPreference(accountStatusPreference)
            showToast(screen, "已退出登录")
        }
'''
s = replace_once(s, old_logout, new_logout, "logout status refresh")

helper_anchor = '''    private fun actionPreference(screen: PreferenceScreen, keyName: String, titleText: String, summaryText: String, action: () -> Unit) {
'''
helpers = '''    private fun accountStatusSummary(): String {
        if (!isLoggedIn()) return "未登录 · 可使用账号密码、WebView Cookie 或手动 Cookie 登录"
        val name = preferences.getString(PREF_ACCOUNT_NAME, "").orEmpty().ifBlank { "E-Hentai 用户 #${memberId()}" }
        val exStatus = if (preferences.getBoolean(PREF_EX_ACCESS, false)) "ExHentai 里站可用" else "里站未验证通过"
        return "已登录：$name · $exStatus"
    }

    private fun updateAccountStatusPreference(preference: Preference) {
        Handler(Looper.getMainLooper()).post { preference.summary = accountStatusSummary() }
    }

'''
s = replace_once(s, helper_anchor, helpers + helper_anchor, "account status helper")
s = replace_once(s, '        private const val IMAGE_LOOPBACK_HOST = "127.0.0.1"\n', "", "loopback constant")
KT.write_text(s, "utf-8")

m = MODELS.read_text("utf-8")
old_default = '''        val defaultUrl = doc.selectFirst("#img")?.attr("abs:src")?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("未找到正文图片")
'''
new_default = '''        val defaultUrl = doc.selectFirst("#img")?.attr("abs:src")?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("未找到正文图片")
        if (defaultUrl == "https://ehgt.org/g/509.gif" || defaultUrl == "https://exhentai.org/img/509.gif") {
            throw IllegalStateException("E-Hentai 图片配额已用尽，请稍后再试或在 My Home 检查 Image Limits")
        }
'''
m = replace_once(m, old_default, new_default, "509 image quota guard")
m = replace_once(
    m,
    '        val authorLink = element.selectFirst(".c2 > .c3 > a[href*=showuser],.c3 a[href*=showuser]")\n',
    '        val authorLink = element.selectFirst(".c2 > .c3 > a,.c3 a")\n',
    "comment author selector",
)
m = replace_once(
    m,
    "        ParsedComment(id, userId, author, clean, parseCommentDate(timeText), timeText, score)\n",
    "        ParsedComment(id, userId, author, clean, parseCommentDate(timeText), commentDisplayTime(timeText), score)\n",
    "comment display time call",
)
parser_anchor = "private fun parseCommentDate(value: String?): Long {\n"
formatter = '''private fun commentDisplayTime(value: String?): String? {
    val millis = parseCommentDate(value)
    if (millis <= 0L) return value?.trim()?.takeIf(String::isNotBlank)
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC).format(EH_DATE_FORMATTER) + " UTC"
}

'''
m = replace_once(m, parser_anchor, formatter + parser_anchor, "comment display time helper")
MODELS.write_text(m, "utf-8")

g = GRADLE.read_text("utf-8")
g = replace_once(g, "versionCode = 1", "versionCode = 2", "versionCode")
GRADLE.write_text(g, "utf-8")

d = DOC.read_text("utf-8")
marker = "## v2 实机修复候选（2026-09-05）"
if marker not in d:
    d += '''

## v2 实机修复候选（2026-09-05）

根据 v1.6.1 实机反馈：

- 正文不再使用伪造 loopback imageUrl + OkHttp interceptor；改用当前 KeiSource 官方 `getImageUrl(Page)` 懒解析链路，避免宿主图片解码器直接拿到非图片响应而报 `Failed to initialize decoder`。
- 按 JHenTai 行为将 `s.exhentai.org` 图片地址归一到 `ehgt.org`，并识别 E-Hentai/ExHentai 的 509 图片配额占位图，返回明确错误。
- 评论页在未登录时不再触发宿主“请先登录/漫画源设置”底栏；只有已登录时才声明可发表评论，实际提交仍二次校验登录。
- 评论作者选择器不再强依赖 `showuser` URL，兼容当前评论 HTML；评论时间改为统一数字时间显示。
- 设置页新增“账号状态”，明确显示未登录 / 已登录账号 / ExHentai 里站验证状态，并在登录、同步 Cookie、验证和退出后即时刷新。
- 扩展图标替换为 E-Hentai 官方 favicon。

验证边界：CI 验证当前 Keiyoushi Spotless、Debug/Release 构建、Lint、固定签名、公开 E-Hentai viewer→image 链路和测试仓库生成；账号、里站与评论写操作仍需用户实机验证。
'''
DOC.write_text(d, "utf-8")

print("E-Hentai Plus v2 patch applied")
