from pathlib import Path
import json
import struct
import urllib.request

ROOT = Path("source")
KOTLIN = ROOT / "src/zh/copymangaplus/src/eu/kanade/tachiyomi/extension/zh/copymangaplus/CopyMangaPlus.kt"
GRADLE = ROOT / "src/zh/copymangaplus/build.gradle.kts"
DOC = ROOT / "docs/sources/copymangaplus.md"
RES = ROOT / "src/zh/copymangaplus/res"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 occurrence, found {count}")
    return text.replace(old, new, 1)


s = KOTLIN.read_text("utf-8")
old_headers = '''        } else {
            val timestamp = (System.currentTimeMillis() / 1000L).toString()
            builder.set("User-Agent", COPY_UA)
            builder.set("Accept", "application/json")
            builder.set("webp", "1")
            builder.set("region", "1")
            builder.set("platform", "3")
            builder.set("source", "copyApp")
            builder.set("version", COPY_VERSION)
            builder.set("referer", "com.copymanga.app-$COPY_REFERER_VERSION")
            builder.set("deviceinfo", copyDeviceInfo())
            builder.set("device", copyDevice())
            builder.set("pseudoid", copyPseudoId())
            builder.set("dt", SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date()))
            builder.set("umstring", COPY_UMSTRING)
            builder.set("x-auth-timestamp", timestamp)
            builder.set("x-auth-signature", copySignature(timestamp))
            builder.set(
                "Authorization",
                if (includeToken && token().isNotBlank()) "Token ${token()}" else "Token",
            )
        }
'''
new_headers = '''        } else {
            builder.set("User-Agent", COPY_UA)
            builder.set("Accept", "application/json")
            builder.set("webp", "1")
            builder.set("region", "1")
            builder.set("platform", "3")
            builder.set("source", "copyApp")
            builder.set("version", COPY_VERSION)
            builder.set("referer", "com.copymanga.app-$COPY_REFERER_VERSION")
            if (includeToken && token().isNotBlank()) builder.set("Authorization", "Token ${token()}")
        }
'''
s = replace_once(s, old_headers, new_headers, "restore v3 global Copy headers")
s = replace_once(s, "private const val ROUTE_SCHEMA = 3", "private const val ROUTE_SCHEMA = 4", "route schema")
KOTLIN.write_text(s, "utf-8")

g = GRADLE.read_text("utf-8")
g = replace_once(g, "versionCode = 4", "versionCode = 5", "versionCode")
GRADLE.write_text(g, "utf-8")

d = DOC.read_text("utf-8")
d = replace_once(d, "- 源码 `versionCode = 4`", "- 源码 `versionCode = 5`", "doc source version")
d = replace_once(d, "- Android APK versionCode：`106004`", "- Android APK versionCode：`106005`", "doc android version")
d = replace_once(d, "- APK versionName：`1.6.4`", "- APK versionName：`1.6.5`", "doc version name")
d += """

## v5 实机反馈与定向修复（2026-09-05）

用户安装 v4 后确认：繁体转简体正常、热辣线路仍正常，但 Copy 首页/最近更新变成“没有结果”；评论页仍显示“请先登录 / 漫画源设置”；v4 图标仍不是用户确认的官方蓝底白色图标。

v5 只修复扩展侧已确认回归：

1. 普通 Copy 内容请求恢复 v3 已经实机通过的轻量移动请求头：`COPY/3.0.0`、`version=3.0.9`、`source=copyApp`、`platform=3`、`region=1`、`webp=1`。不再把 v4 为详情诊断增加的 device/signature 请求头注入首页、搜索、排行、章节和评论等普通请求。
2. v4 的独立详情 `copyDetailHeaders()` 保持不变，详情仍单独使用详情契约，从而隔离“首页已验证契约”和“详情专用契约”。
3. 路由 schema 从 3 增加到 4，v5 首次运行清除 v4 保存的 Copy 动态节点和最近成功节点缓存。
4. 扩展图标改用用户确认的 `com.copymanga.app` 蓝底白色原子状图标 PNG，不再使用 fumiama 第三方客户端的黄底图标资源。

评论页长期登录提示属于 MX App 宿主 UI，不通过扩展伪报 `requiresLoginToPost=false` 隐藏。当前 MX App `main` 已经让 `state.loginRequired` 的底栏分支直接返回 `Unit`；用户截图说明实机仍在使用未包含该 APP 修改的构建，需要单独升级 MX App。

由于 v4 已公开发布签名测试 APK，v5 使用新的 `versionCode=5` / Android `106005`。
"""
DOC.write_text(d, "utf-8")

# Replace the third-party yellow launcher with the user-confirmed blue/white com.copymanga.app icon.
for path in list(RES.rglob("ic_launcher.*")):
    if path.is_file():
        path.unlink()
for rel in ["drawable-anydpi", "mipmap-anydpi-v26"]:
    p = RES / rel
    if p.exists():
        for child in p.iterdir():
            if child.is_file() and child.name.startswith("ic_launcher"):
                child.unlink()

icon_dir = RES / "mipmap-anydpi"
icon_dir.mkdir(parents=True, exist_ok=True)
icon_path = icon_dir / "ic_launcher.png"
url = "https://dl.memuplay.com/new_market/img/com.copymanga.app.icon.2024-11-14-11-25-38.png"
req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
with urllib.request.urlopen(req, timeout=30) as response:
    icon_path.write_bytes(response.read())

b = icon_path.read_bytes()
if b[:8] != b"\x89PNG\r\n\x1a\n" or b[12:16] != b"IHDR":
    raise SystemExit("downloaded launcher is not a valid PNG")
w, h = struct.unpack(">II", b[16:24])
if (w, h) != (512, 512):
    raise SystemExit(f"unexpected launcher size {w}x{h}")
print(f"validated CopyManga launcher {w}x{h}, {len(b)} bytes")
