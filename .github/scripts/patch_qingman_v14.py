from pathlib import Path

q = Path('dev/src/zh/qingman/src/eu/kanade/tachiyomi/extension/zh/qingman/Qingman.kt')
s = q.read_text('utf-8')
start_marker = '    override suspend fun getPageList(chapter: SChapter): List<Page> {\n'
end_marker = '    override fun imageRequest(page: Page): Request {\n'
start = s.find(start_marker)
end = s.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit('reader method markers changed')

method = '''    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val parts = chapter.url.split(':', limit = 2)
        if (parts.size != 2 || parts.any(String::isBlank)) {
            throw IOException("青漫章节参数无效")
        }

        val (comicId, chapterId) = parts
        val diagnostics = mutableListOf<String>()

        for (readerType in listOf(2, 1)) {
            val root = fetchReaderRoot(comicId, chapterId, type = readerType)
            root.loginRequiredMessage()?.let { throw IOException("青漫正文需要官方账号登录：$it") }

            val rawUrls = extractPageUrls(root)
            val resolvedUrls = rawUrls
                .mapNotNull(::resolveReaderImageUrl)
                .distinct()
            val referer = extractReaderReferer(root).orEmpty()

            if (resolvedUrls.isNotEmpty()) {
                return resolvedUrls.mapIndexed { index, imageUrl ->
                    Page(index, url = referer, imageUrl = imageUrl)
                }
            }

            diagnostics += readerDiagnostic(readerType, rawUrls, referer)
        }

        throw IOException(
            "青漫正文未解析到可用图片；" + diagnostics.joinToString("；"),
        )
    }

'''
s = s[:start] + method + s[end:]

helper_marker = '    private fun extractPageUrls(root: JsonElement): List<String> = collectStrings(root, PAGE_URL_KEYS)\n'
helper_pos = s.find(helper_marker)
if helper_pos < 0:
    raise SystemExit('extractPageUrls marker changed')
helper = '''    private fun readerDiagnostic(type: Int, rawUrls: List<String>, referer: String): String {
        val shapes = rawUrls
            .groupingBy(::readerUrlShape)
            .eachCount()
            .entries
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}=${it.value}" }
            .ifBlank { "none=0" }
        return "type=$type,pageUrls=${rawUrls.size}($shapes),referer=${if (referer.isBlank()) "无" else "有"}"
    }

    private fun readerUrlShape(value: String): String {
        val trimmed = value.trim()
        return when {
            isHttpUrl(trimmed) -> "absolute"
            trimmed.startsWith("//") -> "scheme-relative"
            trimmed.startsWith('/') -> "root-relative"
            else -> "relative"
        }
    }

'''
if 'private fun readerDiagnostic(' in s:
    raise SystemExit('v14 diagnostic helper already present unexpectedly')
s = s[:helper_pos] + helper + s[helper_pos:]
q.write_text(s, 'utf-8')

g = Path('dev/src/zh/qingman/build.gradle.kts')
gs = g.read_text('utf-8')
if 'versionCode = 13' not in gs:
    raise SystemExit('expected Qingman versionCode 13')
g.write_text(gs.replace('versionCode = 13', 'versionCode = 14', 1), 'utf-8')

d = Path('dev/docs/sources/qingman.md')
ds = d.read_text('utf-8')
heading = '## 2026-09-07 v1.6.14 在线正文与缓存接口解耦'
if heading not in ds:
    note = '''

## 2026-09-07 v1.6.14 在线正文与缓存接口解耦

用户对 `v1.6.13` 实机确认：进入正文时返回 `code=0，用户等级超过15级才能缓存`，随后阅读器退出。该反馈证明 `cache/detail` 属于离线缓存/下载业务，不能作为普通在线正文的必经接口。

v1.6.14 调整：

- 普通正文彻底停止调用 `api/comic/chapter/ie84hfh8/cache/detail`；
- 仅调用官方在线阅读 `detail`，按官方顺序尝试 `type=2`，无可直接请求图片时再尝试 `type=1`；
- 回退条件改为“是否解析出可直接请求的图片 URL”，而不是只判断 `pageUrls` 是否为空；
- 保留响应自带 `referer` 作为图片请求 Referer；
- 不再用 Android APK 下载地址猜测正文图片 CDN；
- 如果 `pageUrls` 仍为相对路径而官方拼接基址尚未确认，错误信息只报告 URL 形态计数、线路 type 和 referer 是否存在，不泄露完整 URL、Token 或账号数据；
- 保留 v1.6.13 已修正的章节顺序逻辑。

状态：源码和构建通过后仍标记为“待实机验证”；只有用户实机确认正文可读后才算正文修复完成。
'''
    d.write_text(ds.rstrip() + note + '\n', 'utf-8')
