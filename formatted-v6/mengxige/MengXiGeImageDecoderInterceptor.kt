package eu.kanade.tachiyomi.extension.zh.mengxige

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val SCRAMBLE_PREFIX_SIZE = 400
private const val MENGXIGE_APP_PACKAGE = "com.mengxigeyd.novel"
private const val IMAGE_BODY_ATTEMPTS = 3
private const val IMAGE_RETRY_DELAY_MS = 150L

private val RETRYABLE_IMAGE_HTTP_CODES = setOf(408, 425, 429, 500, 502, 503, 504)

private val IMAGE_HTTP1_FALLBACK_CLIENT by lazy {
    OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()
}

internal class MengXiGeImageDecoderInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val request = if (
            originalRequest.header("package") == MENGXIGE_APP_PACKAGE &&
            originalRequest.header("stamp") != null
        ) {
            originalRequest.newBuilder()
                .header("stamp", "")
                .build()
        } else {
            originalRequest
        }

        if (!isPossibleScrambledImage(request.url.host, request.url.encodedPath)) {
            return chain.proceed(request)
        }

        var lastFailure: IOException? = null

        for (attempt in 0 until IMAGE_BODY_ATTEMPTS) {
            val response = try {
                if (attempt == 0) {
                    chain.proceed(request)
                } else {
                    IMAGE_HTTP1_FALLBACK_CLIENT.newCall(request).execute()
                }
            } catch (e: IOException) {
                lastFailure = e
                if (attempt + 1 >= IMAGE_BODY_ATTEMPTS) throw imageFailure(e)
                retryDelay(attempt)
                continue
            }

            if (!response.isSuccessful) {
                if (response.code in RETRYABLE_IMAGE_HTTP_CODES && attempt + 1 < IMAGE_BODY_ATTEMPTS) {
                    response.close()
                    retryDelay(attempt)
                    continue
                }
                return response
            }

            try {
                val body = response.body
                val mediaType = body.contentType()
                val bytes = body.bytes()
                val restored = restoreImageIfNeeded(bytes)
                return response.newBuilder()
                    .body(restored.toResponseBody(mediaType))
                    .build()
            } catch (e: IOException) {
                response.close()
                lastFailure = e
                if (attempt + 1 >= IMAGE_BODY_ATTEMPTS) throw imageFailure(e)
                retryDelay(attempt)
            }
        }

        throw imageFailure(lastFailure)
    }

    private fun restoreImageIfNeeded(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty() || isPlainImage(bytes)) return bytes

        val prefixSize = minOf(SCRAMBLE_PREFIX_SIZE, bytes.size)
        val decodedPrefixSize = (prefixSize + 1) / 2
        val output = ByteArray(decodedPrefixSize + bytes.size - prefixSize)

        var sourceIndex = 0
        var targetIndex = 0
        while (sourceIndex < prefixSize) {
            output[targetIndex++] = bytes[sourceIndex]
            sourceIndex += 2
        }

        bytes.copyInto(
            destination = output,
            destinationOffset = targetIndex,
            startIndex = prefixSize,
            endIndex = bytes.size,
        )
        return output
    }

    private fun retryDelay(attempt: Int) {
        try {
            Thread.sleep(IMAGE_RETRY_DELAY_MS * (attempt + 1))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("梦溪阁图片重试被中断", e)
        }
    }

    private fun imageFailure(cause: IOException?): IOException = IOException("梦溪阁图片连接中断，自动重试后仍失败", cause)

    private fun isPossibleScrambledImage(host: String, path: String): Boolean = host.startsWith("c-chapter.", ignoreCase = true) ||
        host.startsWith("c-res.", ignoreCase = true) ||
        path.substringBefore('?').endsWith(".h", ignoreCase = true)

    private fun isPlainImage(bytes: ByteArray): Boolean {
        val jpeg = bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xFF) == 0xD8 &&
            (bytes[2].toInt() and 0xFF) == 0xFF

        val png = bytes.size >= 8 &&
            (bytes[0].toInt() and 0xFF) == 0x89 &&
            (bytes[1].toInt() and 0xFF) == 0x50 &&
            (bytes[2].toInt() and 0xFF) == 0x4E &&
            (bytes[3].toInt() and 0xFF) == 0x47

        val webp = bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP"

        val gif = bytes.size >= 6 && bytes.copyOfRange(0, 4).decodeToString() == "GIF8"
        return jpeg || png || webp || gif
    }
}
