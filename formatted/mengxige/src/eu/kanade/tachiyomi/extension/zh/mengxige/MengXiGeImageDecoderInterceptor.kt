package eu.kanade.tachiyomi.extension.zh.mengxige

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.buffer

private const val SCRAMBLE_PREFIX_SIZE = 400
private const val MENGXIGE_APP_PACKAGE = "com.mengxigeyd.novel"

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
        val response = chain.proceed(request)

        if (!response.isSuccessful || !isPossibleScrambledImage(request.url.host, request.url.encodedPath)) {
            return response
        }

        val body = response.body
        val upstream = body.source()
        upstream.request(SCRAMBLE_PREFIX_SIZE.toLong())
        val prefixSize = minOf(SCRAMBLE_PREFIX_SIZE.toLong(), upstream.buffer.size).toInt()
        if (prefixSize == 0) return response

        val prefix = upstream.buffer.clone().readByteArray(prefixSize.toLong())
        if (isPlainImage(prefix)) return response

        upstream.skip(prefixSize.toLong())
        val decodedPrefix = Buffer()
        var index = 0
        while (index < prefixSize) {
            decodedPrefix.writeByte(prefix[index].toInt())
            index += 2
        }

        val decodedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (byteCount == 0L) return 0L
                if (decodedPrefix.size > 0L) {
                    return decodedPrefix.read(sink, minOf(byteCount, decodedPrefix.size))
                }
                return upstream.read(sink, byteCount)
            }

            override fun timeout() = upstream.timeout()

            override fun close() = upstream.close()
        }

        val mediaType = body.contentType()
        val originalLength = body.contentLength()
        val decodedLength = if (originalLength >= prefixSize) {
            originalLength - prefixSize + (prefixSize + 1) / 2
        } else {
            -1L
        }

        val decodedBody = object : ResponseBody() {
            private val bufferedSource: BufferedSource = decodedSource.buffer()

            override fun contentType() = mediaType

            override fun contentLength() = decodedLength

            override fun source() = bufferedSource
        }

        return response.newBuilder()
            .body(decodedBody)
            .build()
    }

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
