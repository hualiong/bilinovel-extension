package eu.kanade.tachiyomi.extension.zh.bilinovel

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Html
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.applicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Renders a chapter into page images the way the light novel sources do: the paragraphs are laid
 * out with [StaticLayout] and the illustrations are drawn into the same bitmap, scaled to the text
 * width so both margins stay clear.
 *
 * A chapter taller than [MAX_PAGE_HEIGHT] is kept as several segments, which the page list hands
 * out as its own pages while this interceptor serves the bitmaps.
 */
class TextInterceptor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val pref: SharedPreferences,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != HOST) return chain.proceed(request)

        val key = url.pathSegments.firstOrNull().orEmpty()
        val page = pagesCache[key] ?: throw IOException("页面已失效，请刷新本章节")

        val data = ByteArrayOutputStream()
        page.compress(Bitmap.CompressFormat.PNG, 100, data)

        return Response.Builder()
            .request(request)
            .ok(data.toByteArray().toResponseBody(IMAGE_PNG))
    }

    /**
     * Renders one of the chapter's pages and returns the URL it is served from.
     *
     * The site splits the chapter itself, so a page is only ever rendered when the reader asks
     * for it, and it becomes one image however long it is; the image stays cached for when the
     * reader asks for the page again.
     */
    fun pageUrl(key: String, title: String, text: String): String {
        val bitmap = Render(key, title, text).draw()
        // Both sides of the cache meet on this name: it is the path the reader asks for.
        val name = "${title.hashCode()}-${text.hashCode()}-${System.nanoTime()}"
        cache(name, bitmap)
        return "http://$HOST/$name"
    }

    /**
     * Whether the page behind [url] is still held. A long chapter can push an earlier page out of
     * the cache, and the reader keeps asking for the URL it was given, so the source has to know
     * when that URL no longer answers.
     */
    fun holds(url: String) = pagesCache.containsKey(url.substringAfterLast('/'))

    /**
     * Keeps the page images the reader is opening, dropping another one once the cache is full.
     *
     * A dropped bitmap is only forgotten, never recycled: the reader may still be compressing the
     * very same image on another thread.
     */
    private fun cache(name: String, bitmap: Bitmap) = synchronized(pagesCache) {
        pagesCache[name] = bitmap
        cachedPixels += bitmap.width.toLong() * bitmap.height
        while (pagesCache.size > MAX_CACHED_PAGES || cachedPixels > MAX_CACHED_PIXELS) {
            val dropped = pagesCache.entries.firstOrNull { it.value !== bitmap } ?: break
            cachedPixels -= dropped.value.width.toLong() * dropped.value.height
            pagesCache.remove(dropped.key)
        }
    }

    private inner class Render(private val key: String, private val title: String, private val text: String) {
        private val dark = isDark(pref, appDarkTheme(), systemDarkTheme())
        private val readStyle = pref.getString(PREF_SCREEN_STYLE, DEFAULT_SCREEN_STYLE)!!.split(' ')

        private val background = if (dark) Color.BLACK else Color.parseColor(readStyle[0])
        private val textColor = if (dark) Color.WHITE else Color.parseColor(readStyle[1])
        private val headingPaint = TextPaint().apply {
            color = textColor
            textSize = readStyle.getOrNull(2)?.toFloatOrNull()?.coerceAtLeast(MIN_TEXT_SIZE) ?: DEFAULT_HEADING_SIZE
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        private val bodyPaint = TextPaint().apply {
            color = textColor
            textSize = readStyle.getOrNull(3)?.toFloatOrNull()?.coerceAtLeast(MIN_TEXT_SIZE) ?: DEFAULT_BODY_SIZE
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }
        private val dividerPaint = Paint().apply {
            color = DIVIDER_COLOR
            isAntiAlias = true
        }
        private val placeholderPaint = TextPaint().apply {
            color = if (dark) DARK_PLACEHOLDER_COLOR else LIGHT_PLACEHOLDER_COLOR
            textSize = PLACEHOLDER_TEXT_SIZE
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        private val placeholderBackgroundPaint get() = Paint().apply {
            color = if (dark) DARK_PLACEHOLDER_BACKGROUND else LIGHT_PLACEHOLDER_BACKGROUND
        }

        private val bodySize = bodyPaint.textSize

        /** What one line of text takes, and what a paragraph moves the next one down by. */
        private val lineHeight = ((bodyPaint.descent() - bodyPaint.ascent()) * LINE_SPACING_MULT + LINE_SPACING_EXTRA).toInt()

        /**
         * The blank a line leaves below itself, which is what the reader sees between two lines.
         * An illustration is kept apart by that same room, so it reads as one more line rather
         * than as a new paragraph.
         */
        private val lineGap = (lineHeight - bodySize).toInt().coerceAtLeast(1)

        /** A line is only stretched while the gaps it has to share stay under two characters. */
        private val justifyMaxSlack = JUSTIFY_MAX_SLACK_RATIO * bodySize

        /**
         * The room the foot of the page keeps below its last line, the same one the novel renderer
         * leaves: the page the reader loads next starts on that blank, so the webtoon view joins
         * two pages without a seam.
         */
        private val pageBottomGap = (lineHeight + PARAGRAPH_GAP - (bodyPaint.descent() - bodyPaint.ascent())).toInt().coerceAtLeast(1)

        private val parsed = parse(text)
        private val images = loadImages(key, parsed)
        private val blocks = parsed.map(::block)

        /** Draws the page as tall as its text and illustrations need it to be. */
        fun draw(): Bitmap {
            val heading = title.trim()
                .takeIf(String::isNotEmpty)
                ?.let { headingLayout(plainText(it)) }
            return drawPage(heading)
        }

        private fun drawPage(heading: StaticLayout?): Bitmap {
            // A chapter opens with room above its heading; a page the site continued starts at once.
            val topMargin = if (heading != null) TOP_MARGIN else 0
            val headingBlock = heading?.let { it.height + 2 * HEADING_GAP + DIVIDER_HEIGHT } ?: 0

            // Whatever the page holds, it is one image; the reader scrolls it like any other page.
            val height = (topMargin + headingBlock + bodyHeight() + pageBottomGap).coerceAtLeast(1)
            if (height > MAX_BITMAP_HEIGHT) throw IOException("本章页面过长，无法生成图片（$height px）")
            val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(background)

            heading?.let {
                it.draw(canvas, X_PADDING, topMargin.toFloat())
                val dividerY = (topMargin + it.height + HEADING_GAP).toFloat()
                canvas.drawRect(X_PADDING, dividerY, WIDTH - X_PADDING, dividerY + DIVIDER_HEIGHT, dividerPaint)
            }

            var top = (topMargin + headingBlock).toFloat()
            blocks.forEachIndexed { index, panel ->
                if (index > 0) top += lineGap
                draw(canvas, panel, top)
                top += panel.height
            }
            return bitmap
        }

        /** The room the body takes, its paragraphs and illustrations told apart. */
        private fun bodyHeight(): Int = blocks.sumOf { it.height } +
            if (blocks.isEmpty()) 0 else (blocks.size - 1) * lineGap

        private fun draw(canvas: Canvas, panel: Panel, top: Float) {
            when (panel) {
                is Panel.Paragraphs -> {
                    // Every paragraph takes a line and the blank line below it, so a paragraph of a
                    // single line still moves the next one down a whole line instead of onto itself.
                    var baseline = top + panel.firstBaseline
                    panel.layouts.forEachIndexed { index, layout ->
                        if (index > 0) baseline += lineHeight + PARAGRAPH_GAP
                        drawWrapped(canvas, layout, baseline)
                        baseline += baselineSpan(layout)
                    }
                }

                is Panel.Figure -> {
                    val bitmap = images[panel.url]
                    if (bitmap != null) {
                        // Centre the scaled illustration, so the clearance on both sides is equal.
                        canvas.drawBitmap(bitmap, (WIDTH - bitmap.width) / 2f, top, null)
                    } else {
                        canvas.drawRect(X_PADDING, top, WIDTH - X_PADDING, top + panel.height, placeholderBackgroundPaint)
                        val baseline = top + panel.height / 2f - (placeholderPaint.descent() + placeholderPaint.ascent()) / 2f
                        canvas.drawText(FAILED_LABEL, WIDTH / 2f, baseline, placeholderPaint)
                    }
                }
            }
        }

        /**
         * Draws a [StaticLayout] line by line: the last line of a paragraph stays short, the
         * others are widened to the text width.
         */
        private fun drawWrapped(canvas: Canvas, layout: StaticLayout, firstBaseline: Float) {
            val first = layout.getLineBaseline(0)
            val last = layout.lineCount - 1
            for (line in 0..last) {
                val text = layout.text.subSequence(layout.getLineStart(line), layout.getLineEnd(line))
                    .toString()
                    .trimEnd('\n')
                if (text.isEmpty()) continue

                val baseline = firstBaseline + (layout.getLineBaseline(line) - first)
                val mark = text.takeIf { it.length > 1 }?.last()?.takeIf { it in HANGING_MARKS }
                val body = if (mark == null) text else text.dropLast(1)

                // Place a trailing mark by its ink, so it does not leave half a character of paper behind.
                val ink = Rect()
                bodyPaint.getTextBounds(text, text.length - 1, text.length, ink)
                val markX = X_PADDING + CONTENT_WIDTH - ink.right
                val width = if (mark == null) CONTENT_WIDTH else (markX - X_PADDING).toInt()

                if (line == last || width <= 0 || !justify(canvas, body, baseline, width)) {
                    canvas.drawText(text, X_PADDING, baseline, bodyPaint)
                } else if (mark != null) {
                    canvas.drawText(text, text.length - 1, text.length, markX, baseline, bodyPaint)
                }
            }
        }

        /** Widens the gaps of a line until it meets [width]; false when the line is better left aligned. */
        private fun justify(canvas: Canvas, line: String, baseline: Float, width: Int): Boolean {
            val natural = bodyPaint.measureText(line)
            if (natural <= 0f) return false
            val slack = width - natural
            if (slack < JUSTIFY_MIN_SLACK || slack > justifyMaxSlack) return false

            // Share the slack between all gaps; stretching glyphs or dumping it next to one mark shows.
            bodyPaint.letterSpacing = slack / (line.length * bodySize)
            canvas.drawText(line, X_PADDING, baseline, bodyPaint)
            bodyPaint.letterSpacing = 0f
            return true
        }

        /** Turns a parsed block into the laid out paragraphs or the scaled illustration. */
        private fun block(source: Block): Panel = when (source) {
            is Block.Text -> {
                val layouts = source.text.split('\n')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .map(::bodyLayout)
                val firstBaseline = -bodyPaint.ascent()
                // The panel ends where its last line ends, so an illustration keeps one paragraph
                // gap on either side instead of the room a paragraph would have taken below it.
                var lastBaseline = firstBaseline
                layouts.forEachIndexed { index, layout ->
                    if (index > 0) lastBaseline += lineHeight + PARAGRAPH_GAP
                    lastBaseline += baselineSpan(layout)
                }
                Panel.Paragraphs(layouts, firstBaseline, (lastBaseline + bodyPaint.descent()).toInt())
            }

            is Block.Image -> {
                val bitmap = images[source.url]
                Panel.Figure(source.url, bitmap?.height ?: (CONTENT_WIDTH * PLACEHOLDER_RATIO).toInt())
            }
        }

        /** Distance between the first and the last baseline of a paragraph. */
        private fun baselineSpan(layout: StaticLayout) = (layout.getLineBaseline(layout.lineCount - 1) - layout.getLineBaseline(0)).toFloat()

        /** The title, centred above the body and taken from the chapter's own markup. */
        private fun headingLayout(title: String) = StaticLayout.Builder
            .obtain(title, 0, title.length, headingPaint, CONTENT_WIDTH)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()

        private fun bodyLayout(paragraph: String): StaticLayout {
            // A single ideographic space indents the first line of a paragraph.
            val indented = INDENT + paragraph
            return StaticLayout.Builder.obtain(indented, 0, indented.length, bodyPaint, CONTENT_WIDTH)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(LINE_SPACING_EXTRA, LINE_SPACING_MULT)
                .setIncludePad(false)
                .build()
        }

        private fun plainText(html: String) = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
    }

    /** Splits the rendered text into paragraphs and the illustrations between them. */
    private fun parse(text: String): List<Block> {
        val marker = ChapterContent.IMAGE_MARKER
        val blocks = mutableListOf<Block>()
        val builder = StringBuilder()
        var index = 0
        while (index < text.length) {
            if (text[index] != marker) {
                builder.append(text[index])
                index++
                continue
            }
            val end = text.indexOf(marker, index + 1)
            if (end < 0) {
                builder.append(text, index, text.length)
                break
            }
            appendText(builder, blocks)
            blocks.add(Block.Image(text.substring(index + 1, end)))
            index = end + 1
        }
        appendText(builder, blocks)
        return blocks
    }

    private fun appendText(builder: StringBuilder, blocks: MutableList<Block>) {
        builder.toString().takeIf(String::isNotBlank)?.let { blocks.add(Block.Text(it)) }
        builder.clear()
    }

    /** Loads and scales every illustration of the page, once per render. */
    private fun loadImages(key: String, blocks: List<Block>): Map<String, Bitmap> {
        val urls = blocks.filterIsInstance<Block.Image>().map { it.url }.filter(String::isNotBlank).distinct()
        if (urls.isEmpty()) return emptyMap()

        val strict = pref.getBoolean(PREF_LOAD_ALL_IMAGES, false)
        val cache = if (strict) imageCache.getOrPut(key) { HashMap() } else null
        val loaded = HashMap<String, Bitmap>()
        val pending = urls.filter { url ->
            val cached = cache?.get(url)
            if (cached != null) loaded[url] = cached
            cached == null
        }

        if (pending.isNotEmpty()) {
            val failures = mutableListOf<Throwable>()
            runBlocking(Dispatchers.IO) {
                pending.map { url -> async { runCatching { loadImage(url) } } }
                    .awaitAll()
                    .forEachIndexed { index, result ->
                        result.onSuccess { bitmap ->
                            loaded[pending[index]] = bitmap
                            cache?.put(pending[index], bitmap)
                        }.onFailure(failures::add)
                    }
            }
            if (strict && failures.isNotEmpty()) {
                imageCache.remove(key)
                throw IOException("${failures.size} 张插图加载失败，请重试！\n${failures.first().message}")
            }
        }
        return loaded
    }

    /** Drops the loaded illustrations of a page, so a retry fetches them again. */
    fun clean(key: String) = imageCache.keys.removeIf { it.startsWith(key) }

    private suspend fun loadImage(url: String): Bitmap {
        val raw = if (url.startsWith("//")) "https:$url" else url
        val response = client.newCall(GET(raw, headers)).awaitSuccess()
        val source = response.body.use { it.byteStream().use(BitmapFactory::decodeStream) }
        checkNotNull(source) { throw IOException("位图解码出错") }
        return scaled(source)
    }

    /** Fits the illustration to the text column, so it keeps the same margin on either side. */
    private fun scaled(source: Bitmap): Bitmap {
        if (source.width == PAGE_WIDTH) return source
        val height = (source.height * (PAGE_WIDTH.toDouble() / source.width)).toInt().coerceAtLeast(1)
        val result = Bitmap.createScaledBitmap(source, PAGE_WIDTH, height, true)
        if (result !== source) source.recycle()
        return result
    }

    /** The images a page keeps between renders, for the strict "load all images" retry. */
    private val imageCache by lazy { ConcurrentHashMap<String, HashMap<String, Bitmap>>() }

    /** The page images the reader is opening; a full cache drops one to make room. */
    private val pagesCache = ConcurrentHashMap<String, Bitmap>()

    /** How much of the rendered pages is held on to, so a page can be opened again. */
    private var cachedPixels = 0L

    /** The 200 a rendered page is served with. */
    private fun Response.Builder.ok(body: okhttp3.ResponseBody): Response = code(200)
        .message("OK")
        .protocol(Protocol.HTTP_1_1)
        .body(body)
        .build()

    /** Draws a layout at an offset, from the top of its block rather than its first baseline. */
    private fun StaticLayout.draw(canvas: Canvas, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        draw(canvas)
        canvas.restore()
    }

    // Set through AppCompatDelegate; its getter is shrunk away in release builds, so read the field.
    private fun appDarkTheme(): Boolean? = runCatching {
        val field = Class.forName("androidx.appcompat.app.AppCompatDelegate")
            .getDeclaredField("sDefaultNightMode")
            .apply { isAccessible = true }
        when (field.getInt(null)) {
            APP_NIGHT_NO -> false
            APP_NIGHT_YES -> true
            else -> null
        }
    }.getOrNull()

    /** The system's night mode, the fallback when the reader's own theme cannot be read. */
    private fun systemDarkTheme(): Boolean {
        val uiMode = applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        private const val HOST = "bilinovel-interceptor"

        /** A single ideographic space, the way a Chinese paragraph opens. */
        private const val INDENT = "　"

        /** Space above the heading, and between it, its divider and the body. */
        private const val TOP_MARGIN = 60
        private const val HEADING_GAP = 48
        private const val DIVIDER_HEIGHT = 2

        /** How many rendered pages, and how many pixels of them, stay ready to be served again. */
        private const val MAX_CACHED_PAGES = 12
        private const val MAX_CACHED_PIXELS = 32_000_000L

        /**
         * A page is drawn as tall as its content, up to what a bitmap can hold. Android refuses
         * anything taller than 32767 px, so that is where the renderer gives up with a message.
         */
        private const val MAX_BITMAP_HEIGHT = 32_767

        private const val PLACEHOLDER_TEXT_SIZE = 34f
        private const val PLACEHOLDER_RATIO = 9f / 16f
        private const val FAILED_LABEL = "插图加载失败"

        private const val DEFAULT_HEADING_SIZE = 56f
        private const val DEFAULT_BODY_SIZE = 40f
        private const val MIN_TEXT_SIZE = 12f

        private const val LIGHT_PLACEHOLDER_BACKGROUND = 0xFFEEEEEE.toInt()
        private const val LIGHT_PLACEHOLDER_COLOR = 0xFF999999.toInt()
        private const val DARK_PLACEHOLDER_BACKGROUND = 0xFF2A2A2A.toInt()
        private const val DARK_PLACEHOLDER_COLOR = 0xFF808080.toInt()

        /** A middle grey reads on white paper and on a black screen alike. */
        private const val DIVIDER_COLOR = 0xFF9E9E9E.toInt()

        private const val JUSTIFY_MIN_SLACK = 2f

        /** A line may be stretched by up to two of its own characters before it is left alone. */
        private const val JUSTIFY_MAX_SLACK_RATIO = 2f

        /** Marks whose ink sits left of centre, so their ink (not their box) can end a line. */
        private const val HANGING_MARKS = "，。、；：！？,.;:!?）〕］｝〉》」』】〗”’"

        private val IMAGE_PNG = "image/png".toMediaType()

        // AppCompatDelegate.MODE_NIGHT_NO / MODE_NIGHT_YES, what the reader gives it for LIGHT / DARK.
        private const val APP_NIGHT_NO = 1
        private const val APP_NIGHT_YES = 2
    }
}

/** A run of paragraphs or an illustration, with the room it takes on the page. */
private sealed interface Panel {
    val height: Int

    /**
     * A run of paragraphs, each laid out on its own. Every paragraph is given a line and the blank
     * line below it; a paragraph that wraps asks for one more line step per line it adds.
     */
    class Paragraphs(val layouts: List<StaticLayout>, val firstBaseline: Float, override val height: Int) : Panel

    class Figure(val url: String, override val height: Int) : Panel
}

/** The width every page image is rendered at, and the clearance the reader gets on both sides. */
private const val WIDTH = 1000
private const val X_PADDING = 60f

/** The text column, and the room an illustration is scaled into: the very same margins. */
private const val CONTENT_WIDTH = (WIDTH - 2 * X_PADDING).toInt()
private const val PAGE_WIDTH = CONTENT_WIDTH

private const val LINE_SPACING_MULT = 1.5f
private const val LINE_SPACING_EXTRA = 6f

/** Extra space between two lines of the same paragraph, and between two paragraphs. */
private const val PARAGRAPH_GAP = 25
