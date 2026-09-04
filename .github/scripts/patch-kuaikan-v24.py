from pathlib import Path

src = Path("src/zh/kuaikanmanhua/src/eu/kanade/tachiyomi/extension/zh/kuaikanmanhua/Kuaikanmanhua.kt")
gradle = Path("src/zh/kuaikanmanhua/build.gradle.kts")
docs = Path("docs/sources/kuaikanmanhua.md")

text = src.read_text(encoding="utf-8")
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
                is JsonObject -> (item.string("content") ?: item.string("text"))
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let(parts::add)
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
            COMMENT_IMAGE_FILE_REGEX.containsMatchIn(normalized)
    }
'''
if old_helper not in text:
    raise SystemExit("contentText block changed; refusing blind patch")
text = text.replace(old_helper, new_helper, 1)

old_fallback = '''        val contentObj = obj.obj("content")
        val text = obj.string("content") ?: obj.string("text") ?: contentObj?.string("text") ?: contentObj?.string("content") ?: return null
        val user = obj.obj("user") ?: obj.obj("author") ?: return null
'''
new_fallback = '''        val contentObj = obj.obj("content")
        val directText = obj.string("content") ?: obj.string("text") ?: contentObj?.string("text") ?: contentObj?.string("content")
        val text = buildList {
            directText?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            addAll(commentImageUrls(obj))
        }.distinct().joinToString("\\n")
        if (text.isBlank()) return null
        val user = obj.obj("user") ?: obj.obj("author") ?: return null
'''
if old_fallback not in text:
    raise SystemExit("commentFromObject block changed; refusing blind patch")
text = text.replace(old_fallback, new_fallback, 1)

marker = '''        const val H5_BASE_URL = "https://h5.kuaikanmanhua.com"
        const val DESKTOP_USER_AGENT = '''
replacement = '''        const val H5_BASE_URL = "https://h5.kuaikanmanhua.com"
        val COMMENT_IMAGE_HOST_REGEX = Regex("^https://[^/]*(?:kkmh|v3mh)\\.com/", RegexOption.IGNORE_CASE)
        val COMMENT_IMAGE_FILE_REGEX = Regex("\\.(?:jpe?g|png|webp|gif|avif)(?:[?#].*)?$", RegexOption.IGNORE_CASE)
        const val DESKTOP_USER_AGENT = '''
if marker not in text:
    raise SystemExit("companion marker changed; refusing blind patch")
text = text.replace(marker, replacement, 1)
src.write_text(text, encoding="utf-8")

g = gradle.read_text(encoding="utf-8")
if "versionCode = 23" not in g:
    raise SystemExit("Kuaikan version is no longer 23; refusing duplicate bump")
gradle.write_text(g.replace("versionCode = 23", "versionCode = 24", 1), encoding="utf-8")

d = docs.read_text(encoding="utf-8")
if '- 当前版本：`versionCode = 23`' not in d:
    raise SystemExit("Kuaikan docs version changed; refusing blind patch")
d = d.replace('- 当前版本：`versionCode = 23`', '- 当前版本：`versionCode = 24`', 1)
anchor = "## v16 核心变化\n"
section = '''## v24 核心变化

- 评论富内容解析不再只读取 `content` / `text` 文本字段；会在 APP 评论的 `content`、`contents`、`content_info` 项内递归识别快看评论图片 URL，并把图片 URL 作为独立内容行交给 MX 原生评论组件渲染。
- 图片识别限定在快看实际使用的 `kkmh.com` / `v3mh.com` 图片域名，并覆盖 `/comment/image/`、`/social/`、`-watermark` 以及常见图片扩展名，避免把普通外链误判成评论图片。
- 纯图片评论不会再因文本为空被直接丢弃；图文混排评论会同时保留正文和去重后的图片 URL。
- 历史 JSON / PC 回退评论解析同步补上图片提取，避免 APP 主链路和回退链路显示能力不一致。
- MX APP `663ddc65f2186aeca2da669ef9cdbc96ffc5c0ec` 或更新版本会把这些图片 URL 在原生评论卡片中直接显示为图片；旧宿主仍只会看到 URL 文本。
- 评论功能继续保持只读；本版没有开放发表评论、回复写入或点赞写入。
- 当前公共 Runner 匿名探测 `floor_list` 会返回仅 `code/message/request_id` 的受限响应，因此 v24 的真实图片评论内容仍标记为 **待实机验证**，不能仅凭构建成功宣称端到端可用。

'''
if anchor not in d:
    raise SystemExit("Kuaikan docs anchor changed; refusing blind patch")
d = d.replace(anchor, section + anchor, 1)
docs.write_text(d, encoding="utf-8")
