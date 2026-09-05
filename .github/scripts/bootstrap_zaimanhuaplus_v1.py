from pathlib import Path

MODULE = Path("upstream/src/zh/zaimanhuaplus")
PKG = MODULE / "src/eu/kanade/tachiyomi/extension/zh/zaimanhuaplus"
DEV = Path("dev")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label} not found")
    return text.replace(old, new, 1)


def main() -> None:
    for path in PKG.glob("*.kt"):
        text = path.read_text("utf-8")
        text = text.replace(
            "eu.kanade.tachiyomi.extension.zh.zaimanhua",
            "eu.kanade.tachiyomi.extension.zh.zaimanhuaplus",
        )
        text = text.replace("Zaimanhua.Companion", "ZaimanhuaPlus.Companion")
        path.write_text(text, "utf-8")

    main_file = PKG / "ZaimanhuaPlus.kt"
    text = main_file.read_text("utf-8")
    text = replace_once(text, "abstract class Zaimanhua :", "abstract class ZaimanhuaPlus :", "class declaration")
    text = replace_once(
        text,
        "import eu.kanade.tachiyomi.source.online.HttpSource\n",
        "import eu.kanade.tachiyomi.source.online.HttpSource\n"
        "import eu.kanade.tachiyomi.source.mx.Comment\n"
        "import eu.kanade.tachiyomi.source.mx.CommentPage\n"
        "import eu.kanade.tachiyomi.source.mx.CommentSource\n"
        "import eu.kanade.tachiyomi.source.mx.CommentTarget\n",
        "MX imports",
    )
    text = replace_once(
        text,
        "    HttpSource(),\n    ConfigurableSource {",
        "    HttpSource(),\n    ConfigurableSource,\n    CommentSource {",
        "MX interface",
    )

    client_block = """    override val client: OkHttpClient = network.client.newBuilder()\n        .addInterceptor(::authIntercept)\n        .addInterceptor(::imageRetryInterceptor)\n        .addInterceptor(CommentsInterceptor)\n        .rateLimit(5)\n        .build()\n"""
    client_new = client_block + """
    private val mxComments by lazy {
        ZaimanhuaMxComments(
            client = client,
            headersProvider = { apiHeaders },
            apiUrl = apiUrl,
            mobileBaseUrl = mobileBaseUrl,
        )
    }

    private val autoSign by lazy {
        ZaimanhuaAutoSign(
            client = network.client,
            preferences = preferences,
            baseHeaders = headers,
            accountApiUrl = accountApiUrl,
        )
    }
"""
    text = replace_once(text, client_block, client_new, "client block")

    marker = "        val response = chain.proceed(request)\n"
    text = replace_once(
        text,
        marker,
        "        if (url.contains(tryLoginRegex) && token.isNotBlank()) {\n"
        "            autoSign.runIfNeeded(token)\n"
        "        }\n"
        + marker,
        "auth response marker",
    )

    comment_methods = """
    override val commentCapabilities
        get() = mxComments.capabilities

    override suspend fun getMangaCommentTarget(manga: SManga) = mxComments.getMangaTarget(manga)

    override suspend fun getChapterCommentTarget(manga: SManga, chapter: SChapter) =
        mxComments.getChapterTarget(manga, chapter)

    override suspend fun getComments(target: CommentTarget, page: Int): CommentPage =
        mxComments.getComments(target, page)

    override suspend fun getCommentReplies(
        target: CommentTarget,
        comment: Comment,
        page: Int,
    ): CommentPage = mxComments.getReplies(comment, page)

"""
    text = replace_once(text, "    companion object {\n", comment_methods + "    companion object {\n", "companion marker")

    pref_marker = """            SwitchPreferenceCompat(screen.context).apply {
                key = COMMENTS_PREF
                title = "章末吐槽页"
                summary = "修改后，已加载的章节需要清除章节缓存才能生效。"
                setDefaultValue(false)
            }.let(screen::addPreference)
"""
    pref_new = pref_marker + """
            SwitchPreferenceCompat(screen.context).apply {
                key = AUTO_SIGN_PREF
                title = "自动签到"
                summary = "登录后每天首次使用再漫画 Plus 时自动检查并签到"
                setDefaultValue(true)
            }.let(screen::addPreference)

            EditTextPreference(screen.context).apply {
                title = "签到状态"
                summary = preferences.getString(LAST_SIGN_STATUS_PREF, "尚未检查")
                setEnabled(false)
            }.let(screen::addPreference)
"""
    text = replace_once(text, pref_marker, pref_new, "preference block")
    main_file.write_text(text, "utf-8")

    gradle = MODULE / "build.gradle.kts"
    g = gradle.read_text("utf-8")
    g = replace_once(g, 'name = "Zaimanhua"', 'name = "Zaimanhua Plus"', "extension name")
    g = replace_once(g, "versionCode = 19", "versionCode = 1", "version code")
    g = replace_once(g, 'name = "再漫画"', 'name = "再漫画 Plus"', "source name")
    g += """
android {
    buildTypes {
        named("release") {
            proguardFiles("proguard-rules.pro")
        }
    }
}
"""
    gradle.write_text(g, "utf-8")

    (MODULE / "proguard-rules.pro").write_text(
        "-keep class eu.kanade.tachiyomi.source.mx.** { *; }\n"
        "-keep interface eu.kanade.tachiyomi.source.mx.** { *; }\n",
        "utf-8",
    )

    mx_dir = PKG / "source/mx"
    mx_dir.mkdir(parents=True, exist_ok=True)
    noy = DEV / "src/zh/noyacgplus/src/eu/kanade/tachiyomi/extension/zh/noyacgplus/source/mx"
    for name in ("Comment.kt", "CommentSource.kt"):
        source = noy / name
        if not source.exists():
            raise SystemExit(f"Missing MX ABI stub: {source}")
        (mx_dir / name).write_text(source.read_text("utf-8"), "utf-8")

    features = r'''package eu.kanade.tachiyomi.extension.zh.zaimanhuaplus

import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.mx.Comment
import eu.kanade.tachiyomi.source.mx.CommentAuthor
import eu.kanade.tachiyomi.source.mx.CommentCapabilities
import eu.kanade.tachiyomi.source.mx.CommentPage
import eu.kanade.tachiyomi.source.mx.CommentTarget
import eu.kanade.tachiyomi.source.mx.CommentTargetKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

const val AUTO_SIGN_PREF = "AUTO_SIGN"
const val LAST_SIGN_DAY_PREF = "LAST_SIGN_DAY"
const val LAST_SIGN_ATTEMPT_PREF = "LAST_SIGN_ATTEMPT"
const val LAST_SIGN_STATUS_PREF = "LAST_SIGN_STATUS"

private const val COMMENT_PAGE_SIZE = 10
private const val APP_VERSION = "2.3.7"
private const val APP_CHANNEL = "101_01_01_000"
private const val AUTO_SIGN_RETRY_MS = 10 * 60 * 1000L

private val mxJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

class ZaimanhuaMxComments(
    private val client: OkHttpClient,
    private val headersProvider: () -> Headers,
    private val apiUrl: String,
    private val mobileBaseUrl: String,
) {
    val capabilities = CommentCapabilities(
        supportsMangaComments = true,
        supportsChapterComments = true,
        canPost = false,
        canReply = false,
        canLike = false,
        requiresLoginToPost = true,
    )

    private val replies = ConcurrentHashMap<String, List<Comment>>()

    fun getMangaTarget(manga: SManga) = CommentTarget(
        id = manga.url,
        url = "$mobileBaseUrl/pages/comic/detail?id=${manga.url}",
        kind = CommentTargetKind.MANGA,
    )

    fun getChapterTarget(manga: SManga, chapter: SChapter) = CommentTarget(
        id = chapter.url,
        url = chapter.url.split("/", limit = 2).takeIf { it.size == 2 }?.let { (comicId, chapterId) ->
            "$mobileBaseUrl/pages/comic/page?comic_id=$comicId&chapter_id=$chapterId"
        } ?: "$mobileBaseUrl/pages/comic/detail?id=${manga.url}",
        kind = CommentTargetKind.CHAPTER,
    )

    fun getComments(target: CommentTarget, page: Int): CommentPage = when (target.kind) {
        CommentTargetKind.MANGA -> getBookComments(target.id, page)
        CommentTargetKind.CHAPTER -> getChapterComments(target.id, page)
    }

    fun getReplies(comment: Comment, page: Int): CommentPage {
        val all = replies[comment.id].orEmpty()
        val safePage = page.coerceAtLeast(1)
        val from = (safePage - 1) * COMMENT_PAGE_SIZE
        if (from >= all.size) return CommentPage(emptyList(), false, all.size.toLong())
        val to = minOf(from + COMMENT_PAGE_SIZE, all.size)
        return CommentPage(all.subList(from, to), to < all.size, all.size.toLong())
    }

    private fun getBookComments(comicId: String, page: Int): CommentPage {
        val safePage = page.coerceAtLeast(1)
        val url = "$apiUrl/comment/list".toHttpUrl().newBuilder()
            .addQueryParameter("page", safePage.toString())
            .addQueryParameter("size", COMMENT_PAGE_SIZE.toString())
            .addQueryParameter("type", "4")
            .addQueryParameter("objId", comicId)
            .addQueryParameter("sortBy", "1")
            .addAppParams()
            .build()
        val root = client.newCall(GET(url, headersProvider())).execute().use { response ->
            if (!response.isSuccessful) throw IOException("书评请求失败：HTTP ${response.code}")
            response.body.string().toJsonObject()
        }
        root.throwIfApiError("加载书评")
        val data = root.obj("data") ?: JsonObject(emptyMap())
        val items = data.commentItems()
        val comments = items.mapIndexedNotNull { index, item ->
            item.toMxComment("comment-$safePage-${index + 1}")
        }
        val total = data.longAny("total", "count", "totalNum")
        val hasNext = total?.let { safePage * COMMENT_PAGE_SIZE < it } ?: (comments.size >= COMMENT_PAGE_SIZE)
        return CommentPage(comments, hasNext, total)
    }

    private fun getChapterComments(chapterKey: String, page: Int): CommentPage {
        if (page > 1) return CommentPage(emptyList(), false, null)
        val parts = chapterKey.split("/", limit = 2)
        if (parts.size != 2) throw IOException("章评目标无效")
        val comicId = parts[0]
        val chapterId = parts[1]
        val url = "$apiUrl/viewpoint/list".toHttpUrl().newBuilder()
            .addQueryParameter("type", "0")
            .addQueryParameter("comicId", comicId)
            .addQueryParameter("chapterId", chapterId)
            .addAppParams()
            .build()
        val root = client.newCall(GET(url, headersProvider())).execute().use { response ->
            if (!response.isSuccessful) throw IOException("章评请求失败：HTTP ${response.code}")
            response.body.string().toJsonObject()
        }
        root.throwIfApiError("加载章评")
        val list = root.obj("data")?.arr("list") ?: JsonArray(emptyList())
        val comments = list.mapIndexedNotNull { index, element ->
            val row = element as? JsonArray ?: return@mapIndexedNotNull null
            val commentText = row.getOrNull(7).asString()
                ?: row.lastOrNull().asString()
                ?: return@mapIndexedNotNull null
            Comment(
                id = "vp-$chapterId-${index + 1}",
                author = CommentAuthor(name = "吐槽用户"),
                content = commentText,
                createdAt = 0L,
            )
        }
        return CommentPage(comments, false, comments.size.toLong())
    }

    private fun JsonObject.toMxComment(fallbackId: String, parentId: String? = null): Comment? {
        val authorObject = obj("author")
        val stats = obj("stats")
        val id = stringAny("id", "comment_id", "commentId") ?: fallbackId
        val uid = stringAny("sender_uid", "uid", "user_id") ?: authorObject?.stringAny("uid", "id")
        val nickname = stringAny("nickname", "username", "user_name")
            ?: authorObject?.stringAny("nickname", "username", "name")
            ?: "匿名用户"
        val photo = stringAny("photo", "avatar", "avatar_url")
            ?: authorObject?.stringAny("photo", "avatar", "avatar_url")
        val nested = (arr("replyList") ?: arr("replies"))
            ?.mapIndexedNotNull { index, element ->
                (element as? JsonObject)?.toMxComment("$id-reply-${index + 1}", id)
            }
            .orEmpty()
        replies[id] = nested
        val replyCount = longAny("reply_amount", "reply_count", "replyCount")
            ?: stats?.longAny("reply_amount", "reply_count", "replyCount")
            ?: nested.size.toLong()
        val likeCount = longAny("like_amount", "like_count", "likeCount", "support_amount")
            ?: stats?.longAny("like_amount", "like_count", "likeCount", "support_amount")
            ?: 0L
        val rawTime = stringAny("create_time", "created_at", "time")
        val numericTime = longAny("create_time", "created_at", "time")
        return Comment(
            id = id,
            author = CommentAuthor(
                id = uid,
                name = nickname,
                avatarUrl = photo.toAbsoluteAvatarOrNull(),
            ),
            content = stringAny("content", "comment", "text").orEmpty(),
            createdAt = numericTime?.normalizeEpoch() ?: 0L,
            displayTime = rawTime?.takeIf { numericTime == null },
            likeCount = likeCount,
            replyCount = replyCount,
            parentId = parentId,
        )
    }

    private fun String?.toAbsoluteAvatarOrNull(): String? {
        val value = this?.trim().orEmpty()
        if (value.isEmpty()) return null
        return when {
            value.startsWith("https://") || value.startsWith("http://") -> value
            value.startsWith("//") -> "https:$value"
            else -> null
        }
    }
}

class ZaimanhuaAutoSign(
    private val client: OkHttpClient,
    private val preferences: SharedPreferences,
    private val baseHeaders: Headers,
    private val accountApiUrl: String,
) {
    private val running = AtomicBoolean(false)

    fun runIfNeeded(token: String) {
        if (!preferences.getBoolean(AUTO_SIGN_PREF, true) || token.isBlank()) return
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (preferences.getString(LAST_SIGN_DAY_PREF, "") == today) return
        val now = System.currentTimeMillis()
        val lastAttempt = preferences.getLong(LAST_SIGN_ATTEMPT_PREF, 0L)
        if (now - lastAttempt < AUTO_SIGN_RETRY_MS) return
        if (!running.compareAndSet(false, true)) return
        preferences.edit().putLong(LAST_SIGN_ATTEMPT_PREF, now).apply()
        try {
            if (isSigned(token)) {
                markSigned(today, "今日已签到")
                return
            }
            requestAccount("/task/sign_in", token, post = true).throwIfApiError("自动签到")
            if (!isSigned(token)) throw IOException("签到请求完成，但服务端未确认签到状态")
            markSigned(today, "自动签到成功")
        } catch (e: Exception) {
            preferences.edit()
                .putString(LAST_SIGN_STATUS_PREF, "自动签到失败：${e.message ?: e.javaClass.simpleName}")
                .apply()
        } finally {
            running.set(false)
        }
    }

    private fun isSigned(token: String): Boolean {
        val root = requestAccount("/task/list", token, post = false)
        root.throwIfApiError("检查签到状态")
        val data = root.obj("data") ?: return false
        val task = data.obj("task")
        val signInfo = task?.obj("signInfo") ?: data.obj("signInfo") ?: return false
        return signInfo["current_sign"].asBoolean() ?: signInfo["currentSign"].asBoolean() ?: false
    }

    private fun requestAccount(path: String, token: String, post: Boolean): JsonObject {
        val url = "$accountApiUrl$path".toHttpUrl().newBuilder().addAppParams().build()
        val requestHeaders = baseHeaders.newBuilder()
            .set("Authorization", "Bearer $token")
            .set("Platform", "android")
            .set("Referer", "https://i.zaimanhua.com/")
            .set("Accept", "application/json, text/plain, */*")
            .build()
        val request = if (post) {
            POST(url.toString(), requestHeaders, FormBody.Builder().build())
        } else {
            GET(url, requestHeaders)
        }
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("账号接口失败：HTTP ${response.code}")
            response.body.string().toJsonObject()
        }
    }

    private fun markSigned(day: String, status: String) {
        preferences.edit()
            .putString(LAST_SIGN_DAY_PREF, day)
            .putString(LAST_SIGN_STATUS_PREF, status)
            .apply()
    }
}

private fun okhttp3.HttpUrl.Builder.addAppParams() = apply {
    addQueryParameter("platform", "android")
    addQueryParameter("timestamp", (System.currentTimeMillis() / 1000).toString())
    addQueryParameter("_v", APP_VERSION)
    addQueryParameter("_c", APP_CHANNEL)
}

private fun String.toJsonObject(): JsonObject =
    mxJson.parseToJsonElement(this) as? JsonObject ?: JsonObject(emptyMap())

private fun JsonObject.throwIfApiError(action: String) {
    val errno = longAny("errno", "code") ?: 0L
    if (errno != 0L) throw IOException(stringAny("errmsg", "message", "msg") ?: "$action 失败($errno)")
}

private fun JsonObject.commentItems(): List<JsonObject> {
    val raw = get("commentList")
    return when (raw) {
        is JsonArray -> raw.mapNotNull { it as? JsonObject }
        is JsonObject -> {
            val ids = arr("commentIdList")?.mapNotNull { it.asString() }.orEmpty()
            if (ids.isNotEmpty()) ids.mapNotNull { raw[it] as? JsonObject }
            else raw.values.mapNotNull { it as? JsonObject }
        }
        else -> emptyList()
    }
}

private fun JsonObject.obj(key: String) = get(key) as? JsonObject
private fun JsonObject.arr(key: String) = get(key) as? JsonArray
private fun JsonObject.stringAny(vararg keys: String) = keys.firstNotNullOfOrNull { key -> get(key).asString() }
private fun JsonObject.longAny(vararg keys: String) = keys.firstNotNullOfOrNull { key ->
    val primitive = get(key) as? JsonPrimitive ?: return@firstNotNullOfOrNull null
    primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
}
private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.asBoolean(): Boolean? {
    val primitive = this as? JsonPrimitive ?: return null
    primitive.booleanOrNull?.let { return it }
    primitive.intOrNull?.let { return it != 0 }
    return when (primitive.contentOrNull?.trim()?.lowercase(Locale.ROOT)) {
        "true", "yes", "signed", "1" -> true
        "false", "no", "0" -> false
        else -> null
    }
}
private fun Long.normalizeEpoch() = if (this > 1_000_000_000_000L) this else this * 1000L
'''
    (PKG / "MxFeatures.kt").write_text(features, "utf-8")

    doc = DEV / "docs/sources/zaimanhuaplus.md"
    doc.parent.mkdir(parents=True, exist_ok=True)
    doc.write_text(
        '''# 再漫画 Plus

## 上游基线

- 2026-09-05 新建。
- 直接以当前 Keiyoushi `src/zh/zaimanhua` 为功能基线，保留其登录、账号等级校验、排行榜、筛选、详情、章节、正文、图片重试和章末吐槽图片。
- 当前上游再漫画仍使用 `HttpSource` + `libVersion = "1.4"`，并依赖 legacy `fetchPageList`/Observable、自定义鉴权和图片重试拦截器。为避免首版 Plus 在迁移到 `KeiSource` 时破坏已经验证的登录/阅读链路，v1 暂时保持上游架构；这是项目对旧模板的明确兼容例外。后续若上游迁移到 KeiSource，再同步升级。

## Plus 增强

- MX 书评：`GET /app/v1/comment/list`，参数 `type=4`、`objId=comicId`，分页读取。
- 书评嵌套回复：从 `replyList/replies` 读取，并提供 MX 回复列表。
- MX 章评/吐槽：`GET /app/v1/viewpoint/list?type=0&comicId=...&chapterId=...`。
- 自动签到：账号 API `GET /v1/task/list` 检查 `signInfo.current_sign`，未签到时 `POST /v1/task/sign_in`，随后再次检查确认。
- 自动签到默认开启；成功后当天不重复，失败 10 分钟内不重复尝试。
- 保留上游设置中的“章末吐槽页”，它是阅读器末尾生成图片的兼容功能；MX 章评页面与其互不冲突。

## 登录

保留上游现行登录：

- `POST https://account-api.zaimanhua.com/v1/login/passwd`
- 用户名 + 密码 MD5
- JWT Token 本地持久化
- Token 失效后按上游逻辑重新登录

账号、密码、Token、Cookie 均只保存在客户端运行时/本地偏好，不写入仓库。

## 评论写操作

首版只开放读取书评、书评回复和章评。虽然已找到章评发送接口 `/viewpoint/add`，但书评写入接口和宿主统一写操作尚未完成同等级验证，因此 v1 不声明发布评论、回复或点赞能力。

## 验证状态

- 源码由当前 Keiyoushi 再漫画模块生成并增加 Plus 能力。
- Bootstrap workflow 会在当前 Keiyoushi 框架下执行 Spotless、Debug、Release 与 lint，并验证 Release APK 中保留 MX `CommentSource` ABI。
- CI 通过只代表构建/ABI验证；登录、自动签到、书评、章评和正文仍需用户实机确认。
''',
        "utf-8",
    )


if __name__ == "__main__":
    main()
