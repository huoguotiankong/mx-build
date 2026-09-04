package eu.kanade.tachiyomi.extension.zh.jmcomicplus

import eu.kanade.tachiyomi.source.model.Filter

internal abstract class JmSelectFilter(
    displayName: String,
    private val options: List<Pair<String, String>>,
    defaultValue: Int = 0,
) : Filter.Select<String>(displayName, options.map { it.first }.toTypedArray(), defaultValue) {
    fun value(): String = options[state].second
}

internal class JmSortFilter :
    JmSelectFilter(
        "排序",
        listOf(
            "最新" to "mr",
            "热门" to "mv",
            "阅读日榜" to "mv_t",
            "阅读周榜" to "mv_w",
            "阅读月榜" to "mv_m",
        ),
    )

internal class JmCategoryFilter :
    JmSelectFilter(
        "分类",
        listOf(
            "全部" to "all",
            "同人" to "doujin",
            "单本" to "single",
            "短篇" to "short",
            "韩漫" to "hanman",
            "美漫" to "meiman",
            "其他" to "another",
            "COS" to "doujin_cosplay",
        ),
    )
