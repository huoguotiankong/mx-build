package eu.kanade.tachiyomi.extension.zh.mengxige

import eu.kanade.tachiyomi.source.model.Filter

internal class RankFilter :
    Filter.Select<String>(
        "榜单",
        rankOptions.map { it.first }.toTypedArray(),
    ) {
    fun value(): String = rankOptions[state].second
}

internal class CategoryFilter :
    Filter.Select<String>(
        "分类",
        categoryOptions.map { it.first }.toTypedArray(),
    ) {
    fun value(): String = categoryOptions[state].second
}

private val rankOptions = listOf(
    "推荐榜" to "1",
    "大热榜" to "2",
    "完结榜" to "3",
    "飙升榜" to "4",
)

private val categoryOptions = listOf(
    "全部" to "0",
    "玄幻异能" to "1",
    "科幻悬疑" to "2",
    "奇幻冒险" to "3",
    "竞技日常" to "4",
)
