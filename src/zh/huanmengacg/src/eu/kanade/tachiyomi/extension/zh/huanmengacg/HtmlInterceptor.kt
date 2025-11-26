package eu.kanade.tachiyomi.extension.zh.huanmengacg

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
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.IOException
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Designer string values:
private const val WIDTH: Int = 1000
private const val X_PADDING: Float = 50f
private const val Y_PADDING: Float = 30f
private const val SPACING_MULT: Float = 1.0f
private const val SPACING_ADD: Float = 10f
private const val DIVIDER_HEIGHT: Float = 2f
private const val DIVIDER_MARGIN: Float = 30f

class HtmlInterceptor(
    private val baseUrl: String,
    private val pref: SharedPreferences,
) : Interceptor {
    private val executor = Executors.newFixedThreadPool(4)

    companion object {
        val URL_REGEX = Regex("""<img[^>]+src\s*=\s*["']([^"']+)["'][^>]*>""")
        val DIVIDER_COLOR = Color.parseColor("#E0E0E0")
    }

    @Suppress("DEPRECATION")
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != HtmlInterceptorHelper.HOST) return chain.proceed(request)

        val darkMode = pref.getBoolean(PREF_DARK_MODE, false)
        val bgColor = if (darkMode) {
            Color.BLACK
        } else {
            Color.parseColor(
                pref.getString(PREF_SCREEN_BG_COLOR, "#FAFAF8"),
            )
        }
        val fontColor = if (darkMode) {
            Color.WHITE
        } else {
            Color.parseColor(
                pref.getString(PREF_SCREEN_FONT_COLOR, "#000000"),
            )
        }

        val paintHeading = TextPaint().apply {
            color = fontColor
            textSize = pref.getString(PREF_HEADING_FONT_SIZE, "52.0")!!.toFloat()
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val paintBody = TextPaint().apply {
            color = fontColor
            textSize = pref.getString(PREF_BODY_FONT_SIZE, "30.0")!!.toFloat()
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        val heading = url.pathSegments[0].takeIf { it.isNotEmpty() }?.let {
            val title = Html.fromHtml(url.pathSegments[0], Html.FROM_HTML_MODE_LEGACY).toString()
            StaticLayout(
                title,
                paintHeading,
                (WIDTH - 2 * X_PADDING).toInt(),
                Layout.Alignment.ALIGN_CENTER,
                SPACING_MULT,
                SPACING_ADD,
                false,
            )
        }

        val body = url.pathSegments[1].takeIf { it.isNotEmpty() }?.let {
            // 处理HTML内容并预加载所有图片
            val imageUrls = extractImageUrls(it)
            val imageBuffer = ConcurrentHashMap<String, Drawable>()
            if (imageUrls.isNotEmpty()) {
                Log.v("FantasyNovel", "识别到${imageUrls.size}张图片")
                // 等待所有图片加载完成
                val latch = CountDownLatch(imageUrls.size)
                imageUrls.forEach { url ->
                    executor.execute {
                        try {
                            imageBuffer[url] = loadImage(url)
                        } finally {
                            latch.countDown()
                        }
                    }
                }

                // 设置超时时间，避免无限等待
                if (!latch.await(30L.coerceAtLeast(imageUrls.size * 10L), TimeUnit.SECONDS)) {
                    Log.w("TextInterceptor", "Timeout waiting for images to load")
                }
            }
            val spanned = Html.fromHtml(
                it,
                Html.FROM_HTML_MODE_LEGACY,
                { src -> imageBuffer.getOrDefault(src, createPlaceholder()) },
                null,
            )

            StaticLayout(
                spanned,
                paintBody,
                (WIDTH - 2 * X_PADDING).toInt(),
                Layout.Alignment.ALIGN_NORMAL,
                SPACING_MULT,
                SPACING_ADD,
                false,
            )
        }

        // Image building
        val headingHeight =
            heading?.height?.plus(Y_PADDING * 2 + DIVIDER_HEIGHT + DIVIDER_MARGIN * 2) ?: 0f
        val bodyHeight = body?.height ?: 0
        val imgHeight = (headingHeight + bodyHeight).toInt()
        val bitmap = Bitmap.createBitmap(WIDTH, imgHeight, Bitmap.Config.ARGB_8888)

        Canvas(bitmap).apply {
            drawColor(bgColor)
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
        return URL_REGEX.findAll(html).map { it.groupValues[1] }.toMutableList()
    }

    /**
     * 加载单个图片
     */
    private fun loadImage(url: String): Drawable {
        val connection = URL(url).openConnection().apply {
            setRequestProperty("Referer", baseUrl)
            connectTimeout = 20000
            readTimeout = 20000
        }
        try {
            val inputStream = connection.getInputStream()
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            // 计算适合文本宽度的图片尺寸
            val scaledWidth = WIDTH - (2 * X_PADDING).toInt()
            val scaleFactor = scaledWidth.toFloat() / bitmap.width
            val scaledHeight = (bitmap.height * scaleFactor).toInt()

            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
            return BitmapDrawable(null, scaledBitmap).apply {
                setBounds(0, 0, scaledWidth, scaledHeight)
            }
        } catch (_: IOException) {
            return createPlaceholder()
        }
    }

    /**
     * 创建占位符图片
     */
    private fun createPlaceholder(msg: String = "插图加载失败"): Drawable {
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
        val text = msg
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

object HtmlInterceptorHelper {
    const val HOST = "bilinovel-htmlinterceptor"

    fun createUrl(title: String, text: String): String {
        return "http://$HOST/" + Uri.encode(title) + "/" + Uri.encode(text)
    }
}
