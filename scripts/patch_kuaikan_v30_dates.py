from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


root = Path("dev")
source = root / "src/zh/kuaikanmanhua/src/eu/kanade/tachiyomi/extension/zh/kuaikanmanhua/Kuaikanmanhua.kt"
build = root / "src/zh/kuaikanmanhua/build.gradle.kts"

replace_once(
    source,
    '''        val officialPublicationDates = if (directPublicationDates.size < chapterItems.size) {\n            runCatching { loadOfficialPublicationDates(topicId) }.getOrDefault(emptyMap())\n        } else {\n            emptyMap()\n        }\n\n''',
    '',
)

replace_once(
    source,
    '''                    date_upload = directPublicationDates[id]\n                        ?: officialPublicationDates[id]\n                        ?: normalizeEpoch(\n                            item.long("updated_at")\n                                ?: item.long("updatedAt")\n                                ?: item.long("created_at")\n                                ?: item.long("createdAt")\n                                ?: 0L,\n                        )''',
    '''                    date_upload = directPublicationDates[id]\n                        ?: parsePublicationEpoch(\n                            item.string("updated_at")\n                                ?: item.string("updatedAt")\n                                ?: item.string("update_time")\n                                ?: item.string("updateTime"),\n                        )''',
)

replace_once(
    source,
    '''    private fun explicitPublicationEpoch(obj: JsonObject): Long = sequenceOf(\n        "publishedTime",\n        "published_time",\n        "publishTime",\n        "publish_time",\n        "publishedAt",\n        "published_at",\n    ).firstNotNullOfOrNull { key -> parsePublicationEpoch(obj.string(key)).takeIf { it > 0L } } ?: 0L\n''',
    '''    private fun explicitPublicationEpoch(obj: JsonObject): Long = sequenceOf(\n        "publishedTime",\n        "published_time",\n        "publishTime",\n        "publish_time",\n        "publishedAt",\n        "published_at",\n        "created_at",\n        "createdAt",\n        "create_time",\n        "createTime",\n    ).firstNotNullOfOrNull { key -> parsePublicationEpoch(obj.string(key)).takeIf { it > 0L } } ?: 0L\n''',
)

replace_once(
    source,
    '''        val formats = arrayOf(\n            "yyyy-MM-dd HH:mm:ss",\n            "yyyy-MM-dd HH:mm",\n            "yyyy-MM-dd",\n            "yyyy/MM/dd HH:mm:ss",\n            "yyyy/MM/dd HH:mm",\n            "yyyy/MM/dd",\n            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",\n            "yyyy-MM-dd'T'HH:mm:ssXXX",\n        )\n        return formats.firstNotNullOfOrNull { pattern ->\n            runCatching {\n                SimpleDateFormat(pattern, Locale.ROOT).apply {\n                    isLenient = false\n                    timeZone = CHINA_TIME_ZONE\n                }.parse(raw)?.time\n            }.getOrNull()\n        } ?: 0L\n''',
    '''        // Kuaikan PC topic payload uses two shorthand formats for chapter dates:\n        // previous-year chapters such as 25-12-30, and current-year chapters such as\n        // 01-05 / 09-07. Normalize both before parsing so date_upload never falls back\n        // to the host's chapter discovery time.\n        val normalizedRaw = when {\n            TWO_DIGIT_YEAR_DATE.matches(raw) -> "20$raw"\n            CURRENT_YEAR_MONTH_DAY_DATE.matches(raw) -> {\n                val currentYear = java.util.Calendar.getInstance(CHINA_TIME_ZONE).get(java.util.Calendar.YEAR)\n                "$currentYear-$raw"\n            }\n            else -> raw\n        }\n        val formats = arrayOf(\n            "yyyy-MM-dd HH:mm:ss",\n            "yyyy-MM-dd HH:mm",\n            "yyyy-MM-dd",\n            "yyyy/MM/dd HH:mm:ss",\n            "yyyy/MM/dd HH:mm",\n            "yyyy/MM/dd",\n            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",\n            "yyyy-MM-dd'T'HH:mm:ssXXX",\n        )\n        return formats.firstNotNullOfOrNull { pattern ->\n            runCatching {\n                SimpleDateFormat(pattern, Locale.ROOT).apply {\n                    isLenient = false\n                    timeZone = CHINA_TIME_ZONE\n                }.parse(normalizedRaw)?.time\n            }.getOrNull()\n        } ?: 0L\n''',
)

replace_once(
    source,
    '''        val COMMENT_IMAGE_FILE_REGEX = Regex("\\\\.(?:jpe?g|png|webp|gif|avif)(?:[?#].*)?$", RegexOption.IGNORE_CASE)\n''',
    '''        val COMMENT_IMAGE_FILE_REGEX = Regex("\\\\.(?:jpe?g|png|webp|gif|avif)(?:[?#].*)?$", RegexOption.IGNORE_CASE)\n        val TWO_DIGIT_YEAR_DATE = Regex("^\\\\d{2}[-/]\\\\d{2}[-/]\\\\d{2}(?:[ T].*)?$")\n        val CURRENT_YEAR_MONTH_DAY_DATE = Regex("^\\\\d{2}[-/]\\\\d{2}(?:[ T].*)?$")\n''',
)

replace_once(build, "versionCode = 27", "versionCode = 30")
