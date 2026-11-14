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
 * A chapter is split into pages of at most [MAX_PAGE_HEIGHT], which the page list hands out as its
 * own pages while this interceptor draws each one on demand.
 */
class TextInterceptor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val pref: SharedPreferences,
) : Interceptor {
    /**
     * The chapters laid out and ready to be drawn, oldest dropped first.
     *
     * Per source instance on purpose: the web reader's keys carry a -web- suffix
     * ("article-chapter-web-page") and the client's do not, so the two never name the same
     * render. Sharing these maps would one day let a renamed key collide across sources.
     */
    private val chapters = LinkedHashMap<String, Render>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != HOST) {
            // An illustration page Mihon requested by its own URL: frame its response, and remember.
            if (url.fragment != FRAME) return chain.proceed(request)
            val plain = request.newBuilder().url(url.newBuilder().fragment(null).build()).build()
            val frameName = "frame/${canvasStamp()}/${plain.url}"
            pagesCache[frameName]?.let { return Response.Builder().request(request).ok(png(it)) }
            val response = chain.proceed(plain)
            if (!response.isSuccessful) return response
            val framed = frame(response)
            cache(frameName, framed)
            return Response.Builder().request(request).ok(png(framed))
        }

        val key = url.pathSegments.getOrNull(0).orEmpty()
        val index = url.pathSegments.getOrNull(1)?.toIntOrNull()
            ?: throw IOException("页面已失效，请刷新本章节")
        val render = synchronized(chapters) { chapters[key] }
            ?: throw IOException("页面已失效，请刷新本章节")
        // The render's stamp rides in the name, so a settings change never serves a stale bitmap.
        val name = "$key/$index/${render.stamp}"
        val page = pagesCache[name] ?: render.drawPage(index).also { cache(name, it) }

        return Response.Builder().request(request).ok(png(page))
    }

    /** The canvas colours every drawn page and every framed illustration ride on. */
    private fun canvasStamp() = "${isDark(pref, appDarkTheme(), systemDarkTheme())}/${pref.getString(PREF_SCREEN_STYLE, DEFAULT_SCREEN_STYLE)}"

    private fun png(bitmap: Bitmap): okhttp3.ResponseBody {
        val data = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, data)
        return data.toByteArray().toResponseBody(IMAGE_PNG)
    }

    /**
     * Lays the chapter out and returns one entry per page: null is drawn here when the reader
     * asks for it; a URL is an illustration the reader requests itself, which this interceptor
     * then frames. An unread page, and the illustration on it, costs nothing.
     */
    fun prepare(key: String, title: String, text: String, split: Boolean = true): List<String?> {
        // Chapter images are only useful while their chapter is being read.
        imageCache.keys.removeIf { it != key }
        val render = Render(key, title, text, split)
        synchronized(chapters) {
            chapters[key] = render
            while (chapters.size > MAX_CHAPTERS) {
                chapters.remove(chapters.keys.first())
            }
        }
        return render.entries
    }

    /** The URL the reader asks for; [prepare] must have run for [key] first. */
    fun pageUrl(key: String, index: Int) = "http://$HOST/$key/$index"

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

    private inner class Render(private val key: String, title: String, text: String, private val split: Boolean) {
        private val dark = isDark(pref, appDarkTheme(), systemDarkTheme())
        private val readStyle = pref.getString(PREF_SCREEN_STYLE, DEFAULT_SCREEN_STYLE)!!.split(' ')

        /** Everything that can change pixels, so one stamp covers every stale-page path. */
        val stamp = canvasStamp() +
            "/${pref.getBoolean(PREF_DISPLAY_TRADITIONAL, false)}/${pref.getBoolean(PREF_LOAD_ALL_IMAGES, false)}"

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

        /**
         * The room between two paragraph panels. A paragraph panel already ends at its descent, so
         * the baseline step of the old single-panel renderer shrinks by that much here.
         */
        private val paragraphGap = pageBottomGap

        /** The title, centred above the body and taken from the chapter's own markup. */
        private val heading = title.trim()
            .takeIf(String::isNotEmpty)
            ?.let { headingLayout(plainText(it)) }
        private val headingBlock = heading?.let { it.height + 2 * HEADING_GAP + DIVIDER_HEIGHT } ?: 0

        private val parsed = parse(text)

        // A client chapter hands every illustration to the reader as its own page; only the
        // single drawn web page bakes illustrations into the text.
        private val images = if (split) emptyMap() else loadImages(key, parsed)
        private val panels = parsed.flatMap(::panels)

        /** A web page is drawn as one image, however tall; a client chapter is split into pages. */
        private val maxPageHeight = if (split) MAX_PAGE_HEIGHT else MAX_BITMAP_HEIGHT - 1

        /** null: a page drawn from the body; non-null: an illustration URL the reader requests itself. */
        val entries: List<String?>
        private val pages: List<List<Panel>?>

        init {
            val (entries, pages) = paginate()
            this.entries = entries
            this.pages = pages
        }

        val pageCount get() = entries.size

        /** The blank between two panels: paragraphs keep a paragraph gap, illustrations a line. */
        private fun gapBetween(previous: Panel, next: Panel): Int = if (previous is Panel.Paragraphs && next is Panel.Paragraphs) paragraphGap else lineGap

        /**
         * Splits the body into pages at paragraph boundaries, so one page is one screenful. A
         * client chapter flushes the text before each illustration, which becomes the reader's
         * own page and is only fetched when the reader reaches it.
         */
        private fun paginate(): Pair<List<String?>, List<List<Panel>?>> {
            val entries = mutableListOf<String?>()
            val pages = mutableListOf<List<Panel>?>()
            var current = mutableListOf<Panel>()
            var height = 0
            // The first page also carries the chapter heading above the body.
            var limit = maxPageHeight - (if (heading != null) TOP_MARGIN + headingBlock else 0)
            fun flush() {
                pages += current
                entries += null
                current = mutableListOf()
                height = 0
                limit = maxPageHeight
            }
            fun add(panel: Panel) {
                if (current.isNotEmpty() && height + gapBetween(current.last(), panel) + panel.height > limit) flush()
                if (current.isNotEmpty()) height += gapBetween(current.last(), panel)
                current += panel
                height += panel.height
            }
            if (split) {
                parsed.forEach { block ->
                    when (block) {
                        is Block.Image -> {
                            if (block.url.isBlank()) return@forEach
                            if (current.isNotEmpty()) flush()
                            pages += null
                            entries += block.url
                        }

                        is Block.Text -> block.text.split('\n')
                            .map(String::trim)
                            .filter(String::isNotEmpty)
                            .forEach { add(paragraphPanel(it)) }
                    }
                }
            } else {
                panels.forEach(::add)
            }
            if (current.isNotEmpty() || entries.isEmpty()) flush()
            return entries to pages
        }

        /** Draws one page as tall as its text and illustrations need it to be. */
        fun drawPage(index: Int): Bitmap {
            val page = pages.getOrNull(index) ?: throw IOException("页面已失效，请刷新本章节")
            // A chapter opens with room above its heading; a continued page starts at once.
            val first = index == 0
            val topMargin = if (first && heading != null) TOP_MARGIN else 0
            val header = if (first) headingBlock else 0
            val bodyHeight = page.mapIndexed { i, panel ->
                panel.height + if (i == 0) 0 else gapBetween(page[i - 1], panel)
            }.sum()

            // Whatever the page holds, it is one image; the reader scrolls it like any other page.
            val height = (topMargin + header + bodyHeight + pageBottomGap).coerceAtLeast(1)
            if (height > MAX_BITMAP_HEIGHT) throw IOException("本章页面过长，无法生成图片（$height px）")
            val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(background)

            if (first) {
                heading?.let {
                    it.draw(canvas, X_PADDING, topMargin.toFloat())
                    val dividerY = (topMargin + it.height + HEADING_GAP).toFloat()
                    canvas.drawRect(X_PADDING, dividerY, WIDTH - X_PADDING, dividerY + DIVIDER_HEIGHT, dividerPaint)
                }
            }

            var top = (topMargin + header).toFloat()
            page.forEachIndexed { i, panel ->
                if (i > 0) top += gapBetween(page[i - 1], panel)
                draw(canvas, panel, top)
                top += panel.height
            }
            return bitmap
        }

        private fun draw(canvas: Canvas, panel: Panel, top: Float) {
            when (panel) {
                is Panel.Paragraphs -> drawWrapped(canvas, panel.layout, top + panel.firstBaseline)

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
                var width = CONTENT_WIDTH
                var markX = 0f
                if (mark != null) {
                    // Place a trailing mark by its ink, so it does not leave half a character of paper behind.
                    val ink = Rect()
                    bodyPaint.getTextBounds(text, text.length - 1, text.length, ink)
                    markX = X_PADDING + CONTENT_WIDTH - ink.right
                    width = (markX - X_PADDING).toInt()
                }

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

        /**
         * Turns one parsed block into panels: a paragraph per panel, so a page can break between
         * any two of them instead of holding the whole chapter; an illustration stays one panel.
         */
        private fun panels(source: Block): List<Panel> = when (source) {
            is Block.Text -> source.text.split('\n').map(String::trim).filter(String::isNotEmpty).map(::paragraphPanel)

            is Block.Image -> {
                val bitmap = images[source.url]
                listOf(Panel.Figure(source.url, bitmap?.height ?: (CONTENT_WIDTH * PLACEHOLDER_RATIO).toInt()))
            }
        }

        private fun paragraphPanel(paragraph: String): Panel.Paragraphs {
            val layout = bodyLayout(paragraph)
            val firstBaseline = -bodyPaint.ascent()
            return Panel.Paragraphs(layout, firstBaseline, (firstBaseline + baselineSpan(layout) + bodyPaint.descent()).toInt())
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
        val blocks = mutableListOf<Block>()
        val builder = StringBuilder()
        var index = 0
        while (index < text.length) {
            if (text[index] != IMAGE_MARKER) {
                builder.append(text[index])
                index++
                continue
            }
            val end = text.indexOf(IMAGE_MARKER, index + 1)
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

    /** Loads and scales every illustration of the chapter, once per render. */
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

    private suspend fun loadImage(url: String): Bitmap {
        val raw = if (url.startsWith("//")) "https:$url" else url
        val response = client.newCall(GET(raw, headers)).awaitSuccess()
        val source = response.body.use { it.byteStream().use(BitmapFactory::decodeStream) }
        checkNotNull(source) { throw IOException("位图解码出错") }
        return scaled(source, MAX_PAGE_HEIGHT)
    }

    /** Fits an illustration to the text column and to a height budget, so neither overflows. */
    private fun scaled(source: Bitmap, maxHeight: Int): Bitmap {
        var bitmap = source
        if (bitmap.width != PAGE_WIDTH) {
            val height = (bitmap.height * (PAGE_WIDTH.toDouble() / bitmap.width)).toInt().coerceAtLeast(1)
            val result = Bitmap.createScaledBitmap(bitmap, PAGE_WIDTH, height, true)
            if (result !== bitmap) bitmap.recycle()
            bitmap = result
        }
        if (bitmap.height > maxHeight) {
            val width = (bitmap.width * (maxHeight.toDouble() / bitmap.height)).toInt().coerceAtLeast(1)
            val result = Bitmap.createScaledBitmap(bitmap, width, maxHeight, true)
            if (result !== bitmap) bitmap.recycle()
            bitmap = result
        }
        return bitmap
    }

    /** Decodes one fetched illustration and frames it with the reading margins. */
    private fun frame(response: Response): Bitmap {
        val source = response.body.byteStream().use(BitmapFactory::decodeStream)
            ?: throw IOException("插图解码失败")
        val bottom = X_PADDING.toInt()
        val bitmap = scaled(source, MAX_PAGE_HEIGHT - bottom)
        val page = Bitmap.createBitmap(WIDTH, bitmap.height + bottom, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        val dark = isDark(pref, appDarkTheme(), systemDarkTheme())
        canvas.drawColor(if (dark) Color.BLACK else Color.parseColor(pref.getString(PREF_SCREEN_STYLE, DEFAULT_SCREEN_STYLE)!!.split(' ')[0]))
        canvas.drawBitmap(bitmap, (WIDTH - bitmap.width) / 2f, 0f, null)
        return page
    }

    /** The images a chapter keeps between renders, for the strict "load all images" retry. */
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

        /** Marks an illustration URL whose response this interceptor frames with the page margins. */
        const val FRAME = "bilinovel-frame"

        /** A single ideographic space, the way a Chinese paragraph opens. */
        private const val INDENT = "　"

        /** Space above the heading, and between it, its divider and the body. */
        private const val TOP_MARGIN = 60
        private const val HEADING_GAP = 48
        private const val DIVIDER_HEIGHT = 2

        /** How tall one page may grow before the body continues on the next one. */
        private const val MAX_PAGE_HEIGHT = 2_400

        /**
         * How many chapters stay laid out, so switching back and forth does not redo the work.
         * Linear reading only needs the previous and the current chapter; each laid-out chapter
         * also keeps its scaled illustrations (~4 MB each), so this is a memory high-water mark.
         */
        private const val MAX_CHAPTERS = 2

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
    class Paragraphs(val layout: StaticLayout, val firstBaseline: Float, override val height: Int) : Panel

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
