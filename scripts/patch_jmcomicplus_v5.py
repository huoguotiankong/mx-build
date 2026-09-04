#!/usr/bin/env python3
from pathlib import Path

ROOT = Path("source")
src = ROOT / "src/zh/jmcomicplus/src/eu/kanade/tachiyomi/extension/zh/jmcomicplus/JmComicPlus.kt"
text = src.read_text("utf-8")

old_extract = """                    DOMAIN_REGEX.findAll(html).map { it.value.trimEnd('/', '\\\\', '\"', '\\'') }.forEach(out::add)
                    HREF_REGEX.findAll(html).mapNotNull { it.groupValues.getOrNull(1) }.forEach(out::add)
"""
new_extract = """                    DOMAIN_REGEX.findAll(html).map { it.value.trimEnd('/', '\\\\', '\"', '\\'') }.forEach(out::add)
                    HREF_REGEX.findAll(html).mapNotNull { it.groupValues.getOrNull(1) }.forEach(out::add)
                    BARE_DOMAIN_REGEX.findAll(html)
                        .mapNotNull { it.groupValues.getOrNull(1) }
                        .map { \"https://${it.lowercase(Locale.ROOT)}\" }
                        .forEach(out::add)
"""
if text.count(old_extract) != 1:
    raise SystemExit("Expected web-domain extraction block exactly once")
text = text.replace(old_extract, new_extract)

old_constants = """        private val WEB_FALLBACK = listOf(WEB_PUBLIC, \"https://18comic.vip\", \"https://18comic.ink\")
        private val ALBUM_ID = Regex(\"/album/(\\\\d+)\", RegexOption.IGNORE_CASE)
        private val PHOTO_ID = Regex(\"/photo/(\\\\d+)\", RegexOption.IGNORE_CASE)
        private val SCRAMBLE_REGEX = Regex(\"var\\\\s+scramble_id\\\\s*=\\\\s*(\\\\d+)\")
        private val DOMAIN_REGEX = Regex(\"https?://(?:18comic|jmcomic|jm-comic|jm365)[^<\\\\\\\"'\\\\s]+\", RegexOption.IGNORE_CASE)
        private val HREF_REGEX = Regex(\"href=[\\\\\\\"'](https?://[^\\\\\\\"'<> ]+)[\\\\\\\"']\", RegexOption.IGNORE_CASE)
"""
new_constants = """        private val WEB_FALLBACK = listOf(
            WEB_PUBLIC,
            \"https://18comic.vip\",
            \"https://18comic.org\",
            \"https://jmcomic-zzz.one\",
            \"https://jmcomic-zzz.org\",
            \"https://18comic-daima.vip\",
            \"https://18comic-daima.org\",
            \"https://18comic-dwo.cc\",
            \"https://18comic.ink\",
        )
        private val ALBUM_ID = Regex(\"/album/(\\\\d+)\", RegexOption.IGNORE_CASE)
        private val PHOTO_ID = Regex(\"/photo/(\\\\d+)\", RegexOption.IGNORE_CASE)
        private val SCRAMBLE_REGEX = Regex(\"var\\\\s+scramble_id\\\\s*=\\\\s*(\\\\d+)\")
        private val DOMAIN_REGEX = Regex(\"https?://(?:18comic|jmcomic|jm-comic|jm365)[^<\\\\\\\"'\\\\s]+\", RegexOption.IGNORE_CASE)
        private val HREF_REGEX = Regex(\"href=[\\\\\\\"'](https?://[^\\\\\\\"'<> ]+)[\\\\\\\"']\", RegexOption.IGNORE_CASE)
        private val BARE_DOMAIN_REGEX = Regex(
            \"(?<![A-Za-z0-9.-])((?:18comic|jmcomic|jm-comic)(?:-[A-Za-z0-9-]+)?(?:\\\\.[A-Za-z0-9-]+)+)(?![A-Za-z0-9.-])\",
            RegexOption.IGNORE_CASE,
        )
"""
if text.count(old_constants) != 1:
    raise SystemExit("Expected companion domain constants block exactly once")
text = text.replace(old_constants, new_constants)
src.write_text(text, "utf-8")

gradle = ROOT / "src/zh/jmcomicplus/build.gradle.kts"
g = gradle.read_text("utf-8")
if g.count("versionCode = 4") != 1:
    raise SystemExit("Expected jmcomicplus versionCode 4 exactly once")
gradle.write_text(g.replace("versionCode = 4", "versionCode = 5"), "utf-8")

doc = ROOT / "docs/sources/jmcomicplus.md"
d = doc.read_text("utf-8")
d = d.replace(
    "- 当前测试版本：`1.6.4`，Android `versionCode=106004`。",
    "- 当前源码候选：`1.6.5`，Android `versionCode=106005`；v4 测试版仍保留供回退。",
)
marker = "- 网页域名通过站点跳转页、发布页和候选域名探测自动更新，缓存 3 小时；扩展设置中可手动固定网页域名。"
replacement = marker + "\n- v5 增强发布页域名发现：除完整 URL / href 外，同时识别官网正文中的 `18comic*`、`jmcomic*` 裸域名；候选必须探活通过才优先缓存，并补充当前官网公布域名作为故障回退。"
if marker not in d:
    raise SystemExit("Maintenance-doc insertion point missing")
d = d.replace(marker, replacement, 1)
d = d.replace(
    "- 测试仓库：v4 已发布到 `mx-repo` 的 `repo/test`，`repo.json`、`index.json` 和 JMComic Plus APK 公开端点均已通过构建链校验。",
    "- 测试仓库：v4 已发布到 `mx-repo` 的 `repo/test`；v5 将在本次源码编译验证通过后进入同签名测试发布。",
)
doc.write_text(d, "utf-8")
