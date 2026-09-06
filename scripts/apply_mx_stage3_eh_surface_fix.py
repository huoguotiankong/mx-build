from pathlib import Path
import re
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("source")

# Bump above the already-published Stage 3 package.
build = root / "app/build.gradle.kts"
build_text = build.read_text(encoding="utf-8")
build_text, code_count = re.subn(r"versionCode\s*=\s*\d+", "versionCode = 89", build_text, count=1)
build_text, name_count = re.subn(r'versionName\s*=\s*"[^"]+"', 'versionName = "1.14.9"', build_text, count=1)
if code_count != 1 or name_count != 1:
    raise SystemExit("Unable to set MX Stage 3 surface-fix version to 1.14.9/89")
build.write_text(build_text, encoding="utf-8")

# The built-in implementation already registers E-Hentai unconditionally whenever the EH family
# is enabled, while ExHentai is gated by the user's ExHentai preference. Keep that behavior and
# make the two entries unmistakable in the source list.
manager = root / "app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt"
manager_text = manager.read_text(encoding="utf-8")
for marker in (
    "EHENTAI_EXT_SOURCES.forEach",
    "put(id, EHentai(id, false, context, lang))",
    "EXHENTAI_EXT_SOURCES.forEach",
    "put(id, EHentai(id, true, context, lang))",
):
    if marker not in manager_text:
        raise SystemExit(f"Built-in EH source registration marker missing: {marker}")

source = root / "app/src/main/java/eu/kanade/tachiyomi/source/online/all/EHentai.kt"
source_text = source.read_text(encoding="utf-8")
name_block = '''    override val name: String\n        get() = if (exh) "ExHentai（里站）" else "E-Hentai（表站）"\n\n'''
if name_block not in source_text:
    marker = "    override val metaClass = EHentaiSearchMetadata::class\n\n"
    if marker not in source_text:
        raise SystemExit("Unable to locate EHentai source metadata marker for display-name fix")
    source_text = source_text.replace(marker, marker + name_block, 1)
source.write_text(source_text, encoding="utf-8")

# Use anydpi vector assets so Android picks the new built-in icons instead of the legacy bitmap
# resources on every screen density. The old PNGs remain only as fallback resources.
icon_dir = root / "app/src/main/res/mipmap-anydpi"
icon_dir.mkdir(parents=True, exist_ok=True)

(icon_dir / "ic_ehentai_source.xml").write_text(
    '''<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="48dp"\n    android:height="48dp"\n    android:viewportWidth="96"\n    android:viewportHeight="96">\n    <path\n        android:fillColor="#1E5AA8"\n        android:pathData="M8,8h80v80h-80z" />\n    <path\n        android:fillColor="#FFFFFFFF"\n        android:pathData="M25,22h46v11h-32v9h27v11h-27v10h32v11h-46z" />\n    <path\n        android:fillColor="#A9D6FF"\n        android:pathData="M72,18h8v60h-8z" />\n</vector>\n''',
    encoding="utf-8",
)

(icon_dir / "ic_exhentai_source.xml").write_text(
    '''<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="48dp"\n    android:height="48dp"\n    android:viewportWidth="96"\n    android:viewportHeight="96">\n    <path\n        android:fillColor="#74213D"\n        android:pathData="M8,8h80v80h-80z" />\n    <path\n        android:fillColor="#FFFFFFFF"\n        android:pathData="M18,23h31v10h-20v9h17v10h-17v11h20v10h-31z" />\n    <path\n        android:fillColor="#FFFFFFFF"\n        android:pathData="M55,24l8,0l7,12l7,-12l9,0l-12,20l13,29l-10,0l-8,-17l-9,17l-10,0l14,-29z" />\n</vector>\n''',
    encoding="utf-8",
)

# Guardrails for the exact user-visible regression being fixed.
updated_source = source.read_text(encoding="utf-8")
for marker in ("E-Hentai（表站）", "ExHentai（里站）"):
    if marker not in updated_source:
        raise SystemExit(f"EH source display label missing: {marker}")
for icon_name in ("ic_ehentai_source.xml", "ic_exhentai_source.xml"):
    if not (icon_dir / icon_name).is_file():
        raise SystemExit(f"New EH icon missing: {icon_name}")

print("Applied MX Stage 3 E-Hentai/ExHentai surface fix at 1.14.9/89")
