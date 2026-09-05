from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('.')
src = root / 'src/zh/tencentcomics/src/eu/kanade/tachiyomi/extension/zh/tencentcomics/TencentComics.kt'
text = src.read_text(encoding='utf-8')

old = '''    private fun commentInfoToComment(info: JsonObject, parentTopicId: String): Comment? {
        val content = info.string("content")
            ?.let(::decodeCommentText)
            ?.takeIf(String::isNotBlank)
            ?: return null
'''
new = '''    private fun commentInfoToComment(info: JsonObject, parentTopicId: String): Comment? {
        val textContent = info.string("content")
            ?.let(::decodeCommentText)
            ?.takeIf(String::isNotBlank)
        val content = commentContentWithImages(
            textParts = listOfNotNull(textContent),
            imageUrls = topicImageUrls(info),
        ) ?: return null
'''
if old not in text:
    raise SystemExit('commentInfoToComment baseline not found')
text = text.replace(old, new, 1)

old = '''    private fun topicImageUrls(topic: JsonObject): List<String> = (topic["attach"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        .orEmpty()
        .mapNotNull { attachment ->
            normalizeUrl(attachment.string("picUrl") ?: attachment.string("pic_url"))
        }
        .distinct()
'''
new = '''    private fun topicImageUrls(topic: JsonObject): List<String> {
        val urls = linkedSetOf<String>()

        fun addCandidate(value: String?) {
            normalizeUrl(value)
                ?.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
                ?.let(urls::add)
        }

        fun collect(node: kotlinx.serialization.json.JsonElement?) {
            when (node) {
                is JsonPrimitive -> addCandidate(node.contentOrNull)
                is JsonArray -> node.forEach { collect(it) }
                is JsonObject -> listOf(
                    "picUrl",
                    "pic_url",
                    "imageUrl",
                    "image_url",
                    "url",
                    "src",
                ).forEach { key -> collect(node[key]) }
                else -> Unit
            }
        }

        listOf("attach", "attachments", "pics", "images").forEach { key -> collect(topic[key]) }
        listOf("picUrl", "pic_url", "imageUrl", "image_url").forEach { key -> collect(topic[key]) }
        return urls.toList()
    }
'''
if old not in text:
    raise SystemExit('topicImageUrls baseline not found')
text = text.replace(old, new, 1)

old = '''    private fun decodeCommentText(value: String): String {
        val text = Jsoup.parse(value).text().trim()
        return TENCENT_EMOJI_CODE.replace(text) { match ->
            val code = match.groupValues[1].toIntOrNull()
            if (code == null) match.value else TENCENT_EMOJI_TEXT[code] ?: match.value
        }
    }
'''
new = '''    private fun decodeCommentText(value: String): String {
        val text = Jsoup.parse(value).text().trim()
        return TENCENT_EMOJI_TOKEN.replace(text) { match ->
            val raw = match.groupValues[1].trim()
            val numericCode = raw.toIntOrNull()
            if (numericCode != null) {
                TENCENT_EMOJI_TEXT[numericCode] ?: "【表情：${raw.padStart(3, '0')}】"
            } else {
                val name = raw.removePrefix("b").removePrefix("B").trim().ifBlank { raw }
                TENCENT_EMOJI_NAME_TEXT[name] ?: "【表情：$name】"
            }
        }
    }
'''
if old not in text:
    raise SystemExit('decodeCommentText baseline not found')
text = text.replace(old, new, 1)

old = '        val TENCENT_EMOJI_CODE = Regex("\\\\[:(\\\\d{1,3}):]")\n'
new = '        val TENCENT_EMOJI_TOKEN = Regex("\\\\[:([^:\\\\]\\\\r\\\\n]{1,24}):]")\n'
if old not in text:
    raise SystemExit('emoji regex baseline not found')
text = text.replace(old, new, 1)

marker = '''            61 to "👍",
        )
'''
replacement = '''            61 to "👍",
        )
        val TENCENT_EMOJI_NAME_TEXT = mapOf(
            "鼓掌" to "👏",
            "赞" to "👍",
            "点赞" to "👍",
            "爱心" to "❤️",
            "开心" to "😄",
            "微笑" to "🙂",
            "笑哭" to "😂",
            "大哭" to "😭",
            "哭" to "😭",
            "害羞" to "😊",
            "生气" to "😠",
            "惊讶" to "😮",
            "疑问" to "🤔",
            "666" to "🔥666",
        )
'''
if marker not in text:
    raise SystemExit('emoji map marker not found')
text = text.replace(marker, replacement, 1)
src.write_text(text, encoding='utf-8')

gradle = root / 'src/zh/tencentcomics/build.gradle.kts'
g = gradle.read_text(encoding='utf-8')
if 'versionCode = 24' not in g:
    raise SystemExit('Tencent versionCode 24 baseline not found')
gradle.write_text(g.replace('versionCode = 24', 'versionCode = 25', 1), encoding='utf-8')

doc = root / 'docs/sources/tencentcomics.md'
d = doc.read_text(encoding='utf-8')
d = d.replace('当前开发版本：`versionCode = 24`', '当前开发版本：`versionCode = 25`', 1)
section = '''

## v25：评论附件、CDN 图片与扩展表情兼容

### 2026-09-05 用户实机反馈

- 腾讯评论回复中存在 `manhua.acimg.cn/...jpg/0?tp=sharp` 这类带图片处理尾段的 URL，旧 MX 客户端未识别为图片。
- 评论中仍会出现 `[:b鼓掌:]`、`[:068:]` 等旧式/扩展表情标记。

### 扩展侧处理

- 回复 `commentInfo` 现在和主题评论一样读取官方附件字段，允许纯图片回复。
- 图片字段兼容 `attach / attachments / pics / images`，以及 `picUrl / pic_url / imageUrl / image_url / url / src` 等常见键。
- 腾讯表情解析从仅数字 `[:NNN:]` 扩展为通用 `[:token:]`：已知数字继续映射 Unicode；已知命名表情（例如 `b鼓掌`）映射为对应 emoji；未知数字/命名标记转为可读的 `【表情：...】`，不再原样泄漏协议标签。
- 不猜测未知编号的真实语义；例如 `[:068:]` 在未确认官方资源含义前显示为 `【表情：068】`。

### 验证边界

- 本轮只读评论增强不改变腾讯 APP 评论签名、3DES 解密、排序和回复 API。
- 源码构建/格式/lint 由专用 CI 验证；实际图片与表情最终显示仍以 Android 实机为准。
'''
if '## v25：评论附件、CDN 图片与扩展表情兼容' not in d:
    d += section
doc.write_text(d, encoding='utf-8')
