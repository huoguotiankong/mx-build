package eu.kanade.tachiyomi.extension.zh.komiicplus

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

const val PAGE_SIZE = 30

@Serializable
class ListingVariables(
    val pagination: Pagination,
) {
    @EncodeDefault
    var categoryId: List<String> = emptyList()

    fun encode() = Json.encodeToJsonElement(this) as JsonObject
}

@Suppress("unused")
@Serializable
class Pagination(
    private val offset: Int,
    @EncodeDefault
    var orderBy: OrderBy = OrderBy.DATE_UPDATED,
) {
    @EncodeDefault
    var status: String = ""

    @EncodeDefault
    private val asc: Boolean = false

    @EncodeDefault
    private val limit: Int = PAGE_SIZE

    @EncodeDefault
    var sexyLevel: Int? = null
}

enum class OrderBy {
    DATE_UPDATED,
    DATE_CREATED,
    VIEWS,
    MONTH_VIEWS,
    ID,
    COMIC_DATE_UPDATED,
    FAVORITE_ADDED,
    FAVORITE_COUNT,
}
