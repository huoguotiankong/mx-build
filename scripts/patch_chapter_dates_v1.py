from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def append_once(path: Path, marker: str, block: str) -> None:
    text = path.read_text(encoding="utf-8")
    if marker not in text:
        text = text.rstrip() + "\n\n" + block.strip() + "\n"
    path.write_text(text, encoding="utf-8")


root = Path("dev")
kuaikan = root / "src/zh/kuaikanmanhua/src/eu/kanade/tachiyomi/extension/zh/kuaikanmanhua/Kuaikanmanhua.kt"

# v27 real-device feedback showed that publishTime-only lookup was insufficient.
# The official 8.22.0 app stores Comic.created_at/updated_at as TEXT, while v27's
# final fallback read them as numeric primitives. Parse creation time through the
# existing string/numeric date parser and keep updated_at only as the last fallback.
replace_once(
    kuaikan,
    '''    private fun explicitPublicationEpoch(obj: JsonObject): Long = sequenceOf(\n        "publishedTime",\n        "published_time",\n        "publishTime",\n        "publish_time",\n        "publishedAt",\n        "published_at",\n    ).firstNotNullOfOrNull { key -> parsePublicationEpoch(obj.string(key)).takeIf { it > 0L } } ?: 0L\n''',
    '''    private fun explicitPublicationEpoch(obj: JsonObject): Long = sequenceOf(\n        "publishedTime",\n        "published_time",\n        "publishTime",\n        "publish_time",\n        "publishedAt",\n        "published_at",\n        "created_at",\n        "createdAt",\n        "create_time",\n        "createTime",\n    ).firstNotNullOfOrNull { key -> parsePublicationEpoch(obj.string(key)).takeIf { it > 0L } } ?: 0L\n''',
)

replace_once(
    kuaikan,
    '''                    date_upload = directPublicationDates[id]\n                        ?: officialPublicationDates[id]\n                        ?: normalizeEpoch(\n                            item.long("updated_at")\n                                ?: item.long("updatedAt")\n                                ?: item.long("created_at")\n                                ?: item.long("createdAt")\n                                ?: 0L,\n                        )''',
    '''                    date_upload = directPublicationDates[id]\n                        ?: officialPublicationDates[id]\n                        ?: parsePublicationEpoch(\n                            item.string("updated_at")\n                                ?: item.string("updatedAt")\n                                ?: item.string("update_time")\n                                ?: item.string("updateTime"),\n                        )''',
)

replace_once(
    root / "src/zh/kuaikanmanhua/build.gradle.kts",
    "versionCode = 27",
    "versionCode = 28",
)

kdoc = root / "docs/sources/kuaikanmanhua.md"
ktext = kdoc.read_text(encoding="utf-8")
ktext = ktext.replace('- 当前版本：`versionCode = 26`', '- 当前版本：`versionCode = 28`', 1)
ktext = ktext.replace('- 当前版本：`versionCode = 27`', '- 当前版本：`versionCode = 28`', 1)
kdoc.write_text(ktext, encoding="utf-8")
append_once(
    kdoc,
    "## v28：按官方 Comic created_at 修正章节发布时间",
    '''## v28：按官方 Comic created_at 修正章节发布时间

- 用户 2026-09-09 Android 实机确认：v27 的章节时间仍不正确，因此 v27 记录为实机失败。
- 复核官方快看 8.22.0 APK：`Comic` 持久化模型中的 `created_at`、`updated_at` 为 `TEXT`；v27 最终兜底按 Long 读取，字符串日期会被丢弃。
- v28 在显式 `publish*` 字段之后加入 `created_at / createdAt / create_time / createTime`，统一交给现有字符串/数字日期解析器。
- `updated_at` 仅保留为最终兜底，并同样按字符串日期解析；避免后续编辑/批量迁移时间覆盖首次发布时间。
- `/v1/topics/{topicId}` 的章节 ID 时间补全同步采用同一优先级。
- 源码 `versionCode` 升至 28；构建通过不等于实机日期已验证，仍需 Android 复测。''',
)

tdoc = root / "docs/sources/tencentcomics.md"
ttext = tdoc.read_text(encoding="utf-8")
ttext = ttext.replace('- 当前开发版本：`versionCode = 28`', '- 当前开发版本：`versionCode = 31`', 1)
tdoc.write_text(ttext, encoding="utf-8")
append_once(
    tdoc,
    "## v31：章节发布时间实机验证通过",
    '''## v31：章节发布时间实机验证通过

- 用户 2026-09-09 Android 实机确认：腾讯动漫 v31 章节时间已经恢复正常。
- v31 的 PC 目录 + 官方 Android `Comic/comicChapterList` `updateDate` 按 chapterId 合并方案据此提升为已实机验证。
- 腾讯章节时间本轮收尾，快看后续修复不再改动腾讯实现。''',
)
