from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def append_once(path: Path, marker: str, block: str) -> None:
    text = path.read_text(encoding="utf-8")
    if marker in text:
        return
    path.write_text(text.rstrip() + "\n\n" + block.strip() + "\n", encoding="utf-8")


root = Path("dev")
tencent = root / "src/zh/tencentcomics/src/eu/kanade/tachiyomi/extension/zh/tencentcomics/TencentComics.kt"
kuaikan = root / "src/zh/kuaikanmanhua/src/eu/kanade/tachiyomi/extension/zh/kuaikanmanhua/Kuaikanmanhua.kt"

# Tencent: the PC chapter page does not expose per-chapter dates. Keep it as the
# authority for ordering/title/lock state, and merge updateDate from the official
# Android chapter list API by chapterId.
replace_once(
    tencent,
    "import kotlinx.serialization.json.JsonArray\nimport kotlinx.serialization.json.JsonObject",
    "import kotlinx.serialization.json.JsonArray\nimport kotlinx.serialization.json.JsonElement\nimport kotlinx.serialization.json.JsonObject",
)
replace_once(
    tencent,
    "import java.security.MessageDigest\nimport java.util.concurrent.ConcurrentHashMap",
    "import java.security.MessageDigest\nimport java.text.SimpleDateFormat\nimport java.util.Locale\nimport java.util.TimeZone\nimport java.util.concurrent.ConcurrentHashMap",
)
replace_once(
    tencent,
    '''    override fun chapterListParse(response: Response): List<SChapter> {\n        val document = response.asJsoup()\n        return document.select(".works-chapter-item").map { element ->\n            SChapter.create().apply {\n                setUrlWithoutDomain(element.select("a").attr("abs:href"))\n                name = (if (element.isLockedChapter()) "\\uD83D\\uDD12 " else "") + element.text()\n            }\n        }.reversed()\n    }\n\n    private fun Element.isLockedChapter(): Boolean = selectFirst(".ui-icon-pay") != null\n''',
    '''    override fun chapterListParse(response: Response): List<SChapter> {\n        val document = response.asJsoup()\n        val comicId = PC_COMIC_ID.find(response.request.url.encodedPath)?.groupValues?.getOrNull(1)\n        val uploadDates = comicId\n            ?.let { id -> runCatching { loadOfficialChapterUploadDates(id) }.getOrDefault(emptyMap()) }\n            .orEmpty()\n\n        return document.select(".works-chapter-item").map { element ->\n            val href = element.select("a").attr("abs:href")\n            val chapterId = CHAPTER_IDS.find(href)?.groupValues?.getOrNull(2)\n            SChapter.create().apply {\n                setUrlWithoutDomain(href)\n                name = (if (element.isLockedChapter()) "\\uD83D\\uDD12 " else "") + element.text()\n                date_upload = chapterId?.let(uploadDates::get) ?: 0L\n            }\n        }.reversed()\n    }\n\n    private fun Element.isLockedChapter(): Boolean = selectFirst(".ui-icon-pay") != null\n\n    private fun loadOfficialChapterUploadDates(comicId: String): Map<String, Long> {\n        val dates = linkedMapOf<String, Long>()\n        for (page in 1..APP_CHAPTER_LIST_MAX_PAGES) {\n            val data = APP_API_HOSTS.firstNotNullOfOrNull { host ->\n                runCatching {\n                    requestAppData(\n                        (\n                            "$host/$APP_VERSION/Comic/comicChapterList" +\n                                "/comic_id/$comicId" +\n                                "/page/$page" +\n                                "/listcnt/$APP_CHAPTER_LIST_PAGE_SIZE"\n                            ).toHttpUrl(),\n                        "章节时间",\n                    )\n                }.getOrNull()\n            } ?: break\n\n            val before = dates.size\n            collectChapterUploadDates(data, dates)\n            if (dates.size == before || dates.size - before < APP_CHAPTER_LIST_PAGE_SIZE) break\n        }\n        return dates\n    }\n\n    private fun collectChapterUploadDates(element: JsonElement, destination: MutableMap<String, Long>) {\n        when (element) {\n            is JsonObject -> {\n                val chapterId = element.string("chapterId") ?: element.string("chapter_id")\n                val date = parseChapterUploadDate(\n                    element.string("updateDate")\n                        ?: element.string("update_date")\n                        ?: element.string("publishedTime")\n                        ?: element.string("published_time")\n                        ?: element.string("publishTime")\n                        ?: element.string("publish_time"),\n                )\n                if (!chapterId.isNullOrBlank() && date > 0L) destination[chapterId] = date\n                element.values.forEach { child -> collectChapterUploadDates(child, destination) }\n            }\n\n            is JsonArray -> element.forEach { child -> collectChapterUploadDates(child, destination) }\n            else -> Unit\n        }\n    }\n\n    private fun parseChapterUploadDate(value: String?): Long {\n        val raw = value?.trim().orEmpty()\n        if (raw.isEmpty()) return 0L\n        raw.toLongOrNull()?.let { numeric ->\n            return when {\n                numeric >= 100_000_000_000L -> numeric\n                numeric >= 1_000_000_000L -> numeric * 1000L\n                else -> 0L\n            }\n        }\n        val formats = arrayOf(\n            "yyyy-MM-dd HH:mm:ss",\n            "yyyy-MM-dd HH:mm",\n            "yyyy-MM-dd",\n            "yyyy/MM/dd HH:mm:ss",\n            "yyyy/MM/dd HH:mm",\n            "yyyy/MM/dd",\n            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",\n            "yyyy-MM-dd'T'HH:mm:ssXXX",\n        )\n        return formats.firstNotNullOfOrNull { pattern ->\n            runCatching {\n                SimpleDateFormat(pattern, Locale.ROOT).apply {\n                    isLenient = false\n                    timeZone = CHINA_TIME_ZONE\n                }.parse(raw)?.time\n            }.getOrNull()\n        } ?: 0L\n    }\n''',
)
replace_once(
    tencent,
    '        const val APP_TOPIC_COMMENT_PAGE_SIZE = 20\n        const val APP_RESPONSE_DES_EDE3_KEY',
    '        const val APP_TOPIC_COMMENT_PAGE_SIZE = 20\n        const val APP_CHAPTER_LIST_PAGE_SIZE = 100\n        const val APP_CHAPTER_LIST_MAX_PAGES = 30\n        val CHINA_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")\n        const val APP_RESPONSE_DES_EDE3_KEY',
)

# Kuaikan: created_at is content creation/draft time and must not win over the
# explicit publication timestamp. The official app model contains publishedTime.
replace_once(
    kuaikan,
    "import java.io.IOException\nimport java.net.URLEncoder\nimport java.util.concurrent.ConcurrentHashMap",
    "import java.io.IOException\nimport java.net.URLEncoder\nimport java.text.SimpleDateFormat\nimport java.util.Locale\nimport java.util.TimeZone\nimport java.util.concurrent.ConcurrentHashMap",
)
replace_once(
    kuaikan,
    '''        val chapterItems = pageData.array("comicList")\n            ?: pageData.array("comics")\n            ?: info.array("comics")\n            ?: JsonArray(emptyList())\n\n        val chapters = chapterItems\n''',
    '''        val chapterItems = pageData.array("comicList")\n            ?: pageData.array("comics")\n            ?: info.array("comics")\n            ?: JsonArray(emptyList())\n        val directPublicationDates = chapterItems\n            .mapNotNull { it as? JsonObject }\n            .mapNotNull { item ->\n                val id = item.long("id") ?: return@mapNotNull null\n                explicitPublicationEpoch(item).takeIf { it > 0L }?.let { id to it }\n            }\n            .toMap()\n        val officialPublicationDates = if (directPublicationDates.size < chapterItems.size) {\n            runCatching { loadOfficialPublicationDates(topicId) }.getOrDefault(emptyMap())\n        } else {\n            emptyMap()\n        }\n\n        val chapters = chapterItems\n''',
)
replace_once(
    kuaikan,
    '                    date_upload = normalizeEpoch(item.long("created_at") ?: item.long("updated_at") ?: 0L)',
    '''                    date_upload = directPublicationDates[id]\n                        ?: officialPublicationDates[id]\n                        ?: normalizeEpoch(\n                            item.long("updated_at")\n                                ?: item.long("updatedAt")\n                                ?: item.long("created_at")\n                                ?: item.long("createdAt")\n                                ?: 0L,\n                        )''',
)
replace_once(
    kuaikan,
    '''    private fun normalizeEpoch(value: Long): Long = when {\n        value <= 0L -> 0L\n        value < 100_000_000_000L -> value * 1000L\n        else -> value\n    }\n''',
    '''    private fun normalizeEpoch(value: Long): Long = when {\n        value <= 0L -> 0L\n        value < 100_000_000_000L -> value * 1000L\n        else -> value\n    }\n\n    private fun explicitPublicationEpoch(obj: JsonObject): Long = sequenceOf(\n        "publishedTime",\n        "published_time",\n        "publishTime",\n        "publish_time",\n        "publishedAt",\n        "published_at",\n    ).firstNotNullOfOrNull { key -> parsePublicationEpoch(obj.string(key)).takeIf { it > 0L } } ?: 0L\n\n    private fun loadOfficialPublicationDates(topicId: Long): Map<Long, Long> {\n        val root = getJson("$apiUrl/v1/topics/$topicId")\n        val dates = linkedMapOf<Long, Long>()\n\n        fun collect(element: JsonElement, depth: Int) {\n            if (depth > 10) return\n            when (element) {\n                is JsonObject -> {\n                    val id = element.long("id")\n                    val published = explicitPublicationEpoch(element)\n                    if (id != null && published > 0L) dates[id] = published\n                    element.values.forEach { child ->\n                        if (child is JsonObject || child is JsonArray) collect(child, depth + 1)\n                    }\n                }\n\n                is JsonArray -> element.forEach { child -> collect(child, depth + 1) }\n                else -> Unit\n            }\n        }\n\n        collect(root, 0)\n        return dates\n    }\n\n    private fun parsePublicationEpoch(value: String?): Long {\n        val raw = value?.trim().orEmpty()\n        if (raw.isEmpty()) return 0L\n        raw.toLongOrNull()?.let { numeric ->\n            return when {\n                numeric >= 100_000_000_000L -> numeric\n                numeric >= 1_000_000_000L -> numeric * 1000L\n                else -> 0L\n            }\n        }\n        val formats = arrayOf(\n            "yyyy-MM-dd HH:mm:ss",\n            "yyyy-MM-dd HH:mm",\n            "yyyy-MM-dd",\n            "yyyy/MM/dd HH:mm:ss",\n            "yyyy/MM/dd HH:mm",\n            "yyyy/MM/dd",\n            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",\n            "yyyy-MM-dd'T'HH:mm:ssXXX",\n        )\n        return formats.firstNotNullOfOrNull { pattern ->\n            runCatching {\n                SimpleDateFormat(pattern, Locale.ROOT).apply {\n                    isLenient = false\n                    timeZone = CHINA_TIME_ZONE\n                }.parse(raw)?.time\n            }.getOrNull()\n        } ?: 0L\n    }\n''',
)
replace_once(
    kuaikan,
    '        const val H5_BASE_URL = "https://h5.kuaikanmanhua.com"\n        val MEME_CODE_REGEX',
    '        const val H5_BASE_URL = "https://h5.kuaikanmanhua.com"\n        val CHINA_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")\n        val MEME_CODE_REGEX',
)

replace_once(root / "src/zh/tencentcomics/build.gradle.kts", "versionCode = 30", "versionCode = 31")
replace_once(root / "src/zh/kuaikanmanhua/build.gradle.kts", "versionCode = 26", "versionCode = 27")

append_once(
    root / "docs/sources/tencentcomics.md",
    "## v31：修复章节发布时间",
    '''## v31：修复章节发布时间\n\n- 根据用户 2026-09-09 实机反馈，章节目录中大量不同周更新的章节被统一显示为“1 天前”。\n- 根因：PC 章节列表负责标题、顺序和付费锁状态，但当前实现从未给 `SChapter.date_upload` 赋真实章节时间。\n- v31 保留 PC 目录作为顺序/标题/锁状态事实源，并按 `chapterId` 合并官方 Android `Comic/comicChapterList` 返回的 `updateDate`。\n- APP 时间接口不可用时不伪造日期，回退 `date_upload = 0`，避免再次把抓取时间误当发布时间。\n- 构建状态：待公共构建器验证；Android 实机显示仍待验证。''',
)
append_once(
    root / "docs/sources/kuaikanmanhua.md",
    "## v27：修复章节发布时间",
    '''## v27：修复章节发布时间\n\n- 根据用户 2026-09-09 实机反馈，同一作品连续章节出现“1 天前 / 1 天前 / 6 天前”等与官方发布时间不符的目录时间。\n- 根因：旧实现优先使用 `created_at`，该字段可能代表章节创建/草稿时间，并不等于正式发布时间；官方 8.22.0 APK 的章节模型另有 `publishedTime`。\n- v27 改为显式发布时间字段优先；当前网页 payload 缺失时仅额外请求一次官方 `/v1/topics/{topicId}`，按章节 ID 合并 `publishedTime`。\n- 只有没有显式发布时间时才依次回退 `updated_at`、`created_at`，不再让创建时间覆盖发布时间。\n- 构建状态：待公共构建器验证；Android 实机显示仍待验证。''',
)
