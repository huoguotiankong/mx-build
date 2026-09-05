from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
src = root / "src/zh/tencentcomics/src/eu/kanade/tachiyomi/extension/zh/tencentcomics/TencentComics.kt"
gradle = root / "src/zh/tencentcomics/build.gradle.kts"
doc = root / "docs/sources/tencentcomics.md"

text = src.read_text(encoding="utf-8")
g = gradle.read_text(encoding="utf-8")
d = doc.read_text(encoding="utf-8")


def replace_once(source: str, old: str, new: str, label: str) -> str:
    if old in source:
        return source.replace(old, new, 1)
    if new in source:
        return source
    raise SystemExit(f"{label} baseline not found")


# The v26 workflow starts from mx-dev/main (currently v24), but keep the patch
# rerunnable against a v25 branch and harmless once v26 has already landed.
if "versionCode = 26" in g:
    required = (
        "TENCENT_EMOJI_PACK_PREFIXES",
        "commentInlineImageUrls",
        "BARE_MEDIA_URL",
        "topicImageUrls(info)",
    )
    missing = [marker for marker in required if marker not in text]
    if missing:
        raise SystemExit("v26 marker(s) missing: " + ", ".join(missing))
    raise SystemExit(0)

if "versionCode = 24" in g:
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
    text = replace_once(text, old, new, "v25 commentInfoToComment")

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
    text = replace_once(text, old, new, "v25 topicImageUrls")

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
    text = replace_once(text, old, new, "v25 decodeCommentText")

    old = '        val TENCENT_EMOJI_CODE = Regex("\\\\[:(\\\\d{1,3}):]")\n'
    new = '        val TENCENT_EMOJI_TOKEN = Regex("\\\\[:([^:\\\\]\\\\r\\\\n]{1,24}):]")\n'
    text = replace_once(text, old, new, "v25 emoji regex")

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
    text = replace_once(text, marker, replacement, "v25 emoji name map")

    g = g.replace("versionCode = 24", "versionCode = 25", 1)
    d = d.replace("当前开发版本：`versionCode = 24`", "当前开发版本：`versionCode = 25`", 1)
    v25_section = '''

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
    if "## v25：评论附件、CDN 图片与扩展表情兼容" not in d:
        d += v25_section

elif "versionCode = 25" not in g:
    raise SystemExit("Tencent versionCode 24/25/26 baseline not found")

# v26: keep all v25 compatibility and make the parser match the additional
# official 12.19.9 emoji packs and inline-media shapes found in the APK.
old = '''    private fun communityTopicToComment(topic: JsonObject): Comment? {
        val title = topic.string("title")
            ?.let(::decodeCommentText)
            ?.takeIf(String::isNotBlank)
        val body = topic.string("content")
            ?.let(::decodeCommentText)
            ?.takeIf(String::isNotBlank)
        val content = commentContentWithImages(
            textParts = listOfNotNull(title, body).distinct(),
            imageUrls = topicImageUrls(topic),
        ) ?: return null
'''
new = '''    private fun communityTopicToComment(topic: JsonObject): Comment? {
        val titleRaw = topic.string("title")
        val bodyRaw = topic.string("content")
        val title = titleRaw
            ?.let(::decodeCommentText)
            ?.takeIf(String::isNotBlank)
        val body = bodyRaw
            ?.let(::decodeCommentText)
            ?.takeIf(String::isNotBlank)
        val content = commentContentWithImages(
            textParts = listOfNotNull(title, body).distinct(),
            imageUrls = commentInlineImageUrls(titleRaw) + commentInlineImageUrls(bodyRaw) + topicImageUrls(topic),
        ) ?: return null
'''
text = replace_once(text, old, new, "v26 communityTopicToComment")

old = '''    private fun topicToComment(topic: JsonObject): Comment? {
        val textContent = topic.string("content")
            ?.let(::decodeCommentText)
            ?.takeIf(String::isNotBlank)
        val content = commentContentWithImages(
            textParts = listOfNotNull(textContent),
            imageUrls = topicImageUrls(topic),
        ) ?: return null
'''
new = '''    private fun topicToComment(topic: JsonObject): Comment? {
        val rawContent = topic.string("content")
        val textContent = rawContent
            ?.let(::decodeCommentText)
            ?.takeIf(String::isNotBlank)
        val content = commentContentWithImages(
            textParts = listOfNotNull(textContent),
            imageUrls = commentInlineImageUrls(rawContent) + topicImageUrls(topic),
        ) ?: return null
'''
text = replace_once(text, old, new, "v26 topicToComment")

old = '''    private fun commentInfoToComment(info: JsonObject, parentTopicId: String): Comment? {
        val textContent = info.string("content")
            ?.let(::decodeCommentText)
            ?.takeIf(String::isNotBlank)
        val content = commentContentWithImages(
            textParts = listOfNotNull(textContent),
            imageUrls = topicImageUrls(info),
        ) ?: return null
'''
new = '''    private fun commentInfoToComment(info: JsonObject, parentTopicId: String): Comment? {
        val rawContent = info.string("content")
        val textContent = rawContent
            ?.let(::decodeCommentText)
            ?.takeIf(String::isNotBlank)
        val content = commentContentWithImages(
            textParts = listOfNotNull(textContent),
            imageUrls = commentInlineImageUrls(rawContent) + topicImageUrls(info),
        ) ?: return null
'''
text = replace_once(text, old, new, "v26 commentInfoToComment")

old = '''                is JsonObject -> listOf(
                    "picUrl",
                    "pic_url",
                    "imageUrl",
                    "image_url",
                    "url",
                    "src",
                ).forEach { key -> collect(node[key]) }
'''
new = '''                is JsonObject -> listOf(
                    "picUrl",
                    "pic_url",
                    "picurl",
                    "imageUrl",
                    "image_url",
                    "originalUrl",
                    "originUrl",
                    "largeUrl",
                    "url",
                    "src",
                ).forEach { key -> collect(node[key]) }
'''
text = replace_once(text, old, new, "v26 nested image keys")

old = '''        listOf("attach", "attachments", "pics", "images").forEach { key -> collect(topic[key]) }
        listOf("picUrl", "pic_url", "imageUrl", "image_url").forEach { key -> collect(topic[key]) }
'''
new = '''        listOf("attach", "attachments", "pics", "images").forEach { key -> collect(topic[key]) }
        listOf(
            "picUrl",
            "pic_url",
            "picurl",
            "imageUrl",
            "image_url",
            "originalUrl",
            "originUrl",
            "largeUrl",
        ).forEach { key -> collect(topic[key]) }
'''
text = replace_once(text, old, new, "v26 top-level image keys")

old = '''    private fun normalizeUrl(value: String?): String? = value?.trim()?.takeIf(String::isNotBlank)?.let {
        when {
            it.startsWith("https:///") -> "https://" + it.removePrefix("https:///")
            it.startsWith("//") -> "https:$it"
            it.startsWith("http://") -> "https://" + it.removePrefix("http://")
            else -> it
        }
    }
'''
new = '''    private fun normalizeUrl(value: String?): String? = value
        ?.trim()
        ?.replace("&amp;", "&")
        ?.takeIf(String::isNotBlank)
        ?.let { candidate ->
            when {
                candidate.startsWith("https:///") -> "https://" + candidate.removePrefix("https:///")
                candidate.startsWith("//") -> "https:$candidate"
                candidate.startsWith("http://") -> "https://" + candidate.removePrefix("http://")
                BARE_MEDIA_URL.matches(candidate) -> "https://$candidate"
                else -> candidate
            }
        }
'''
text = replace_once(text, old, new, "v26 normalizeUrl")

old = '''    private fun decodeCommentText(value: String): String {
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
new = '''    private fun decodeCommentText(value: String): String {
        val text = Jsoup.parse(value).text().trim()
        return TENCENT_EMOJI_TOKEN.replace(text) { match ->
            val raw = match.groupValues[1].trim()
            val numericCode = raw.toIntOrNull()
            if (numericCode != null) {
                TENCENT_EMOJI_TEXT[numericCode] ?: "【表情：${raw.padStart(3, '0')}】"
            } else {
                val name = normalizeTencentEmojiName(raw)
                TENCENT_EMOJI_NAME_TEXT[name] ?: "【表情：$name】"
            }
        }
    }

    private fun normalizeTencentEmojiName(raw: String): String {
        val trimmed = raw.trim()
        val lowered = trimmed.lowercase()
        val prefix = TENCENT_EMOJI_PACK_PREFIXES.firstOrNull { packPrefix ->
            lowered.startsWith(packPrefix) && trimmed.length > packPrefix.length
        }
        return prefix
            ?.let { trimmed.drop(it.length).trim() }
            ?.takeIf(String::isNotBlank)
            ?: trimmed
    }
'''
text = replace_once(text, old, new, "v26 emoji pack normalization")

old = '''    private fun imageUrlFromElement(image: Element): String? = normalizeUrl(
        image.attr("src")
            .ifBlank { image.attr("data-src") }
            .ifBlank { image.attr("data-original") }
            .ifBlank { image.attr("data-url") },
    )
'''
new = '''    private fun imageUrlFromElement(image: Element): String? = normalizeUrl(
        image.attr("src")
            .ifBlank { image.attr("data-src") }
            .ifBlank { image.attr("data-original") }
            .ifBlank { image.attr("data-url") }
            .ifBlank { image.attr("data-actualsrc") }
            .ifBlank { image.attr("data-lazy-src") }
            .ifBlank { image.attr("data-lazyload") },
    )

    private fun commentInlineImageUrls(value: String?): List<String> = value
        ?.takeIf(String::isNotBlank)
        ?.let { html ->
            Jsoup.parseBodyFragment(html)
                .select("img")
                .mapNotNull(::imageUrlFromElement)
                .distinct()
        }
        .orEmpty()
'''
text = replace_once(text, old, new, "v26 inline image extraction")

old = '''        val TENCENT_EMOJI_NAME_TEXT = mapOf(
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
new = '''        val TENCENT_EMOJI_NAME_TEXT = mapOf(
            "OK" to "👌",
            "鼓掌" to "👏",
            "赞" to "👍",
            "点赞" to "👍",
            "称赞" to "👍",
            "太赞了" to "👍",
            "顶起" to "👍",
            "爱心" to "❤️",
            "喜欢" to "❤️",
            "爱你" to "❤️",
            "表白" to "❤️",
            "开心" to "😄",
            "快乐" to "😄",
            "大笑" to "😄",
            "微笑" to "🙂",
            "笑哭" to "😂",
            "大哭" to "😭",
            "哭" to "😭",
            "哭泣" to "😭",
            "我哭" to "😭",
            "伤心" to "😭",
            "好伤心" to "😭",
            "害羞" to "😊",
            "生气" to "😠",
            "发怒" to "😠",
            "暴怒" to "😠",
            "发脾气" to "😠",
            "惊讶" to "😮",
            "惊" to "😱",
            "吓到" to "😱",
            "吓死了" to "😱",
            "吓死宝宝了" to "😱",
            "惊呆了" to "😱",
            "疑问" to "🤔",
            "思考" to "🤔",
            "睡觉" to "😴",
            "吃瓜" to "🍉",
            "喝茶" to "☕",
            "鬼脸" to "😜",
            "666" to "🔥666",
        )
        val TENCENT_EMOJI_PACK_PREFIXES = listOf(
            "bjqgx",
            "xxsy",
            "aadb",
            "zcy",
            "sw",
            "a",
            "b",
            "g",
            "l",
            "q",
            "s",
        )
        val BARE_MEDIA_URL = Regex(
            "^(?:[A-Za-z0-9-]+\\\\.)+[A-Za-z]{2,}(?::\\\\d+)?/.*",
        )
'''
text = replace_once(text, old, new, "v26 emoji map and URL regex")

if "versionCode = 25" not in g:
    raise SystemExit("Tencent v25 transition failed")
g = g.replace("versionCode = 25", "versionCode = 26", 1)

if "当前开发版本：`versionCode = 25`" in d:
    d = d.replace("当前开发版本：`versionCode = 25`", "当前开发版本：`versionCode = 26`", 1)
elif "当前开发版本：`versionCode = 24`" in d:
    d = d.replace("当前开发版本：`versionCode = 24`", "当前开发版本：`versionCode = 26`", 1)

v26_section = '''

## v26：腾讯评论富媒体覆盖补强

### 2026-09-05 APK 逆向核对

- 腾讯动漫 Android 12.19.9 的 DEX 字符串中除 `[:000:]` 等数字表情外，还存在多套命名表情 token，例如 `[:b鼓掌:]`、`[:a笑哭:]`、`[:aadb伤心:]`、`[:bjqgx吃瓜:]`、`[:xxsy无语:]`、`[:zcy哭泣:]` 等。
- APK 中可确认内置 `emoji_0.png` 至 `emoji_61.png`；对 62 以上数字 token 不臆测官方语义，仍使用可读占位 `【表情：NNN】`。

### 扩展侧处理

- 命名表情现在识别 `a / b / g / l / q / s / sw / aadb / bjqgx / xxsy / zcy` 等官方包前缀；可明确语义的名称转换为 Unicode emoji，未知名称保留为可读的 `【表情：名称】`。
- APP 书评、章评和回复在提取正文文字之前额外解析正文 HTML 内的 `<img>`，兼容 `src / data-src / data-original / data-url / data-actualsrc / data-lazy-src / data-lazyload`。
- 附件字段继续兼容 `attach / attachments / pics / images`，并补充 `picurl / originalUrl / originUrl / largeUrl` 等常见图片键。
- 图片 URL 归一化新增裸域名形式（如 `manhua.acimg.cn/...`）自动补 `https://`，同时保留 `//` 与 `http://` 兼容。
- 不改变腾讯 APP 评论请求签名、3DES 解密、排序分页、回复读取 API。

### 验证边界

- CI 负责 `validate_sources.py`、Spotless、Debug/Release 编译与 lint；只有这些真实通过后才标记“构建已验证”。
- 评论图片/表情是否在 MX Android 评论页完整显示仍需用户实机确认；客户端若不识别 `...jpg/0?tp=sharp` 这类 CDN 处理路径，属于 MX APP 富媒体识别层问题，不在扩展内伪装为已解决。
'''
if "## v26：腾讯评论富媒体覆盖补强" not in d:
    d += v26_section

src.write_text(text, encoding="utf-8")
gradle.write_text(g, encoding="utf-8")
doc.write_text(d, encoding="utf-8")
