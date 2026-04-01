package eu.kanade.tachiyomi.extension.zh.bilinovel

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.Html
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.HashMap
import java.util.concurrent.TimeUnit

class TextInterceptor(
    private val baseUrl: String,
    private val pref: SharedPreferences,
) : Interceptor {

    companion object {
        private const val HOST = "bilinovel-interceptor"
        private const val WIDTH: Int = 1000
        private const val X_PADDING: Float = 50f
        private const val Y_PADDING: Float = 30f
        private const val SPACING_MULT: Float = 1.0f
        private const val SPACING_ADD: Float = 10f
        private const val DIVIDER_HEIGHT: Float = 2f
        private const val DIVIDER_MARGIN: Float = 30f
        val URL_REGEX = Regex("""<img[^>]+src\s*=\s*["']([^"']+)["'][^>]*>""")
        val DIVIDER_COLOR = Color.parseColor("#E0E0E0")
        fun createUrl(title: String, text: String): String {
            return "http://$HOST/" + Uri.encode(title) + "/" + Uri.encode(text)
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != HOST) return chain.proceed(request)

        val darkMode = pref.getBoolean(PREF_DARK_MODE, false)

        val screenColor = if (darkMode) {
            Color.BLACK to Color.WHITE
        } else {
            pref.getString(PREF_SCREEN_COLORS, "#FAFAF8 #000000")!!.split(' ').let {
                Color.parseColor(it[0]) to Color.parseColor(it[1])
            }
        }
        val screenFontSize = pref.getString(PREF_SCREEN_FONT_SIZE, "52 30")!!.split(' ').let {
            it[0].toFloat() to it[1].toFloat()
        }

        val paintHeading = TextPaint().apply {
            color = screenColor.second
            textSize = screenFontSize.first
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val paintBody = TextPaint().apply {
            color = screenColor.second
            textSize = screenFontSize.second
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        val heading = url.pathSegments[0].takeIf { it.isNotEmpty() }?.let {
            val title = Html.fromHtml(url.pathSegments[0], Html.FROM_HTML_MODE_LEGACY).toString()
            StaticLayout.Builder.obtain(title, 0, title.length, paintHeading, (WIDTH - 2 * X_PADDING).toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(SPACING_ADD, SPACING_MULT) // 注意参数顺序：add, mult
                .setIncludePad(false)
                .build()
        }

        val body = url.pathSegments[1].takeIf { it.isNotEmpty() }?.let {
            // 处理HTML内容并预加载所有图片
            val imageUrls = extractImageUrls(it)
            val imageBuffer = HashMap<String, Drawable>()

            if (imageUrls.isNotEmpty()) {
                // 使用协程并发加载图片，并等待完成（最多30秒）
                runBlocking(Dispatchers.IO) {
                    val deferredImages = imageUrls.map { url ->
                        async {
                            runCatching {
                                // 加载图片，失败时返回占位符
                                loadImage(url) ?: createPlaceholder()
                            }.getOrElse { e ->
                                Log.w("TextInterceptor", "Failed to load image: $url", e)
                                createPlaceholder()
                            }
                        }
                    }

                    // 等待所有图片加载完成，设置超时30秒
                    withTimeoutOrNull(TimeUnit.SECONDS.toMillis(30)) {
                        deferredImages.awaitAll().forEachIndexed { index, drawable ->
                            imageBuffer[imageUrls[index]] = drawable
                        }
                    } ?: run {
                        // 超时处理：已加载的图片保留，未加载的用占位符
                        Log.w("TextInterceptor", "Timeout waiting for images to load")
                        deferredImages.forEachIndexed { index, deferred ->
                            if (!deferred.isCompleted) {
                                deferred.cancel()
                                imageBuffer[imageUrls[index]] = createPlaceholder()
                            }
                        }
                    }
                }
            }

            val spanned = Html.fromHtml(
                it,
                Html.FROM_HTML_MODE_LEGACY,
                { src -> imageBuffer.getOrDefault(src, createPlaceholder()) },
                null,
            )

            StaticLayout.Builder.obtain(spanned, 0, spanned.length, paintBody, (WIDTH - 2 * X_PADDING).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(SPACING_ADD, SPACING_MULT)
                .setIncludePad(false)
                .build()
        }

        // Image building
        val headingHeight =
            heading?.height?.plus(Y_PADDING * 2 + DIVIDER_HEIGHT + DIVIDER_MARGIN * 2) ?: 0f
        val bodyHeight = body?.height ?: 0
        val imgHeight = (headingHeight + bodyHeight).toInt()
        val bitmap = Bitmap.createBitmap(WIDTH, imgHeight, Bitmap.Config.ARGB_8888)

        Canvas(bitmap).apply {
            drawColor(screenColor.first)
            heading?.let {
                it.draw(this, X_PADDING, Y_PADDING * 2)
                // 绘制标题下方的分割线
                val dividerY = heading.height + Y_PADDING * 2 + DIVIDER_MARGIN
                val paint = Paint().apply {
                    color = DIVIDER_COLOR
                    strokeWidth = DIVIDER_HEIGHT
                }
                drawLine(X_PADDING, dividerY, WIDTH - X_PADDING, dividerY, paint)
            }
            // 调整正文位置，考虑分割线的高度和间距
            body?.draw(this, X_PADDING, headingHeight)
        }

        // Image converting & returning
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val responseBody = stream.toByteArray().toResponseBody("image/png".toMediaType())
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(responseBody)
            .build()
    }

    /**
     * 从HTML中提取所有图片URL
     */
    private fun extractImageUrls(html: String): List<String> {
        val matches = URL_REGEX.findAll(html)
        return matches.map { it.groupValues[1] }.filter { it.isNotBlank() }.toMutableList()
    }

    /**
     * 加载单个图片（可空返回，用于协程异常处理）
     */
    private fun loadImage(url: String): Drawable? {
        val raw = if (url.startsWith("//")) URL("https:$url") else URL(url)
        val connection = raw.openConnection().apply {
            setRequestProperty("Referer", baseUrl)
            connectTimeout = 10000
            readTimeout = 10000
        }
        return runCatching {
            val bitmap = connection.getInputStream().use(BitmapFactory::decodeStream)

            // 计算适合文本宽度的图片尺寸
            val scaledWidth = WIDTH - (2 * X_PADDING).toInt()
            val scaleFactor = scaledWidth.toFloat() / bitmap.width
            val scaledHeight = (bitmap.height * scaleFactor).toInt()

            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
            BitmapDrawable(null, scaledBitmap).apply {
                setBounds(0, 0, scaledWidth, scaledHeight)
            }
        }.getOrNull()
    }

    /**
     * 创建占位符图片
     */
    private fun createPlaceholder(): Drawable {
        val width = WIDTH - (2 * X_PADDING).toInt()
        val height = (width * 0.5625).toInt() // 16:9比例

        val placeholder = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(placeholder)

        // 绘制背景色
        canvas.drawColor(Color.parseColor("#EEEEEE"))

        // 创建文字画笔
        val textPaint = TextPaint().apply {
            color = Color.parseColor("#999999")
            textSize = 36f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        // 计算文字位置（水平垂直居中）
        val text = "插图加载失败"
        val x = width / 2f
        val y = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f

        // 绘制文字
        canvas.drawText(text, x, y, textPaint)

        // 可选：添加边框
        // val borderPaint = Paint().apply {
        //     color = Color.parseColor("#CCCCCC")
        //     style = Paint.Style.STROKE
        //     strokeWidth = 2f
        //     isAntiAlias = true
        // }
        // canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)

        return BitmapDrawable(null, placeholder).apply {
            setBounds(0, 0, width, height)
        }
    }

    private fun StaticLayout.draw(canvas: Canvas, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        this.draw(canvas)
        canvas.restore()
    }
}
