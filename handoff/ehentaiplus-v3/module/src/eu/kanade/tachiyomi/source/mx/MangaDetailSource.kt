package eu.kanade.tachiyomi.source.mx

import eu.kanade.tachiyomi.source.model.SManga

interface MangaDetailSource {
    suspend fun getMangaDetailInfo(manga: SManga): MangaDetailInfo = MangaDetailInfo()
}

data class MangaDetailInfo(
    val fields: List<MangaDetailField> = emptyList(),
    val replaceDefaultFields: Boolean = false,
)

data class MangaDetailField(
    val label: String,
    val values: List<MangaDetailValue>,
)

data class MangaDetailValue(
    val text: String,
    val action: MangaDetailAction? = null,
)

data class MangaDetailAction(
    val type: MangaDetailActionType,
    val value: String,
)

enum class MangaDetailActionType {
    SOURCE_SEARCH,
    SOURCE_GENRE,
    WEB_URL,
}
