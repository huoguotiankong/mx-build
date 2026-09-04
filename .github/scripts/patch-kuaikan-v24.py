from pathlib import Path

src = Path("src/zh/kuaikanmanhua/src/eu/kanade/tachiyomi/extension/zh/kuaikanmanhua/Kuaikanmanhua.kt")
gradle = Path("src/zh/kuaikanmanhua/build.gradle.kts")
docs = Path("docs/sources/kuaikanmanhua.md")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}; refusing blind patch")
    return text.replace(old, new, 1)


text = src.read_text(encoding="utf-8")

old_web = '''                    val content = item.selectFirst("div.content")?.text()?.trim().orEmpty()
                    val author = item.selectFirst("div.user-name")?.text()?.trim().orEmpty()
                    if (content.isBlank() || author.isBlank()) return@mapIndexedNotNull null
'''
new_web = '''                    val contentElement = item.selectFirst("div.content")
                    val content = buildList {
                        contentElement?.text()?.trim()?.takeIf(String::isNotBlank)?.let(::add)
                        contentElement?.select("img")?.forEach { image ->
                            sequenceOf(image.attr("src"), image.attr("data-src"), image.attr("data-original"))
                                .mapNotNull(::normalizeUrl)
                                .firstOrNull(::isCommentImageUrl)
                                ?.let(::add)
                        }
                        contentElement?.select("a[href]")
                            ?.mapNotNull { normalizeUrl(it.attr("href")) }
                            ?.filter(::isCommentImageUrl)
                            ?.forEach(::add)
                    }.distinct().joinToString("\\n")
                    val author = item.selectFirst("div.user-name")?.text()?.trim().orEmpty()
                    if (content.isBlank() || author.isBlank()) return@mapIndexedNotNull null
'''
text = replace_once(text, old_web, new_web, "web comment content")

old_helper = '''    private fun contentText(items: JsonArray?): String = (items ?: JsonArray(emptyList()))
        .mapNotNull { item ->
            when (item) {
                is JsonObject -> item.string("content") ?: item.string("text")
                is JsonPrimitive -> item.contentOrNull
                else -> null
            }
        }
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString("\\n")
'''
new_helper = '''    private fun contentText(items: JsonArray?): String {
        val parts = mutableListOf<String>()
        (items ?: JsonArray(emptyList())).forEach { item ->
            when (item) {
                is JsonObject -> {
                    item.string("content")
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?.let(parts::add)
                    item.string("text")
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?.let(parts::add)
                }
                is JsonPrimitive -> item.contentOrNull
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let(parts::add)
                else -> Unit
            }
            parts += commentImageUrls(item)
        }
        return parts.filter(String::isNotBlank).distinct().joinToString("\\n")
    }

    private fun commentObjectContent(obj: JsonObject): String {
        val parts = mutableListOf<String>()
        val contentObj = obj.obj("content")
        sequenceOf(
            obj.string("content"),
            obj.string("text"),
            contentObj?.string("text"),
            contentObj?.string("content"),
        )
            .filterNotNull()
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach(parts::add)

        obj.array("contents")?.let { contentText(it).takeIf(String::isNotBlank)?.let(parts::add) }
        obj.array("content_info")?.let { contentText(it).takeIf(String::isNotBlank)?.let(parts::add) }
        contentObj?.let { parts += commentImageUrls(it) }
        listOf("image", "images", "image_url", "imageUrl", "pic", "pics", "picture", "pictures")
            .mapNotNull(obj::get)
            .forEach { parts += commentImageUrls(it) }

        return parts.filter(String::isNotBlank).distinct().joinToString("\\n")
    }

    private fun commentImageUrls(element: JsonElement): List<String> {
        val urls = mutableListOf<String>()

        fun collect(node: JsonElement, depth: Int) {
            if (depth > 7) return
            when (node) {
                is JsonPrimitive -> node.contentOrNull
                    ?.trim()
                    ?.let(::normalizeUrl)
                    ?.takeIf(::isCommentImageUrl)
                    ?.let(urls::add)
                is JsonObject -> node.values.forEach { collect(it, depth + 1) }
                is JsonArray -> node.forEach { collect(it, depth + 1) }
            }
        }

        collect(element, 0)
        return urls.distinct()
    }

    private fun isCommentImageUrl(url: String): Boolean {
        val normalized = url.lowercase()
        if (!COMMENT_IMAGE_HOST_REGEX.containsMatchIn(normalized)) return false
        return "/comment/image/" in normalized ||
            "/social/" in normalized ||
            "-watermark" in normalized ||
            COMMENT_IMAGE_FILE_REGEX.containsMatchIn(normalized) ||
            normalized.contains(".v3mh.com/")
    }
'''
text = replace_once(text, old_helper, new_helper, "contentText")

old_fallback = '''        val contentObj = obj.obj("content")
        val text = obj.string("content") ?: obj.string("text") ?: contentObj?.string("text") ?: contentObj?.string("content") ?: return null
        val user = obj.obj("user") ?: obj.obj("author") ?: return null
'''
new_fallback = '''        val text = commentObjectContent(obj)
        if (text.isBlank()) return null
        val user = obj.obj("user") ?: obj.obj("author") ?: return null
'''
text = replace_once(text, old_fallback, new_fallback, "commentFromObject")

marker = '''        const val H5_BASE_URL = "https://h5.kuaikanmanhua.com"
        const val DESKTOP_USER_AGENT = '''
replacement = r'''        const val H5_BASE_URL = "https://h5.kuaikanmanhua.com"
        val COMMENT_IMAGE_HOST_REGEX = Regex("^https://[^/]*(?:kkmh|v3mh)\\.com/", RegexOption.IGNORE_CASE)
        val COMMENT_IMAGE_FILE_REGEX = Regex("\\.(?:jpe?g|png|webp|gif|avif)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
        const val DESKTOP_USER_AGENT = '''
text = replace_once(text, marker, replacement, "companion image regex")
src.write_text(text, encoding="utf-8")

g = gradle.read_text(encoding="utf-8")
g = replace_once(g, "versionCode = 23", "versionCode = 24", "Kuaikan version")
gradle.write_text(g, encoding="utf-8")

d = docs.read_text(encoding="utf-8")
d = replace_once(d, '- 当前版本：`versionCode = 23`', '- 当前版本：`versionCode = 24`', "docs version")
anchor = "## v16 核心变化\n"
section = '''## v24 核心变化

- 配合 MX 漫画 `663ddc65f2186aeca2da669ef9cdbc96ffc5c0ec` 起的原生评论图片渲染，快看 APP 评论 `content` / `contents` / `content_info` 中的图片 URL 会和文字一起保留下来，不再只读取 `content/text`。
- 图片识别限制在快看实际使用的 `kkmh.com` / `v3mh.com` 图像域名，并覆盖 `/comment/image/`、`/social/`、`-watermark`、常见图片扩展名以及旧版 `v3mh.com/<hash>_<timestamp>` 形式。
- 纯图片评论不会再因为文字为空被通用 `commentFromObject()` 丢弃；图文评论会保留正文和去重后的图片 URL，同时只扫描评论内容字段，避免把用户头像误当评论图片。
- PC 网页评论回退同步读取评论正文里的 `<img>` 和图片链接，避免 APP 评论接口回退后再次丢图。
- 评论能力继续保持只读；本版不开放发表评论、回复写入或点赞写入。
- CI 成功仅代表源码格式、构建、lint 与 MX ABI 可通过；快看真实图片评论显示仍需 Android 实机验证。

'''
if anchor not in d:
    raise SystemExit("Kuaikan docs anchor changed; refusing blind patch")
d = d.replace(anchor, section + anchor, 1)
docs.write_text(d, encoding="utf-8")
