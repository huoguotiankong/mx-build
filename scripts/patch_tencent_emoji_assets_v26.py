from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
src = root / "src/zh/tencentcomics/src/eu/kanade/tachiyomi/extension/zh/tencentcomics/TencentComics.kt"
doc = root / "docs/sources/tencentcomics.md"
gradle = root / "src/zh/tencentcomics/build.gradle.kts"

text = src.read_text(encoding="utf-8")
d = doc.read_text(encoding="utf-8")
g = gradle.read_text(encoding="utf-8")

if "versionCode = 26" not in g:
    raise SystemExit("Tencent v26 baseline required before emoji asset correction")

# Verified directly against qqcomic Android 12.19.9 bundled assets:
# assets/chatbuildinemojis/emoji_0.png ... emoji_61.png.
# Keep facial mappings 0-46/48-53/60-61 as existing approximations, but use
# the literal/specific symbols for the seven tiles whose old Unicode guesses
# were demonstrably different from the bundled artwork.
replacements = {
    '            47 to "💰",': '            47 to "壕",',
    '            54 to "🧧",': '            54 to "囍",',
    '            55 to "🀄",': '            55 to "🀅",',
    '            56 to "👏",': '            56 to "服",',
    '            57 to "🚫",': '            57 to "禁",',
    '            58 to "🔥666",': '            58 to "666",',
    '            59 to "🎶857",': '            59 to "857",',
}

for old, new in replacements.items():
    if old in text:
        text = text.replace(old, new, 1)
    elif new not in text:
        raise SystemExit(f"Tencent emoji mapping baseline not found: {old.strip()}")

note = '''

## v26 补充：官方内置数字表情资源逐图核对

2026-09-05 使用用户提供的腾讯动漫 Android 12.19.9 APK 直接核对 `assets/chatbuildinemojis/emoji_0.png` 至 `emoji_61.png`：

- 已确认 APK 内本地数字表情资源只到 61；因此 `[:062:]` 及以上编号继续保持可读占位，不臆测含义。
- 修正此前与官方图片不一致的明确编号：47=`壕`、54=`囍`、55=`🀅`（麻将发财/绿发）、56=`服`、57=`禁`、58=`666`、59=`857`。
- `[:b鼓掌:]` 等命名表情与数字 56 无关；数字 56 的官方内置图片实际是“服”字牌，因此不再错误映射为鼓掌。
- 其余 0-61 的表情映射保持现有 Unicode 近似表示，避免把图片表情强行替换成语义不确定的文本。
'''
if "## v26 补充：官方内置数字表情资源逐图核对" not in d:
    d += note

src.write_text(text, encoding="utf-8")
doc.write_text(d, encoding="utf-8")
