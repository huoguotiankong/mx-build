package eu.kanade.tachiyomi.extension.zh.miaoqumanhuaplus

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

object MiaoquImageInterceptor : Interceptor {
    private val webp = byteArrayOf('W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte())
    private val riff = byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte())

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!request.url.encodedPath.contains("/tu/") || !request.url.encodedPath.endsWith(".jpeg")) return response
        if (!response.isSuccessful) return response

        val body = response.body
        val bytes = body.bytes()
        val fixed = repairWebp(bytes)
        if (fixed === bytes) {
            return response.newBuilder()
                .body(bytes.toResponseBody(body.contentType()))
                .build()
        }

        return response.newBuilder()
            .header("Content-Type", "image/webp")
            .body(fixed.toResponseBody("image/webp".toMediaTypeOrNull()))
            .build()
    }

    private fun repairWebp(data: ByteArray): ByteArray {
        if (data.size < 12) return data
        val hasWebpMarker = (8..11).all { index -> data[index] == webp[index - 8] }
        val hasRiffHeader = (0..3).all { index -> data[index] == riff[index] }
        if (!hasWebpMarker || hasRiffHeader) return data

        return data.copyOf().apply {
            for (i in riff.indices) this[i] = riff[i]
            val riffSize = size - 8
            this[4] = (riffSize and 0xff).toByte()
            this[5] = (riffSize ushr 8 and 0xff).toByte()
            this[6] = (riffSize ushr 16 and 0xff).toByte()
            this[7] = (riffSize ushr 24 and 0xff).toByte()
        }
    }
}
