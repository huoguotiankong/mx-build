package eu.kanade.tachiyomi.extension.zh.jmcomicplus

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

internal class ScrambledImageInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) return response

        val path = response.request.url.encodedPath
        if (!path.contains("/media/photos/")) return response
        val parts = path.split('/').filter(String::isNotBlank)
        val mediaIndex = parts.indexOf("media")
        if (mediaIndex < 0 || parts.getOrNull(mediaIndex + 1) != "photos") return response
        val aid = parts.getOrNull(mediaIndex + 2)?.toIntOrNull() ?: return response
        val filename = parts.getOrNull(mediaIndex + 3).orEmpty()
        if (filename.isBlank()) return response

        val num = segmentation(aid, filename)
        if (num == 0) return response
        val body = response.body
        val sourceBytes = runCatching {
            if (response.header("Content-Encoding").equals("gzip", ignoreCase = true)) {
                GZIPInputStream(body.byteStream()).use { it.readBytes() }
            } else {
                body.bytes()
            }
        }.getOrElse { return response }

        val decoded = runCatching { descramble(sourceBytes, num) }.getOrNull() ?: return response
        return response.newBuilder()
            .removeHeader("Content-Encoding")
            .removeHeader("Content-Length")
            .body(decoded.toResponseBody(JPEG))
            .build()
    }

    private fun segmentation(aid: Int, filename: String): Int {
        if (aid < SCRAMBLE_ID) return 0
        if (aid < SCRAMBLE_268850) return 10
        val modulus = if (aid < SCRAMBLE_421926) 10 else 8
        val imageIndex = filename.substringBefore('.').substringBefore('?')
        val digest = MessageDigest.getInstance("MD5")
            .digest("$aid$imageIndex".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return ((digest.last().code % modulus) * 2) + 2
    }

    private fun descramble(bytes: ByteArray, num: Int): ByteArray {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val dst = Bitmap.createBitmap(src.width, src.height, src.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dst)
        val remainder = src.height % num
        for (i in 0 until num) {
            var sliceHeight = src.height / num
            val srcY = src.height - sliceHeight * (i + 1) - remainder
            var dstY = sliceHeight * i
            if (i == 0) sliceHeight += remainder else dstY += remainder
            if (sliceHeight <= 0 || srcY < 0 || srcY + sliceHeight > src.height) continue
            canvas.drawBitmap(
                src,
                Rect(0, srcY, src.width, srcY + sliceHeight),
                Rect(0, dstY, src.width, dstY + sliceHeight),
                null,
            )
        }
        val output = ByteArrayOutputStream()
        dst.compress(Bitmap.CompressFormat.JPEG, 100, output)
        src.recycle()
        dst.recycle()
        return output.toByteArray()
    }

    companion object {
        private const val SCRAMBLE_ID = 220980
        private const val SCRAMBLE_268850 = 268850
        private const val SCRAMBLE_421926 = 421926
        private val JPEG = "image/jpeg".toMediaType()
    }
}
