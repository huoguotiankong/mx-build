from pathlib import Path

ROOT = Path("source")
KT = ROOT / "src/zh/jmcomicplus/src/eu/kanade/tachiyomi/extension/zh/jmcomicplus/JmComicPlus.kt"
GRADLE = ROOT / "src/zh/jmcomicplus/build.gradle.kts"
DOC = ROOT / "docs/sources/jmcomicplus.md"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


g = GRADLE.read_text("utf-8")
g = replace_once(g, "versionCode = 8", "versionCode = 9", "versionCode")
GRADLE.write_text(g, "utf-8")

s = KT.read_text("utf-8")
old = '''        val rawQuery = query.trim()
        val metadataSearch = rawQuery.startsWith(META_AUTHOR_PREFIX) || rawQuery.startsWith(META_TAG_PREFIX) || rawQuery.startsWith(META_WORK_PREFIX)
        val q = rawQuery
            .removePrefix(META_AUTHOR_PREFIX)
            .removePrefix(META_TAG_PREFIX)
            .removePrefix(META_WORK_PREFIX)
            .trim()
'''
s = replace_once(s, old, '''        val q = query.trim()
''', "clean search query")
dedupe_old = "                if (metadataSearch) result.semanticDedupe() else result"
if s.count(dedupe_old) != 2:
    raise SystemExit(f"dedupe branches: expected 2 matches, found {s.count(dedupe_old)}")
s = s.replace(dedupe_old, "                result.semanticDedupe()")
s = replace_once(
    s,
    '''        val clean = mangas.distinctBy { manga ->
            manga.title.lowercase(Locale.ROOT)
                .replace(Regex("[\\s\\p{Punct}·・]+"), "")
        }
''',
    '''        val clean = mangas.distinctBy { manga ->
            val normalizedTitle = manga.title.lowercase(Locale.ROOT)
                .replace(Regex("[\\s\\p{Punct}·・]+"), "")
            val coverKey = manga.thumbnail_url.orEmpty().substringAfterLast('/').substringBefore('?')
            normalizedTitle + "|" + coverKey
        }
''',
    "dedupe key",
)
s = replace_once(
    s,
    '''            addClickable("作者", authors, META_AUTHOR_PREFIX)
            addClickable("作品", works, META_WORK_PREFIX)
            addClickable("登场人物", actors, META_TAG_PREFIX)
            addClickable("分类", listOfNotNull(category), META_TAG_PREFIX)
            addClickable("标签", tags, META_TAG_PREFIX)
''',
    '''            addClickable("作者", authors)
            addClickable("作品", works)
            addClickable("登场人物", actors)
            addClickable("分类", listOfNotNull(category))
            addClickable("标签", tags)
''',
    "clean detail actions",
)
s = replace_once(
    s,
    '''    private fun MutableList<MangaDetailField>.addClickable(label: String, values: List<String>, queryPrefix: String) {
        val clean = values.map(String::trim).filter(String::isNotBlank).distinct()
        if (clean.isEmpty()) return
        add(
            MangaDetailField(
                label,
                clean.map { value ->
                    MangaDetailValue(
                        value,
                        MangaDetailAction(MangaDetailActionType.SOURCE_SEARCH, queryPrefix + value),
                    )
                },
            ),
        )
    }
''',
    '''    private fun MutableList<MangaDetailField>.addClickable(label: String, values: List<String>) {
        val clean = values.map(String::trim).filter(String::isNotBlank).distinct()
        if (clean.isEmpty()) return
        add(
            MangaDetailField(
                label,
                clean.map { value ->
                    MangaDetailValue(
                        value,
                        MangaDetailAction(MangaDetailActionType.SOURCE_SEARCH, value),
                    )
                },
            ),
        )
    }
''',
    "clean clickable helper",
)
s = replace_once(
    s,
    '''        private const val META_AUTHOR_PREFIX = "__jm_author__:"
        private const val META_TAG_PREFIX = "__jm_tag__:"
        private const val META_WORK_PREFIX = "__jm_work__:"
''',
    "",
    "remove internal prefixes",
)
KT.write_text(s, "utf-8")

with DOC.open("a", encoding="utf-8") as f:
    f.write('''\n\n## v9 元数据搜索展示修正\n\n- 版本：1.6.9 / Android 106009。\n- 作者、作品、人物、分类、标签点击后继续使用用户可见的原始文本，不再向宿主搜索栏暴露内部查询前缀。\n- 搜索结果统一按“规范化标题 + 封面文件”去重：同标题同封面的重复投稿只保留一项，同标题但不同封面的独立版本仍保留。\n- v8 的章评、评论页提示、作者/标签解析与正文解混淆修复全部保留。\n''')

print("JMComic Plus v9 patch applied")
