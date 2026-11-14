package eu.kanade.tachiyomi.extension.zh.hikarinagi

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import keiyoushi.utils.applicationContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Serves the illustrations extracted from a volume EPUB, addressed by content.
 *
 * An illustration keeps its own page, but it is drawn on the canvas the text pages use: centred in
 * the text column, on the page background, with the reading margins at both sides and at the foot.
 * That keeps it off the screen edge and keeps the next page from running into it. An illustration
 * that opens a chapter also gets the top margin the chapter's first page gives its heading.
 */
class NovelImageInterceptor(private val preferences: SharedPreferences) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != HOST) return chain.proceed(request)

        val file = File(cacheDir, url.pathSegments.last())
        if (!file.exists()) throw IOException("插图已失效，请刷新本章节")

        val page = frame(file, url.queryParameter("top") == "1")
        val stream = ByteArrayOutputStream()
        page.compress(Bitmap.CompressFormat.PNG, 100, stream)
        page.recycle()

        return Response.Builder().request(request).ok(stream.toByteArray().toResponseBody(IMAGE_PNG))
    }

    /** Draws one illustration on the page canvas: the same width, margins and background as the text. */
    private fun frame(file: File, topMargin: Boolean): Bitmap {
        val source = BitmapFactory.decodeFile(file.path) ?: throw IOException("插图解码失败")
        // The same foot blank a text page keeps, so the spacing above and below an illustration matches.
        val bottom = NovelTextInterceptor.PAGE_FOOT_BLANK
        // Only an illustration that opens a chapter needs room above it; otherwise the previous
        // page's own foot blank already separates them.
        val top = if (topMargin) NovelTextInterceptor.TOP_MARGIN else 0

        // Fit the drawing to the text column, then to what one bitmap page may hold.
        var bitmap = scaled(source, NovelTextInterceptor.CONTENT_WIDTH)
        if (bitmap.height > MAX_CONTENT_HEIGHT) {
            val width = (bitmap.width * (MAX_CONTENT_HEIGHT.toDouble() / bitmap.height)).toInt().coerceAtLeast(1)
            bitmap = Bitmap.createScaledBitmap(bitmap, width, MAX_CONTENT_HEIGHT, true)
                .also { if (it !== bitmap) bitmap.recycle() }
        }

        val page = Bitmap.createBitmap(NovelTextInterceptor.WIDTH, top + bitmap.height + bottom, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(NovelTextInterceptor.background(preferences))
        // Centre it, so the clearance on both sides is equal.
        canvas.drawBitmap(bitmap, (NovelTextInterceptor.WIDTH - bitmap.width) / 2f, top.toFloat(), null)
        bitmap.recycle()
        return page
    }

    /** Scales [source] to [width] wide, recycling the original once it is no longer needed. */
    private fun scaled(source: Bitmap, width: Int): Bitmap {
        if (source.width == width) return source
        val height = (source.height * (width.toDouble() / source.width)).toInt().coerceAtLeast(1)
        val result = Bitmap.createScaledBitmap(source, width, height, true)
        if (result !== source) source.recycle()
        return result
    }

    companion object {
        private const val HOST = "hikarinagi-novel-image"

        /** A taller illustration is shrunk, so one page still fits a bitmap and a reader's memory. */
        private const val MAX_CONTENT_HEIGHT = 4_000

        private val IMAGE_PNG = "image/png".toMediaType()

        private val cacheDir: File get() = applicationContext.cacheDir.resolve("novel-images")

        /** Stores an illustration and returns the URL it is served from. */
        fun save(bytes: ByteArray, extension: String, topMargin: Boolean = false): String {
            val name = "${bytes.toByteString().sha1().hex().take(24)}.${extension.lowercase()}"
            val file = File(cacheDir, name)
            if (file.length() != bytes.size.toLong()) {
                cacheDir.mkdirs()
                file.writeBytes(bytes)
            }
            return "http://$HOST/$name" + if (topMargin) "?top=1" else ""
        }
    }
}

/** The 200 the local page interceptors answer with. */
internal fun Response.Builder.ok(body: ResponseBody): Response = code(200).message("OK").protocol(Protocol.HTTP_2).body(body).build()
