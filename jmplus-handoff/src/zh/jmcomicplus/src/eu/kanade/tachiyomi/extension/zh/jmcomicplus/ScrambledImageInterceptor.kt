package eu.kanade.tachiyomi.extension.zh.jmcomicplus

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.math.floor

internal object ScrambledImageInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val url = request.url
        if (!url.encodedPath.contains("/media/photos/", ignoreCase = true)) return response

        val segments = url.pathSegments
        if (segments.size < 2) return response
        val photoId = segments.getOrNull(segments.lastIndex - 1)?.toIntOrNull() ?: return response
        if (photoId < SCRAMBLE_ID) return response

        val imageIndex = segments.lastOrNull()?.substringBefore('.')?.substringBefore('?').orEmpty()
        if (imageIndex.isBlank()) return response

        val rows = getRows(photoId, imageIndex)
        val responseBuilder = response.newBuilder()
        val input: InputStream = if (response.header("Content-Encoding").equals("gzip", ignoreCase = true)) {
            responseBuilder.headers(
                response.headers.newBuilder()
                    .removeAll("Content-Encoding")
                    .removeAll("Content-Length")
                    .build(),
            )
            GZIPInputStream(response.body.byteStream())
        } else {
            response.body.byteStream()
        }

        val body = runCatching {
            input.use { decodeImage(it, rows) }.asResponseBody(JPEG)
        }.getOrNull() ?: return response

        return responseBuilder
            .removeHeader("Content-Length")
            .header("Content-Type", "image/jpeg")
            .body(body)
            .build()
    }

    private fun md5LastHexCharCode(value: String): Int {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray())
        val lowNibble = digest.last().toInt() and 0x0F
        val c = "0123456789abcdef"[lowNibble]
        return c.code
    }

    private fun getRows(photoId: Int, imageIndex: String): Int {
        val modulus = when {
            photoId >= 421926 -> 8
            photoId >= 268850 -> 10
            else -> return 10
        }
        return 2 * (md5LastHexCharCode(photoId.toString() + imageIndex) % modulus) + 2
    }

    private fun decodeImage(stream: InputStream, rows: Int): Buffer {
        val source = BitmapFactory.decodeStream(stream) ?: error("无法解码禁漫图片")
        val height = source.height
        val width = source.width
        val remainder = height % rows
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        for (x in 0 until rows) {
            var copyH = floor(height / rows.toDouble()).toInt()
            var py = copyH * x
            val y = height - (copyH * (x + 1)) - remainder
            if (x == 0) {
                copyH += remainder
            } else {
                py += remainder
            }
            val src = Rect(0, y, width, y + copyH)
            val dst = Rect(0, py, width, py + copyH)
            canvas.drawBitmap(source, src, dst, null)
        }

        val buffer = Buffer()
        result.compress(Bitmap.CompressFormat.JPEG, 92, buffer.outputStream())
        result.recycle()
        source.recycle()
        return buffer
    }

    private const val SCRAMBLE_ID = 220980
    private val JPEG = "image/jpeg".toMediaType()
}
