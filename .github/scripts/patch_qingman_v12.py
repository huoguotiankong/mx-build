from pathlib import Path

q = Path('dev/src/zh/qingman/src/eu/kanade/tachiyomi/extension/zh/qingman/Qingman.kt')
s = q.read_text('utf-8')
old = '''        var lastRoot = ultraRoot
        var urls = extractPageUrls(ultraRoot)
        if (urls.isEmpty()) {
            val standardRoot = fetchReaderRoot(comicId, chapterId, type = 1)
            standardRoot.loginRequiredMessage()?.let { throw IOException("青漫正文需要官方账号登录：$it") }
            lastRoot = standardRoot
            urls = extractPageUrls(standardRoot)
        }

        val mediaBase = mediaBaseUrl()
        val resolvedUrls = urls
            .mapNotNull { resolveMediaUrl(it, mediaBase) }
            .distinct()

        if (resolvedUrls.isEmpty()) {
            throw IOException(lastRoot.readerFailureMessage())
        }

        val referer = extractReaderReferer(lastRoot).orEmpty()
        return resolvedUrls.mapIndexed { index, imageUrl ->
            Page(index, url = referer, imageUrl = imageUrl)
        }
'''
new = '''        var readerType = 2
        var lastRoot = ultraRoot
        var urls = extractPageUrls(ultraRoot)
        if (urls.isEmpty()) {
            readerType = 1
            val standardRoot = fetchReaderRoot(comicId, chapterId, type = readerType)
            standardRoot.loginRequiredMessage()?.let { throw IOException("青漫正文需要官方账号登录：$it") }
            lastRoot = standardRoot
            urls = extractPageUrls(standardRoot)
        }

        // Official 4.4.5 refreshes signed image download URLs through cache/detail.
        // Prefer the refreshed list but retain the chapter-detail list as fallback.
        val cacheRoot = runCatching {
            fetchReaderCacheRoot(comicId, chapterId, type = readerType)
        }.getOrNull()
        val cacheUrls = cacheRoot?.let(::extractPageUrls).orEmpty()
        if (cacheUrls.isNotEmpty()) {
            urls = cacheUrls
        }

        val mediaBase = mediaBaseUrl()
        val resolvedUrls = urls
            .mapNotNull { resolveMediaUrl(it, mediaBase) }
            .distinct()

        if (resolvedUrls.isEmpty()) {
            throw IOException((cacheRoot ?: lastRoot).readerFailureMessage())
        }

        val referer = cacheRoot
            ?.takeIf { cacheUrls.isNotEmpty() }
            ?.let(::extractReaderReferer)
            ?: extractReaderReferer(lastRoot)
            ?: ""
        return resolvedUrls.mapIndexed { index, imageUrl ->
            Page(index, url = referer, imageUrl = imageUrl)
        }
'''
if old not in s:
    raise SystemExit('reader page block changed; aborting exact v12 patch')
s = s.replace(old, new, 1)
anchor = '''    private suspend fun fetchReaderRoot(comicId: String, chapterId: String, type: Int): JsonElement = transport.postAuthenticatedJson("api/comic/chapter/ie84hfh8/detail") {
        addQueryParameter("comicId", comicId)
        addQueryParameter("chapterId", chapterId)
        addQueryParameter("type", type.toString())
    }

    private fun extractPageUrls(root: JsonElement): List<String>'''
replacement = '''    private suspend fun fetchReaderRoot(comicId: String, chapterId: String, type: Int): JsonElement = transport.postAuthenticatedJson("api/comic/chapter/ie84hfh8/detail") {
        addQueryParameter("comicId", comicId)
        addQueryParameter("chapterId", chapterId)
        addQueryParameter("type", type.toString())
    }

    private suspend fun fetchReaderCacheRoot(comicId: String, chapterId: String, type: Int): JsonElement = transport.postAuthenticatedJson("api/comic/chapter/ie84hfh8/cache/detail") {
        addQueryParameter("comicId", comicId)
        addQueryParameter("chapterId", chapterId)
        addQueryParameter("type", type.toString())
    }

    private fun extractPageUrls(root: JsonElement): List<String>'''
if anchor not in s:
    raise SystemExit('reader helper anchor changed; aborting exact v12 patch')
q.write_text(s.replace(anchor, replacement, 1), 'utf-8')

t = Path('dev/src/zh/qingman/src/eu/kanade/tachiyomi/extension/zh/qingman/QingmanTransport.kt')
ts = t.read_text('utf-8')
old_body = '''        val requestBody = if (path == READER_PATH) {
            JsonObject(
                mapOf(
                    "deviceId" to JsonPrimitive(deviceId()),
                ),
            ).toString().toRequestBody(JSON_MEDIA_TYPE)
        } else {
            EMPTY_JSON_BODY
        }'''
new_body = '''        val requestBody = if (path == READER_PATH || path == READER_CACHE_PATH) {
            JsonObject(
                mapOf(
                    "deviceId" to JsonPrimitive(deviceId()),
                ),
            ).toString().toRequestBody(JSON_MEDIA_TYPE)
        } else {
            EMPTY_JSON_BODY
        }'''
if old_body not in ts:
    raise SystemExit('transport reader body block changed; aborting exact v12 patch')
ts = ts.replace(old_body, new_body, 1)
old_const = '        private const val READER_PATH = "api/comic/chapter/ie84hfh8/detail"\n'
if old_const not in ts or 'private const val READER_CACHE_PATH' in ts:
    raise SystemExit('transport reader constants changed; aborting exact v12 patch')
ts = ts.replace(old_const, old_const + '        private const val READER_CACHE_PATH = "api/comic/chapter/ie84hfh8/cache/detail"\n', 1)
t.write_text(ts, 'utf-8')

g = Path('dev/src/zh/qingman/build.gradle.kts')
gs = g.read_text('utf-8')
if 'versionCode = 11' not in gs:
    raise SystemExit('expected Qingman versionCode 11')
g.write_text(gs.replace('versionCode = 11', 'versionCode = 12', 1), 'utf-8')

d = Path('dev/docs/sources/qingman.md')
ds = d.read_text('utf-8')
note = '''

## 2026-09-07 v1.6.12 正文签名图片 URL 刷新

用户实机确认 `1.6.11` 已能取得官方账号与广告后的正文权限，但正文图片 GET 仍返回 HTTP 400。官方 Android 4.4.5 AOT 已确认存在 `cache/detail` 下载 Token 刷新链路，以及“已更新下载地址 / 重拉 cache/detail 失败”日志。

`1.6.12` 在章节详情成功后，以同一 `comicId / chapterId / type / deviceId` 调用认证 `api/comic/chapter/ie84hfh8/cache/detail`，若返回新的 `picList/pageUrls/imageUrls` 则优先使用；为空或失败时回退章节详情原 URL。该改动只刷新已授权正文的图片下载地址，不伪造会员、广告或解锁状态。
'''
if '## 2026-09-07 v1.6.12 正文签名图片 URL 刷新' not in ds:
    d.write_text(ds.rstrip() + note.rstrip() + '\n', 'utf-8')
