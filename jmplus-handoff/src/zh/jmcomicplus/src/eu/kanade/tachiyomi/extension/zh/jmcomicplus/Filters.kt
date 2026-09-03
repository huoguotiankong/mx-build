package eu.kanade.tachiyomi.extension.zh.jmcomicplus

import eu.kanade.tachiyomi.source.model.Filter

internal class CategoryFilter :
    Filter.Select<String>(
        "分类",
        LABELS,
    ) {
    fun value(): String = VALUES[state]

    companion object {
        private val LABELS = arrayOf("全部", "同人", "单本", "短篇", "韩漫", "美漫", "其他", "Cosplay", "CG图集")
        private val VALUES = arrayOf("all", "doujin", "single", "short", "hanman", "meiman", "another", "doujin_cosplay", "CG")
    }
}

internal class SortFilter :
    Filter.Select<String>(
        "排序",
        arrayOf("最新", "最多浏览", "最多爱心", "最多图片"),
    ) {
    fun value(): String = arrayOf("mr", "mv", "tf", "mp")[state]
}

internal class TimeFilter :
    Filter.Select<String>(
        "时间",
        arrayOf("全部", "今天", "本周", "本月"),
    ) {
    fun value(): String = arrayOf("a", "t", "w", "m")[state]
}

internal class SearchScopeFilter :
    Filter.Select<String>(
        "搜索范围",
        arrayOf("站内搜索", "作品", "作者", "标签", "登场人物"),
    ) {
    fun value(): String = arrayOf("0", "1", "2", "3", "4")[state]
}
