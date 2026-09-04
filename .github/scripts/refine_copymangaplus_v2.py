from pathlib import Path

kt = Path("source/src/zh/copymangaplus/src/eu/kanade/tachiyomi/extension/zh/copymangaplus/CopyMangaPlus.kt")
s = kt.read_text("utf-8")

def once(old: str, new: str, label: str) -> None:
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    s = s.replace(old, new, 1)

# The verified 3.0.9 mobile contract does not require this list flag either.
once('            add("_update=true")\n', '', "remove list update flag")

# 3.0.9 restored a complete CopyManga chain on the current dynamic API node.
# Keep Copy as the provider-preferred automatic route, but do not allow a v1
# remembered stale node to outrank freshly discovered/fixed current candidates.
once(
    '''        val remembered = if (includeLast) parseExplicit(preferences.getString(PREF_LAST_HOST, null)) else null
        val result = mutableListOf<ApiRoute>()
        when (selected) {
            ROUTE_COPY_AUTO -> {
                remembered?.takeIf { it.kind == RouteKind.COPY }?.let(result::add)
                result += copyCandidates()
            }
            ROUTE_HOT_AUTO -> {
                remembered?.takeIf { it.kind == RouteKind.HOT }?.let(result::add)
                result += hotCandidates()
            }
            else -> {
                remembered?.takeIf { it.kind == RouteKind.HOT }?.let(result::add)
                result += hotCandidates()
                remembered?.takeIf { it.kind == RouteKind.COPY }?.let(result::add)
                result += copyCandidates()
            }
        }
        return result.distinctBy { it.serialized }''',
    '''        val remembered = if (includeLast) parseExplicit(preferences.getString(PREF_LAST_HOST, null)) else null
        val result = mutableListOf<ApiRoute>()
        when (selected) {
            ROUTE_COPY_AUTO -> {
                result += copyCandidates()
                remembered?.takeIf { it.kind == RouteKind.COPY }?.let(result::add)
            }
            ROUTE_HOT_AUTO -> {
                result += hotCandidates()
                remembered?.takeIf { it.kind == RouteKind.HOT }?.let(result::add)
            }
            else -> {
                result += copyCandidates()
                remembered?.takeIf { it.kind == RouteKind.COPY }?.let(result::add)
                result += hotCandidates()
                remembered?.takeIf { it.kind == RouteKind.HOT }?.let(result::add)
            }
        }
        return result.distinctBy { it.serialized }''',
    "prefer fresh current routes",
)

once(
    '                "自动（热辣优先，失败切拷贝）",',
    '                "自动（拷贝 3.0.9 优先，失败切热辣）",',
    "auto label",
)

once(
    '''                "拷贝漫画·动态线路",
                "拷贝漫画·api.copy2000.online",''',
    '''                "拷贝漫画·动态线路",
                "拷贝漫画·api.copy202601.com",
                "拷贝漫画·api.copy2000.online",''',
    "current copy preference entry",
)

once(
    '''                ROUTE_COPY_AUTO,
                "copy:api.copy2000.online",''',
    '''                ROUTE_COPY_AUTO,
                "copy:api.copy202601.com",
                "copy:api.copy2000.online",''',
    "current copy preference value",
)

once(
    '''        private val COPY_HOSTS = listOf(
            "api.copy2000.online",''',
    '''        private val COPY_HOSTS = listOf(
            "api.copy202601.com",
            "api.copy2000.online",''',
    "current fixed Copy host",
)

# Old v1 discovery caches can contain website/share hosts. Filter persisted
# candidates as well as freshly discovered values to API-shaped hosts only.
once(
    '''        val discovered = preferences.getString(PREF_COPY_DISCOVERED, "").orEmpty()
            .split(',').map(String::trim).filter(String::isNotBlank)''',
    '''        val discovered = preferences.getString(PREF_COPY_DISCOVERED, "").orEmpty()
            .split(',').map(String::trim).filter(::isApiHost)''',
    "copy cached host filter",
)
once(
    '''        val discovered = preferences.getString(PREF_HOT_DISCOVERED, "").orEmpty()
            .split(',').map(String::trim).filter(String::isNotBlank)''',
    '''        val discovered = preferences.getString(PREF_HOT_DISCOVERED, "").orEmpty()
            .split(',').map(String::trim).filter(::isApiHost)''',
    "hot cached host filter",
)
once(
    '''    private fun normalizeHost(value: String) = value.trim().removePrefix("https://").removePrefix("http://").trim('/')
''',
    '''    private fun normalizeHost(value: String) = value.trim().removePrefix("https://").removePrefix("http://").trim('/')

    private fun isApiHost(value: String): Boolean {
        val host = normalizeHost(value)
        return host.startsWith("api.") || host.startsWith("mapi.")
    }
''',
    "api host filter helper",
)

kt.write_text(s, "utf-8")
