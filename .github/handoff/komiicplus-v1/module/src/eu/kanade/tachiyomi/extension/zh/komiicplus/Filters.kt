package eu.kanade.tachiyomi.extension.zh.komiicplus

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

var categories: List<ItemDto> = emptyList()

fun buildFilterList(): FilterList {
    val categoryFilter = if (categories.isNotEmpty()) {
        CategoryFilter()
    } else {
        Filter.Header("点击“重置”加载分类")
    }
    return FilterList(
        Filter.Header("筛选条件（输入搜索关键词时无效）"),
        categoryFilter,
        SortFilter(),
        StatusFilter(),
        RatingFilter(),
    )
}

interface KomiicFilter {
    fun apply(variables: ListingVariables)
}

class Category(val id: String, name: String) : Filter.CheckBox(name)

class CategoryFilter :
    Filter.Group<Category>("分类（同时包含全部所选标签）", categories.map { Category(it.id, it.name) }),
    KomiicFilter {
    override fun apply(variables: ListingVariables) {
        variables.categoryId = state.mapNotNull { if (it.state) it.id else null }
    }
}

class StatusFilter :
    Filter.Select<String>("状态", arrayOf("全部", "连载", "完结")),
    KomiicFilter {
    override fun apply(variables: ListingVariables) {
        variables.pagination.status = arrayOf("", "ONGOING", "END")[state]
    }
}

class SortFilter :
    Filter.Select<String>("排序", arrayOf("更新", "本月观看数（不能筛选分类）", "观看数", "喜爱数")),
    KomiicFilter {
    override fun apply(variables: ListingVariables) {
        variables.pagination.orderBy = arrayOf(OrderBy.DATE_UPDATED, OrderBy.MONTH_VIEWS, OrderBy.VIEWS, OrderBy.FAVORITE_COUNT)[state]
    }
}

class RatingFilter :
    Filter.Select<String>("色气程度", arrayOf("全部", "无", "1", "2", "3", "≥4", "5")),
    KomiicFilter {
    override fun apply(variables: ListingVariables) {
        variables.pagination.sexyLevel = arrayOf(null, 0, 1, 2, 3, 4, 5)[state]
    }
}
