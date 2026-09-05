package eu.kanade.tachiyomi.extension.zh.ehentaiplus

import android.net.Uri
import eu.kanade.tachiyomi.source.model.Filter

internal interface UriFilter {
    fun addToUri(builder: Uri.Builder)
}

internal open class UriGroup<T : Filter<*>>(name: String, state: List<T>) :
    Filter.Group<T>(name, state),
    UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        state.filterIsInstance<UriFilter>().forEach { it.addToUri(builder) }
    }
}

internal class FavoritesFilter :
    Filter.CheckBox("仅我的收藏"),
    UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        if (state) builder.path("/favorites.php")
    }
}

internal class WatchedFilter :
    Filter.CheckBox("监视列表"),
    UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        if (state) builder.path("/watched")
    }
}

internal class CategoryOption(name: String, private val id: String) :
    Filter.CheckBox(name, true),
    UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        builder.appendQueryParameter("f_$id", if (state) "1" else "0")
    }
}

internal class CategoryGroup :
    UriGroup<CategoryOption>(
        "分类",
        listOf(
            CategoryOption("同人志", "doujinshi"),
            CategoryOption("漫画", "manga"),
            CategoryOption("画师 CG", "artistcg"),
            CategoryOption("游戏 CG", "gamecg"),
            CategoryOption("西方作品", "western"),
            CategoryOption("非 H", "non-h"),
            CategoryOption("图集", "imageset"),
            CategoryOption("Cosplay", "cosplay"),
            CategoryOption("亚洲成人", "asianporn"),
            CategoryOption("其他", "misc"),
        ),
    )

internal class AdvancedOption(name: String, private val param: String, default: Boolean = false) :
    Filter.CheckBox(name, default),
    UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        if (state) builder.appendQueryParameter(param, "on")
    }
}

internal open class PageOption(name: String, private val key: String) :
    Filter.Text(name),
    UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        val value = state.trim()
        if (value.isBlank()) return
        if (builder.build().getQueryParameters("f_sp").isEmpty()) builder.appendQueryParameter("f_sp", "on")
        builder.appendQueryParameter(key, value)
    }
}

internal class MinPagesOption : PageOption("最少页数", "f_spf")
internal class MaxPagesOption : PageOption("最多页数", "f_spt")

internal class RatingOption :
    Filter.Select<String>(
        "最低评分",
        arrayOf("不限", "2 星", "3 星", "4 星", "5 星"),
    ),
    UriFilter {
    override fun addToUri(builder: Uri.Builder) {
        if (state > 0) {
            builder.appendQueryParameter("f_srdd", (state + 1).toString())
            builder.appendQueryParameter("f_sr", "on")
        }
    }
}

internal class AdvancedGroup :
    UriGroup<Filter<*>>(
        "高级搜索",
        listOf(
            AdvancedOption("搜索画廊名称", "f_sname", true),
            AdvancedOption("搜索标签", "f_stags", true),
            AdvancedOption("搜索简介", "f_sdesc"),
            AdvancedOption("搜索种子文件名", "f_storr"),
            AdvancedOption("仅显示有种子的画廊", "f_sto"),
            AdvancedOption("搜索低权重标签", "f_sdt1"),
            AdvancedOption("搜索被踩标签", "f_sdt2"),
            AdvancedOption("显示已删除画廊", "f_sh"),
            RatingOption(),
            MinPagesOption(),
            MaxPagesOption(),
        ),
    )

internal class ChineseOnlyFilter(default: Boolean) : Filter.CheckBox("仅中文内容", default)

internal open class TagTextFilter(name: String, val namespace: String) : Filter.Text(name)
