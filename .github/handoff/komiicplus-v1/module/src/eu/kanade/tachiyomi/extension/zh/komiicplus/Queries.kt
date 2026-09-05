package eu.kanade.tachiyomi.extension.zh.komiicplus

import eu.kanade.tachiyomi.source.model.MangasPage
import keiyoushi.utils.graphQLBody
import kotlinx.serialization.json.addAll
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.RequestBody

private fun buildQuery(query: String): String {
    val allCategory = categories.takeIf { it.isEmpty() }?.let { "allCategory { id name }" } ?: ""
    return query.replace("#{category}", allCategory).trimIndent()
}

private val COMIC_BODY =
    """
    {
        id
        title
        description
        status
        imageUrl
        authors {
            id
            name
        }
        categories {
            id
            name
        }
        warnings
    }
    """.trimIndent()

fun parseListing(data: DataDto): MangasPage {
    data.allCategory?.let { categories = it }
    val listing = data.getListing()
    val entries = listing.map { it.toSManga() }
    val hasNextPage = listing.size == PAGE_SIZE
    return MangasPage(entries, hasNextPage)
}

fun commonQuery(variables: ListingVariables): RequestBody {
    val operation = if (variables.pagination.orderBy == OrderBy.DATE_UPDATED) "recentUpdate" else "hotComics"
    val query = buildQuery(
        $$"""
        query commonQuery($pagination: Pagination!) {
            comics: $$operation(pagination: $pagination) $$COMIC_BODY
            #{category}
        }
        """,
    )
    return graphQLBody(query, "commonQuery", variables.encode())
}

fun listingQuery(variables: ListingVariables): RequestBody {
    val query = buildQuery(
        $$"""
        query comicByCategories($categoryId: [ID!]!, $pagination: Pagination!) {
            comics: comicByCategories(categoryId: $categoryId, pagination: $pagination) $$COMIC_BODY
            #{category}
        }
        """,
    )
    return graphQLBody(query, "comicByCategories", variables.encode())
}

fun searchQuery(keyword: String): RequestBody {
    val query = buildQuery(
        $$"""
        query searchComicsAndAuthors($keyword: String!) {
            searchComicsAndAuthors(keyword: $keyword) {
                comics $$COMIC_BODY
            }
            #{category}
        }
        """,
    )
    val variables = buildJsonObject { put("keyword", keyword) }
    return graphQLBody(query, "searchComicsAndAuthors", variables)
}

fun recommendQuery(id: String): RequestBody {
    val query =
        $$"""
        query recommendComicById($comicId: ID!) {
            recommendComicById(comicId: $comicId)
        }
        """.trimIndent()
    val variables = buildJsonObject { put("comicId", id) }
    return graphQLBody(query, "recommendComicById", variables)
}

fun idsQuery(ids: List<String>): RequestBody {
    val query =
        $$"""
        query comicByIds($comicIds: [ID]!) {
            comics: comicByIds(comicIds: $comicIds) $$COMIC_BODY
        }
        """.trimIndent()
    val variables = buildJsonObject { putJsonArray("comicIds") { addAll(ids) } }
    return graphQLBody(query, "comicByIds", variables)
}

fun mangaQuery(id: String, fetchDetails: Boolean, fetchChapters: Boolean): RequestBody {
    val query = buildString {
        append($$"query mangaQuery($comicId: ID!) {")
        if (fetchDetails) append($$"comicById(comicId: $comicId) ", COMIC_BODY)
        if (fetchChapters) append($$"chaptersByComicId(comicId: $comicId) { id serial type size dateCreated }")
        append('}')
    }.trimIndent()
    val variables = buildJsonObject { put("comicId", id) }
    return graphQLBody(query, "mangaQuery", variables)
}

fun pageListQuery(chapterId: String): RequestBody {
    val query =
        $$"""
        query imagesByChapterId($chapterId: ID!) {
            imagesByChapterId(chapterId: $chapterId) {
                kid
            }
        }
        """.trimIndent()
    val variables = buildJsonObject { put("chapterId", chapterId) }
    return graphQLBody(query, "imagesByChapterId", variables)
}

fun imageLimitQuery(): RequestBody = graphQLBody(
    """
    query getImageLimit {
        getImageLimit {
            limit
            usage
            resetInSeconds
        }
    }
    """.trimIndent(),
    "getImageLimit",
    buildJsonObject { },
)

fun commentsQuery(comicId: String, page: Int): RequestBody = graphQLBody(
    $$"""
    query getMessagesByComicId($comicId: ID!, $pagination: Pagination!) {
        getMessagesByComicId(comicId: $comicId, pagination: $pagination) {
            id
            comicId
            account {
                id
                nickname
                profileImageUrl
            }
            message
            replyTo { id }
            dateUpdated
            dateCreated
        }
    }
    """.trimIndent(),
    "getMessagesByComicId",
    buildJsonObject {
        put("comicId", comicId)
        put(
            "pagination",
            buildJsonObject {
                put("limit", 100)
                put("offset", (page - 1).coerceAtLeast(0) * 100)
                put("orderBy", "DATE_UPDATED")
                put("asc", true)
            },
        )
    },
)

fun commentRepliesQuery(commentId: String): RequestBody = graphQLBody(
    $$"""
    query messageChan($messageId: ID!) {
        messageChan(messageId: $messageId) {
            id
            comicId
            account {
                id
                nickname
                profileImageUrl
            }
            message
            replyTo { id }
            dateUpdated
            dateCreated
        }
    }
    """.trimIndent(),
    "messageChan",
    buildJsonObject { put("messageId", commentId) },
)

fun commentCountQuery(comicId: String): RequestBody = graphQLBody(
    $$"""
    query messageCountByComicId($comicId: ID!) {
        messageCountByComicId(comicId: $comicId)
    }
    """.trimIndent(),
    "messageCountByComicId",
    buildJsonObject { put("comicId", comicId) },
)
