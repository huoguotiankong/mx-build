#!/usr/bin/env python3
from pathlib import Path
from shutil import copytree, rmtree
import re

ROOT = Path("source")
UPSTREAM = Path("upstream")
SRC = ROOT / "src/zh/jmcomicplus/src/eu/kanade/tachiyomi/extension/zh/jmcomicplus/JmComicPlus.kt"
GRADLE = ROOT / "src/zh/jmcomicplus/build.gradle.kts"
DOC = ROOT / "docs/sources/jmcomicplus.md"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if text.count(old) != 1:
        raise SystemExit(f"Expected {label} exactly once, found {text.count(old)}")
    return text.replace(old, new, 1)


def sub_once(text: str, pattern: str, repl: str, label: str) -> str:
    out, n = re.subn(pattern, repl, text, count=1, flags=re.S)
    if n != 1:
        raise SystemExit(f"Expected {label} exactly once, found {n}")
    return out


text = SRC.read_text("utf-8")

# OkHttp transparently decompresses gzip only when it owns Accept-Encoding. v4 manually set
# Accept-Encoding on APP/API calls, so some JM nodes returned compressed bytes to body.string(),
# matching the user's real-device 'Unexpected JSON token' + binary-garbage failure.
text = replace_once(
    text,
    "import org.jsoup.Jsoup\n",
    "import org.jsoup.Jsoup\nimport org.jsoup.nodes.Document\n",
    "Jsoup import",
)
text = replace_once(
    text,
    "import java.util.concurrent.ConcurrentHashMap\n",
    "import java.util.concurrent.ConcurrentHashMap\nimport java.util.concurrent.TimeUnit\n",
    "TimeUnit import",
)
text = replace_once(
    text,
    "    override fun OkHttpClient.Builder.configureClient() = addNetworkInterceptor(ScrambledImageInterceptor())\n",
    """    override fun OkHttpClient.Builder.configureClient() =
        connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor(ScrambledImageInterceptor())
""",
    "client configuration",
)

# Use the current Keiyoushi JM web endpoints for browse pages.
text = replace_once(
    text,
    '        web = { webList("search/photos?search_query=&search-type=photos&main_tag=0&o=mv&page=$page", page) },\n',
    '        web = { webList("albums?o=mv&page=$page", page) },\n',
    "popular web route",
)
text = replace_once(
    text,
    '        web = { webList("search/photos?search_query=&search-type=photos&main_tag=0&o=mr&page=$page", page) },\n',
    '        web = { webList("albums?o=mr&page=$page", page) },\n',
    "latest web route",
)

# Current JM detail pages may inject the real body through base64DecodeUtf8/document.write.
text = replace_once(
    text,
    "        val doc = Jsoup.parse(html, base)\n        val title = doc.selectFirst(\"h1\")?.text().orEmpty().ifBlank { \"JM$id\" }\n",
    "        val doc = parseWebDocument(html, base)\n        val title = doc.selectFirst(\"h1\")?.text().orEmpty().ifBlank { \"JM$id\" }\n",
    "web detail parser",
)
text = replace_once(
    text,
    '        val authors = doc.select("a.web-author-tag").map { it.text().trim() }.filter(String::isNotBlank).distinct()\n',
    '        val authors = (doc.select("a.web-author-tag").ifEmpty { doc.select("div.panel-body div.tag-block:eq(3) .btn-primary") }).map { it.text().trim() }.filter(String::isNotBlank).distinct()\n',
    "web author selector",
)
text = replace_once(
    text,
    '        val tags = doc.select("#intro-block [data-type=tags] a,[data-type=tags] a,.tag a").map { it.text().trim() }.filter(String::isNotBlank).distinct()\n',
    '        val tags = doc.select("#intro-block [data-type=tags] a,[data-type=tags] a,.tag a,span[itemprop=genre] a").map { it.text().trim() }.filter(String::isNotBlank).filterNot { it in setOf("連載中", "连载中", "完結", "完结") }.distinct()\n',
    "web tag selector",
)
text = replace_once(
    text,
    '        val description = doc.selectFirst(".intro-collapse-content")?.text()\n',
    '        val description = doc.selectFirst(".intro-collapse-content,#intro-block .p-t-5.p-b-5")?.text()?.substringAfter("敘述：")?.trim()\n',
    "web description selector",
)
text = sub_once(
    text,
    r'''        val chapters = doc\.select\("a\[href\*=/photo/\]"\).*?        \}\.distinctBy \{ it\.url \}\.asReversed\(\)\n''',
    '''        val chapterLinks = doc.select("#episode-block a[href^=/photo/],a[href^=/photo/]")
        val chapters = chapterLinks.mapIndexedNotNull { index, a ->
            val pid = PHOTO_ID.find(a.attr("href"))?.groupValues?.getOrNull(1) ?: return@mapIndexedNotNull null
            SChapter.create().apply {
                url = "/photo/$pid?jm_album=$id"
                name = a.selectFirst("li h3")?.ownText()?.trim().orEmpty()
                    .ifBlank { a.text().trim().ifBlank { a.attr("title").ifBlank { "第 ${index + 1} 话" } } }
                chapter_number = (index + 1).toFloat()
            }
        }.distinctBy { it.url }.asReversed().ifEmpty {
            doc.selectFirst("#album_photo_cover a[href^=/photo/]")?.let { a ->
                PHOTO_ID.find(a.attr("href"))?.groupValues?.getOrNull(1)?.let { pid ->
                    listOf(SChapter.create().apply {
                        url = "/photo/$pid?jm_album=$id"
                        name = "单章节"
                        chapter_number = 1f
                    })
                }
            }.orEmpty()
        }
''',
    "web chapter parser",
)

# Follow JM's paginated chapter pages exactly like the maintained Keiyoushi source.
text = sub_once(
    text,
    r'''    private fun webPages\(pid: String\): PageBundle \{.*?\n    \}\n\n    override val commentCapabilities''',
    '''    private fun webPages(pid: String): PageBundle {
        val images = mutableListOf<String>()
        var next: String? = "photo/$pid/?shunt=${imageShunt()}"
        var guard = 0
        while (!next.isNullOrBlank() && guard++ < MAX_WEB_PAGE_REQUESTS) {
            val html = webRequest(next)
            val base = cachedWebBase()
            val doc = parseWebDocument(html, base)
            doc.select("div.center.scramble-page.spnotice_chk[id*=0] img,.scramble-page img,img[data-original*=/media/photos/]").forEach { img ->
                val raw = img.attr("data-original").ifBlank { img.attr("data-src").ifBlank { img.attr("data-cfsrc").ifBlank { img.attr("src") } } }
                val value = absoluteUrl(base, raw).substringBefore('?')
                if (value.contains("/media/photos/") && !value.contains("blank.jpg")) images += value
            }
            next = doc.selectFirst("a.prevnext")?.attr("abs:href")?.takeIf(String::isNotBlank)
        }
        val scramble = runCatching { scrambleId(pid) }.getOrDefault(DEFAULT_SCRAMBLE_ID)
        return PageBundle(images.distinct(), scramble)
    }

    override val commentCapabilities''',
    "web page parser",
)

# Harden APP requests. Normal APP calls use 185Hcomic3PAPP7R; the content secret is only
# for chapter_view_template. Do not manually set Accept-Encoding on OkHttp.
text = sub_once(
    text,
    r'''    private fun apiHeaders\(ts: String, secret: String, version: String\?, noAvs: Boolean\): Headers = Headers\.Builder\(\)\.apply \{.*?\n    \}\.build\(\)''',
    '''    private fun apiHeaders(ts: String, secret: String, version: String?, noAvs: Boolean): Headers = Headers.Builder().apply {
        set("User-Agent", UA_APP)
        set("Accept", "application/json, text/plain, */*")
        set("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
        set("X-Requested-With", "com.JMComic3.app")
        set("Referer", "https://${preferences.getString(PREF_API_HOST, API_FALLBACK.first()).orEmpty().ifBlank { API_FALLBACK.first() }}/")
        set("token", md5Hex(ts + secret))
        set("tokenparam", "$ts,${version ?: preferences.getString(PREF_APP_VERSION, APP_VERSION).orEmpty().ifBlank { APP_VERSION }}")
        val avs = if (noAvs) "" else storedAvs()
        set("Cookie", API_BOOTSTRAP_COOKIE + avs.takeIf(String::isNotBlank)?.let { "; AVS=$it" }.orEmpty())
    }.build()''',
    "APP headers",
)
text = replace_once(
    text,
    '''                        val value = decodeEnvelope(response.body.string(), ts)
                        preferences.edit().putString(PREF_API_HOST, normalizeHost(host)).putString(PREF_LAST_ROUTE, "app").apply()
                        value
''',
    '''                        val raw = response.body.string().trimStart('\uFEFF')
                        if (raw.firstOrNull { !it.isWhitespace() } != '{') {
                            throw IOException("API 节点返回非 JSON，切换节点重试")
                        }
                        val value = decodeEnvelope(raw, ts)
                        preferences.edit().putString(PREF_API_HOST, normalizeHost(host)).putString(PREF_LAST_ROUTE, "app").apply()
                        value
''',
    "APP response validation",
)

# Replace the slow v4 web discovery. Normal browsing starts from known-good mirrors immediately;
# expensive discovery happens only after those candidates fail or when the user forces refresh.
text = sub_once(
    text,
    r'''    private fun webRequest\(path: String, method: String = "GET", form: Map<String, String> = emptyMap\(\)\): String \{.*?\n    \}\n\n    private fun refreshWebDomains''',
    '''    private fun webRequest(path: String, method: String = "GET", form: Map<String, String> = emptyMap()): String {
        fun attempt(candidates: List<String>): String? {
            for (base in candidates.filterNot(::isLegacyWebBase).distinct()) {
                val result = runCatching {
                    val url = if (path.startsWith("http://") || path.startsWith("https://")) path else "${base.trimEnd('/')}/${path.trimStart('/')}"
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", UA_WEB)
                        .header("Referer", "${base.trimEnd('/')}/")
                        .header("Accept-Language", "zh-CN,zh;q=0.9")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                        .header("Cache-Control", "no-cache")
                        .header("Pragma", "no-cache")
                        .apply {
                            if (method.equals("GET", true)) get() else post(FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build())
                        }
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val body = response.body.string()
                        if (body.length < 100) throw IOException("网页响应过短")
                        val finalBase = "${response.request.url.scheme}://${response.request.url.host}"
                        preferences.edit().putString(PREF_WEB_BASE, finalBase).putString(PREF_LAST_ROUTE, "web").apply()
                        body
                    }
                }
                if (result.isSuccess) return result.getOrThrow()
            }
            return null
        }

        val quick = (listOf(cachedWebBase()) + refreshWebDomains(false) + WEB_FALLBACK).distinct()
        attempt(quick)?.let { return it }
        val refreshed = refreshWebDomains(true)
        attempt(refreshed + WEB_FALLBACK)?.let { return it }
        throw IOException("网页线路失败：当前镜像均不可用，请稍后重试或在设置中手动指定域名")
    }

    private fun refreshWebDomains''',
    "web request",
)
text = sub_once(
    text,
    r'''    private fun refreshWebDomains\(force: Boolean\): List<String> \{.*?\n    \}\n\n    private fun probeWeb''',
    '''    private fun refreshWebDomains(force: Boolean): List<String> {
        val manual = preferences.getString(PREF_MANUAL_WEB, "").orEmpty().trim().trimEnd('/')
        if (manual.startsWith("http://") || manual.startsWith("https://")) return listOf(manual)
        val cached = readStringList(PREF_WEB_DOMAINS).filterNot(::isLegacyWebBase)
        if (!force && cached.isNotEmpty()) return cached
        if (!force) return WEB_FALLBACK

        val out = linkedSetOf<String>()
        out += WEB_FALLBACK
        runCatching {
            val req = Request.Builder().url(WEB_DOMAIN_FEED).header("User-Agent", UA_WEB).build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    response.body.string().lineSequence()
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .map { if (it.startsWith("http")) it.trimEnd('/') else "https://${normalizeHost(it)}" }
                        .filterNot(::isLegacyWebBase)
                        .forEach(out::add)
                }
            }
        }
        runCatching {
            val req = Request.Builder().url(WEB_REDIRECT).header("User-Agent", UA_WEB).build()
            client.newCall(req).execute().use { response ->
                "${response.request.url.scheme}://${response.request.url.host}".takeUnless(::isLegacyWebBase)?.let(out::add)
            }
        }
        for (pub in WEB_FALLBACK.take(4)) {
            runCatching {
                val req = Request.Builder().url(pub).header("User-Agent", UA_WEB).build()
                client.newCall(req).execute().use { response ->
                    val html = response.body.string()
                    DOMAIN_REGEX.findAll(html).map { it.value.trimEnd('/', '\\', '"', '\'') }.filterNot(::isLegacyWebBase).forEach(out::add)
                    HREF_REGEX.findAll(html).mapNotNull { it.groupValues.getOrNull(1) }.filterNot(::isLegacyWebBase).forEach(out::add)
                    BARE_DOMAIN_REGEX.findAll(html)
                        .mapNotNull { it.groupValues.getOrNull(1) }
                        .map { "https://${it.lowercase(Locale.ROOT)}" }
                        .filterNot(::isLegacyWebBase)
                        .forEach(out::add)
                }
            }
        }
        val result = out.toList().take(MAX_WEB_DOMAINS)
        writeStringList(PREF_WEB_DOMAINS, result)
        preferences.edit().putLong(PREF_WEB_DOMAINS_AT, System.currentTimeMillis()).apply()
        return result
    }

    private fun probeWeb''',
    "web domain refresh",
)
# probeWeb is no longer on the request hot path; retain it for compatibility but avoid using it in discovery.
text = sub_once(
    text,
    r'''    private fun cachedWebBase\(\): String \{.*?\n    \}\n\n    private fun imageHost''',
    '''    private fun cachedWebBase(): String {
        val manual = preferences.getString(PREF_MANUAL_WEB, "").orEmpty().trim().trimEnd('/')
        if (manual.startsWith("http://") || manual.startsWith("https://")) return manual
        val cached = preferences.getString(PREF_WEB_BASE, WEB_PUBLIC).orEmpty().ifBlank { WEB_PUBLIC }.trimEnd('/')
        return if (isLegacyWebBase(cached)) WEB_PUBLIC else cached
    }

    private fun isLegacyWebBase(value: String): Boolean {
        val host = hostOf(value).lowercase(Locale.ROOT)
        return host == "jmcomicgo.org" || host == "jmcomicgo.me"
    }

    private fun parseWebDocument(html: String, base: String): Document {
        val doc = Jsoup.parse(html, base)
        doc.select("#wrapper > script").forEach { script ->
            val code = script.html()
            if (!code.contains("base64DecodeUtf8") || !code.contains("document.write")) return@forEach
            DETAIL_BASE64_REGEX.findAll(code).forEach { match ->
                runCatching { String(Base64.decode(match.groupValues[1], Base64.DEFAULT), Charsets.UTF_8) }
                    .getOrNull()
                    ?.takeIf(String::isNotBlank)
                    ?.let { doc.body().append(it) }
            }
        }
        return doc
    }

    private fun imageHost''',
    "cached web base and decoder",
)

# Align constants with the maintained Keiyoushi source and current JM crawler.
text = replace_once(text, '        private const val WEB_PUBLIC = "https://jmcomicgo.org"\n', '        private const val WEB_PUBLIC = "https://18comic.vip"\n', "web public")
text = replace_once(text, '        private const val WEB_PUBLIC_OLD = "https://jmcomicgo.me"\n', '        private const val WEB_PUBLIC_OLD = "https://18comic.ink"\n', "web old")
text = replace_once(
    text,
    '        private val TOKEN_SECRETS = listOf("18comicAPP", "185Hcomic3PAPP7R")\n',
    '        private val TOKEN_SECRETS = listOf("185Hcomic3PAPP7R")\n',
    "APP token secret",
)
text = sub_once(
    text,
    r'''        private val WEB_FALLBACK = listOf\(WEB_PUBLIC, "https://18comic\.vip", "https://18comic\.ink"\)\n''',
    '''        private val WEB_FALLBACK = listOf(
            WEB_PUBLIC,
            "https://18comic.ink",
            "https://jmcomic-zzz.one",
            "https://jmcomic-zzz.org",
            "https://18comic-ive.club",
            "https://18comic-aspa.org",
            "https://18comic-wantgo.cc",
        )
''',
    "web fallbacks",
)
text = replace_once(
    text,
    '        private val HREF_REGEX = Regex("href=[\\\\\\\"\'](https?://[^\\\\\\\"\'<> ]+)[\\\\\\\"\']", RegexOption.IGNORE_CASE)\n',
    '        private val HREF_REGEX = Regex("href=[\\\\\\\"\'](https?://[^\\\\\\\"\'<> ]+)[\\\\\\\"\']", RegexOption.IGNORE_CASE)\n        private val BARE_DOMAIN_REGEX = Regex("(?<![A-Za-z0-9.-])((?:18comic|jmcomic|jm-comic)(?:-[A-Za-z0-9-]+)?(?:\\\\.[A-Za-z0-9-]+)+)(?![A-Za-z0-9.-])", RegexOption.IGNORE_CASE)\n        private val DETAIL_BASE64_REGEX = Regex("(?:const|let|var)\\\\s+html\\\\s*=\\\\s*base64DecodeUtf8\\\\([\\\\\\\"\']([^\\\\\\\"\']+)[\\\\\\\"\']\\\\)")\n',
    "web regex constants",
)
text = replace_once(
    text,
    '        private const val DEFAULT_SCRAMBLE_ID = 220980\n',
    '        private const val DEFAULT_SCRAMBLE_ID = 220980\n        private const val WEB_DOMAIN_FEED = "https://stevenyomi.github.io/source-domains/jmcomic.txt"\n        private const val API_BOOTSTRAP_COOKIE = "ipcountry=TW; theme=light"\n        private const val MAX_WEB_DOMAINS = 16\n        private const val MAX_WEB_PAGE_REQUESTS = 40\n',
    "new constants",
)

SRC.write_text(text, "utf-8")

# Formal update -> versionCode must increase. Also make the source's identity/deeplinks follow
# the current working Keiyoushi mirrors instead of the deprecated jmcomicgo host.
g = GRADLE.read_text("utf-8")
g = replace_once(g, "versionCode = 4", "versionCode = 5", "versionCode")
g = replace_once(g, 'baseUrl = "https://jmcomicgo.org"', 'baseUrl = "https://18comic.vip"', "source baseUrl")
g = replace_once(
    g,
    '''    deeplink {
        path("/album/..*")
        path("/photo/..*")
    }
''',
    '''    deeplink {
        host("18comic.vip")
        host("18comic.ink")
        host("jmcomic-zzz.one")
        host("jmcomic-zzz.org")
        path("/album/..*")
        path("/photo/..*")
    }
''',
    "deeplink hosts",
)
GRADLE.write_text(g, "utf-8")

# Reuse the maintained Keiyoushi 禁漫天堂 icon set exactly. The previous Plus icon was a
# placeholder and was confirmed wrong on-device.
icon_src = UPSTREAM / "src/zh/jinmantiantang/res"
icon_dst = ROOT / "src/zh/jmcomicplus/res"
if not icon_src.is_dir():
    raise SystemExit("Current Keiyoushi Jinmantiantang icons not found")
if icon_dst.exists():
    rmtree(icon_dst)
copytree(icon_src, icon_dst)

# Record the real-device failure as the source of truth and the exact v5 repair scope.
d = DOC.read_text("utf-8")nd = d.replace(
    "- 当前测试版本：`1.6.4`，Android `versionCode=106004`。",
    "- 当前源码候选：`1.6.5`，Android `versionCode=106005`；v4 测试版保留供回退。",
)
anchor = "## 验证状态\n"
real = """## v4 实机反馈（2026-09-04）

- 扩展可安装且宿主可识别，但图标为错误占位图。
- APP/API：多个 `cdngwc` 节点返回内容被当成 JSON 直接解析，实机出现二进制乱码 + `Unexpected JSON token`；根因修复方向为移除 OkHttp 手工 `Accept-Encoding`、补齐 APP 请求头/基础 Cookie，并对非 JSON 节点自动切换。
- 网页：进入列表持续加载；v5 改为当前 Keiyoushi 已维护的 `18comic.vip / 18comic.ink / jmcomic-zzz.*` 镜像优先，正常请求不再先同步探测一长串候选，失败后再自动刷新域名。
- 网页解析同步参考当前 Keiyoushi 禁漫天堂实现：`/albums` 热门/最新、详情页 Base64 注入内容展开、章节选择器、分页正文选择器。
- v5 图标直接同步当前 Keiyoushi 禁漫天堂官方扩展图标资源。

"""
if real.strip() not in d:
    d = d.replace(anchor, real + anchor, 1)
d = d.replace(
    "- 实机功能：APP/API、网页线路、动态域名、登录、书评、章评、评论回复、增强详情页、正文反混淆仍待用户实机验证；特别是章评目标 ID、登录兼容性和长篇正文反混淆效果，以用户实机反馈为准。",
    "- v4 实机已确认：APP/API 与网页线路不可用、图标错误；以上问题进入 v5 修复。v5 构建完成后，登录、书评、章评、回复、增强详情页及长篇正文反混淆仍需继续实机回归。",
)
DOC.write_text(d, "utf-8")
