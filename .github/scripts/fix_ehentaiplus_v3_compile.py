from pathlib import Path

ROOT = Path("source")
KT = ROOT / "src/zh/ehentaiplus/src/eu/kanade/tachiyomi/extension/zh/ehentaiplus/EHentaiPlus.kt"
DOC = ROOT / "docs/sources/ehentaiplus.md"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, found {count}")
    return text.replace(old, new, 1)


s = KT.read_text("utf-8")

s = replace_once(
    s,
    '''    override suspend fun getImageUrl(page: Page): String = client.get(page.url).use { response ->
        val info = EHParser.parseImageInfo(response, originalImages())
        info.reloadViewerUrl?.let { page.url = it }
        info.imageUrl
    }
''',
    '''    override suspend fun getImageUrl(page: Page): String = client.get(page.url).use { response ->
        EHParser.parseImageInfo(response, originalImages()).imageUrl
    }
''',
    "immutable page url",
)

start_marker = '''    override suspend fun getImage(page: Page): Response {
'''
end_marker = '''    private fun buildImageRequest(imageUrl: String, referer: String): Request = Request.Builder()
'''
start = s.find(start_marker)
end = s.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit("getImage override block not found")
s = s[:start] + s[end:]

alt_start = '''    private fun alternateImageUrl(raw: String): String? {
'''
alt_end = '''    private fun readerChapterUrl(raw: String): String = Uri.parse(raw)
'''
start = s.find(alt_start)
end = s.find(alt_end, start)
if start < 0 or end < 0:
    raise SystemExit("alternate image block not found")
s = s[:start] + s[end:]

s = replace_once(
    s,
    '''        .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        .apply {
''',
    '''        .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        .header(IMAGE_REQUEST_HEADER, "1")
        .apply {
''',
    "image request marker",
)

client_anchor = '''    override suspend fun getPopularManga(page: Int): MangasPage {
'''
image_guard = '''    private fun validateImageResponse(response: Response): Response {
        val contentType = response.header("Content-Type").orEmpty().substringBefore(';').trim()
        if (contentType.startsWith("image/", ignoreCase = true)) return response
        val message = "正文图片服务器没有返回有效图片：HTTP ${response.code} · ${contentType.ifBlank { "未知 Content-Type" }}。若刚从旧测试版升级，请刷新章节并清除一次阅读缓存。"
        response.close()
        throw IOException(message)
    }

'''
s = replace_once(s, client_anchor, image_guard + client_anchor, "image validation helper")

old_chain = '''            val response = chain.proceed(builder.build())
            captureSetCookies(response)
            response
        }
    }
'''
new_chain = '''            val isImageRequest = request.header(IMAGE_REQUEST_HEADER) == "1"
            if (isImageRequest) builder.removeHeader(IMAGE_REQUEST_HEADER)
            val response = chain.proceed(builder.build())
            captureSetCookies(response)
            if (isImageRequest) validateImageResponse(response) else response
        }
    }
'''
s = replace_once(s, old_chain, new_chain, "image response guard interceptor")

s = replace_once(
    s,
    '''            summary = accountStatusSummary()
            isEnabled = false
''',
    '''            summary = accountStatusSummary()
            setOnPreferenceClickListener { true }
''',
    "read-only account status preference",
)

s = replace_once(
    s,
    '        private const val READER_REVISION = "3"\n',
    '        private const val READER_REVISION = "3"\n        private const val IMAGE_REQUEST_HEADER = "X-MX-EH-Image"\n',
    "image marker constant",
)

KT.write_text(s, "utf-8")

d = DOC.read_text("utf-8")
d = d.replace(
    "- `getImageUrl(Page)` 保留站点返回的原始 `#img` URL，并消费 `nl(...)` 重试地址更新 `page.url`，与 MX 内置 E-Hentai 的重试模型保持一致。",
    "- `getImageUrl(Page)` 保留站点返回的原始 `#img` URL；当前 Keiyoushi 扩展 API 的 `Page.url` 为只读，因此不在扩展侧强行改写 viewer URL。",
)
d = d.replace(
    "- 图片下载新增 Content-Type 校验；非 `image/*` 响应不再被当作图片写入阅读缓存，从而避免 HTML/错误页最终表现成模糊的 decoder 异常。",
    "- 图片请求使用内部请求标记，由 OkHttp interceptor 在响应返回宿主前检查 Content-Type；非 `image/*` 响应直接报明确错误，不再把 HTML/错误页交给阅读器当图片解码。",
)
d = d.replace(
    "- `s.exhentai.org` / `ehgt.org` 只作为互相回退，不再无条件提前改写。\n",
    "- 不再无条件把 `s.exhentai.org` 改写为 `ehgt.org`，优先完全使用 viewer 返回的原始图片地址。\n",
)
DOC.write_text(d, "utf-8")

print("E-Hentai Plus v3 current-API compile patch applied")
