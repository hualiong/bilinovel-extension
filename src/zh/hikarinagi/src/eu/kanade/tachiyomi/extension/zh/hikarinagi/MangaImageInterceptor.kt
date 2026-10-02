package eu.kanade.tachiyomi.extension.zh.hikarinagi

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import java.io.IOException

/**
 * Serves a manga page. The site no longer hands out image URLs: it wants a POST whose body carries
 * a token the client generated itself, and it answers with `iv || AES-256-GCM(ciphertext)` keyed by
 * that token.
 *
 * The request is built by `Hikarinagi.imageRequest`, so this only has to decrypt what comes back.
 * The token it needs for that rides in the request fragment, where the site never sees it.
 */
class MangaImageInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Only the reader's pages carry a fragment, and it is `<type>|<token>`.
        val fragment = request.url.fragment ?: return chain.proceed(request)
        val mime = fragment.substringBefore('|')
        val token = fragment.substringAfter('|')
        // .../api/v3/reader/mangas/<mid>/chapters/<cid>/pages/<pid>/content
        val (mid, _, cid, _, pid) = request.url.pathSegments.drop(4)

        val response = chain.proceed(request)
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException(if (code == 401) Hikarinagi.LOGIN_MESSAGE else "加载图片失败（HTTP $code）")
        }

        // The answer is decrypted while the reader reads it, so a page is never held whole in memory.
        val length = response.body.contentLength().takeIf { it > 0 }?.minus(ReaderCrypto.OVERHEAD_SIZE) ?: -1L
        val page = ReaderCrypto.decrypting(response.body.source(), token, "manga:page:$mid:$cid:$pid")
            .buffer().asResponseBody(mime.toMediaType(), length)

        return Response.Builder().request(request).ok(page)
    }
}
