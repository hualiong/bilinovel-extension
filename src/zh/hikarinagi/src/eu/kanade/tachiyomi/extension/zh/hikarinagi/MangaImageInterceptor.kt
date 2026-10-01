package eu.kanade.tachiyomi.extension.zh.hikarinagi

import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/** Serves a manga page: the site only hands them out through an encrypted POST. */
class MangaImageInterceptor(
    private val baseUrl: String,
    private val headers: Headers,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != HOST) return chain.proceed(request)

        val mangaId = url.pathSegments.getOrNull(0) ?: throw IOException(BROKEN_URL)
        val chapterId = url.pathSegments.getOrNull(1) ?: throw IOException(BROKEN_URL)
        val pageId = url.pathSegments.getOrNull(2) ?: throw IOException(BROKEN_URL)

        val token = ReaderCrypto.newToken()
        val contentRequest = request.newBuilder()
            .url("$baseUrl/api/v3/reader/mangas/$mangaId/chapters/$chapterId/pages/$pageId/content")
            .headers(headers)
            .post(buildJsonObject { put("p", token) }.toJsonRequestBody())
            .build()

        val response = chain.proceed(contentRequest)
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException(if (code == 401) Hikarinagi.LOGIN_MESSAGE else "加载图片失败（HTTP $code）")
        }

        val page = response.use { ReaderCrypto.decrypt(token, it.body.bytes(), "manga:page:$mangaId:$chapterId:$pageId") }
        return Response.Builder().request(request).ok(page.toResponseBody((url.queryParameter("mime") ?: "image/jpeg").toMediaType()))
    }

    companion object {
        private const val HOST = "hikarinagi-manga-image"

        private const val BROKEN_URL = "图片地址无效"

        /** Where the reader loads a page from; [mimeType] only labels the decrypted bytes. */
        fun createUrl(mangaId: String, chapterId: String, pageId: String, mimeType: String?): String = "http://$HOST/$mangaId/$chapterId/$pageId" + (mimeType?.let { "?mime=$it" } ?: "")
    }
}
