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

internal class ScrambledImageInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val url = original.url
        val pid = url.queryParameter("jm_pid") ?: return chain.proceed(original)
        val scramble = url.queryParameter("jm_scramble")?.toIntOrNull() ?: return chain.proceed(original)
        val filename = url.queryParameter("jm_file").orEmpty()
        val cleanUrl = url.newBuilder()
            .removeAllQueryParameters("jm_pid")
            .removeAllQueryParameters("jm_scramble")
            .removeAllQueryParameters("jm_file")
            .build()
        val response = chain.proceed(original.newBuilder().url(cleanUrl).build())
        if (!response.isSuccessful || filename.isBlank()) return response
        val num = segmentation(scramble, pid.toIntOrNull() ?: return response, filename)
        if (num == 0) return response
        val bytes = response.body.bytes()
        val decoded = runCatching { descramble(bytes, num) }.getOrNull()
        val out = decoded ?: bytes
        val media = if (decoded != null) PNG else response.body.contentType()
        return response.newBuilder().body(out.toResponseBody(media)).build()
    }

    private fun segmentation(scrambleId: Int, aid: Int, filename: String): Int {
        if (aid < scrambleId) return 0
        if (aid < SCRAMBLE_268850) return 10
        val x = if (aid < SCRAMBLE_421926) 10 else 8
        val imageIndex = filename.substringBefore('.')
        val md5 = MessageDigest.getInstance("MD5").digest("$aid$imageIndex".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return ((md5.last().code % x) * 2) + 2
    }

    private fun descramble(bytes: ByteArray, num: Int): ByteArray {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val config = src.config ?: Bitmap.Config.ARGB_8888
        val dst = Bitmap.createBitmap(src.width, src.height, config)
        val canvas = Canvas(dst)
        val over = src.height % num
        for (i in 0 until num) {
            var move = src.height / num
            val ySrc = src.height - (move * (i + 1)) - over
            var yDst = move * i
            if (i == 0) move += over else yDst += over
            if (move <= 0 || ySrc < 0 || ySrc + move > src.height) continue
            canvas.drawBitmap(
                src,
                Rect(0, ySrc, src.width, ySrc + move),
                Rect(0, yDst, src.width, yDst + move),
                null,
            )
        }
        val output = ByteArrayOutputStream()
        dst.compress(Bitmap.CompressFormat.PNG, 100, output)
        src.recycle()
        dst.recycle()
        return output.toByteArray()
    }

    companion object {
        private const val SCRAMBLE_268850 = 268850
        private const val SCRAMBLE_421926 = 421926
        private val PNG = "image/png".toMediaType()
    }
}
