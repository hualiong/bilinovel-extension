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
import android.util.Base64
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

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != HOST) {
            // An illustration page Mihon requested by its own URL: frame its response, and remember.
            val parts = url.fragment?.split('/')
            if (parts?.firstOrNull() != FRAME) return chain.proceed(request)
            val plain = request.newBuilder().url(url.newBuilder().fragment(null).build()).build()
            val image = plain.url.toString()
            // A chapter that opens on this picture puts its title in the fragment, so the framed page
            // carries the title without this class keeping anything about the chapter.
            val title = parts.getOrNull(2)?.takeIf(String::isNotEmpty)?.decode()
            val titled = title?.let { Render("", it, "", split = true) }
            val frameName = "frame/${canvasStamp()}/${if (titled == null) "bare" else "titled"}/$image"
            pagesCache[frameName]?.let { return Response.Builder().request(request).ok(it) }
            val response = chain.proceed(plain)
            if (!response.isSuccessful) return response
            val bytes = png(frame(response, titled))
            cache(frameName, bytes)
            return Response.Builder().request(request).ok(bytes)
        }

        val path = url.pathSegments
        // A drawn page carries its own text, so it renders without anything kept from [prepare].
        if (path.getOrNull(0) == PAYLOAD) {
            val page = path.getOrNull(1) ?: throw IOException("页面已失效，请刷新本章节")
            return Response.Builder().request(request).ok(payload(page, url.queryParameter("k")?.decode().orEmpty()))
        }

        // Anything else is a page list from an older version: it carries no text, so it cannot be drawn.
        throw IOException("页面已失效，请刷新本章节")
    }

    /** The canvas colours every drawn page and every framed illustration ride on. */
    private fun canvasStamp() = "${isDark(pref, appDarkTheme(), systemDarkTheme())}/${pref.getString(PREF_SCREEN_STYLE, DEFAULT_SCREEN_STYLE)}"

    /** The page as the reader receives it; the encoded form is all that is kept around afterwards. */
    private fun png(bitmap: Bitmap): ByteArray {
        val data = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, data)
        return data.toByteArray()
    }

    /**
     * Lays the chapter out and returns one URL per page: an illustration keeps its own address, a
     * drawn page carries the text it has to draw. Nothing is remembered, so any page stays
     * renderable after a restart, a stale page list or an evicted image.
     */
    fun prepare(key: String, title: String, text: String): List<String> {
        val render = Render(key, title, text)
        return render.entries.mapIndexed(render::url)
    }

    /**
     * The URL of a page drawn from [text]. A web page goes through this directly: its illustrations
     * cannot be pages of their own, so they are fetched together and baked in when it is drawn.
     */
    fun payloadUrl(key: String, title: String, text: String) = "http://$HOST/$PAYLOAD/${title.encode()}.${text.encode()}?v=$RENDER_VERSION&k=${key.encode()}"

    /** Draws a page from the text its URL carries; [key] only names it for the image cache. */
    private fun payload(page: String, key: String): ByteArray {
        val (title, text) = page.split('.', limit = 2).let { (t, body) -> t.decode() to body.decode() }
        // A URL always carries one whole page, so it is never paginated again: a web page may bake
        // its illustrations in, and a client page holds text only (its illustrations are pages).
        val render = Render(key, title, text, split = false)
        // A page that still needs a second one has nowhere to continue: it would lose its tail.
        if (render.pageCount > 1) throw IOException("本章页面过长，无法生成图片")
        val name = "p/${canvasStamp()}/$page"
        return pagesCache[name] ?: png(render.drawPage(0)).also { cache(name, it) }
    }

    /**
     * Keeps the page the reader is opening in the very form it was sent: the PNG it decodes anyway.
     * A decoded page is four bytes a pixel (9 MB for a full one) while the same page encodes to a
     * few hundred kB, and serving that again needs neither a redraw nor a re-encode.
     */
    private fun cache(name: String, bytes: ByteArray) = synchronized(pagesCache) {
        pagesCache[name] = bytes
        cachedBytes += bytes.size
        while (pagesCache.size > MAX_CACHED_PAGES || cachedBytes > MAX_CACHED_BYTES) {
            val dropped = pagesCache.entries.firstOrNull { it.value !== bytes } ?: break
            cachedBytes -= dropped.value.size
            pagesCache.remove(dropped.key)
        }
    }

    private inner class Render(private val key: String, title: String, text: String, private val split: Boolean = true) {
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

        /** The reading of a ruby: half the body text, bold, centred over the base it belongs to. */
        private val rubyPaint = TextPaint(bodyPaint).apply {
            textSize = bodySize * RUBY_SCALE
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }

        /** The room a reading takes above its line, which only the top of a page has to give. */
        private val rubyHeadroom = bodySize * RUBY_HEADROOM_RATIO + rubyPaint.descent() - rubyPaint.ascent()

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
        private val headingText = title.trim().takeIf(String::isNotEmpty)?.let(::plainText)
        private val heading = headingText?.let(::headingLayout)
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

        /**
         * The illustration the chapter opens with, when a client chapter hands it a page of its own.
         * The title rides that framed image instead of a drawn page, because this renderer never
         * gets to touch page 0 — and the room the heading's divider keeps is the very clearance a
         * drawn page leaves above its body, so the image starts exactly where text would.
         */
        val headingImage: String? = if (split && heading != null) {
            val first = parsed.firstOrNull {
                when (it) {
                    is Block.Image -> it.url.isNotBlank()
                    is Block.Text -> it.text.isNotBlank()
                }
            }
            (first as? Block.Image)?.url
        } else {
            null
        }

        /** The room the title and its divider take above an illustration. */
        val imageHeader get() = if (heading == null) 0 else TOP_MARGIN + headingBlock

        /** The URL of one page: its own address for an illustration, its own text for a drawn page. */
        fun url(index: Int, image: String?): String {
            val title = headingText?.takeIf { index == titlePage }
            return if (image != null) {
                "$image#$FRAME/$RENDER_VERSION" + (title?.let { "/${it.encode()}" } ?: "")
            } else {
                val text = pages[index].orEmpty().joinToString("\n") {
                    when (it) {
                        is Panel.Paragraphs -> it.text
                        is Panel.Figure -> "$IMAGE_MARKER${it.url}$IMAGE_MARKER"
                    }
                }
                payloadUrl(key, title ?: "", text)
            }
        }

        /** The drawn page carrying the title, or none when [headingImage] carries it. */
        private val headingPage: Int

        init {
            val (entries, pages) = paginate()
            this.entries = entries
            this.pages = pages
            this.headingPage = if (headingImage != null) -1 else pages.indexOfFirst { it != null }
        }

        /**
         * Draws the title and its divider at the head of a framed illustration, leaving the image
         * exactly where a drawn page would have started its body.
         */
        fun drawHeading(canvas: Canvas) {
            val layout = heading ?: return
            layout.draw(canvas, X_PADDING, TOP_MARGIN.toFloat())
            val dividerY = (TOP_MARGIN + layout.height + HEADING_GAP).toFloat()
            canvas.drawRect(X_PADDING, dividerY, WIDTH - X_PADDING, dividerY + DIVIDER_HEIGHT, dividerPaint)
        }

        /** The page the title rides on: the first drawn one, or the illustration the chapter opens on. */
        private val titlePage = if (headingImage != null) 0 else headingPage

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
            // The first page flushed below carries the chapter heading, unless the opening
            // illustration does, in which case every drawn page starts out at full height.
            var limit = maxPageHeight - (if (heading != null && headingImage == null) TOP_MARGIN + headingBlock else 0)
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
            val first = index == headingPage
            val topMargin = if (first && heading != null) TOP_MARGIN else 0
            val header = if (first) headingBlock else 0
            val bodyHeight = page.mapIndexed { i, panel ->
                panel.height + if (i == 0) 0 else gapBetween(page[i - 1], panel)
            }.sum()

            // A reading on the page's own first line has no blank above it to sit in.
            val opensWithReading = topMargin + header == 0 &&
                (page.firstOrNull() as? Panel.Paragraphs)?.rubies?.any { it.line == 0 } == true
            val head = if (opensWithReading) rubyHeadroom.toInt() else 0

            // Whatever the page holds, it is one image; the reader scrolls it like any other page.
            val height = (topMargin + header + head + bodyHeight + pageBottomGap).coerceAtLeast(1)
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

            var top = (topMargin + header + head).toFloat()
            page.forEachIndexed { i, panel ->
                if (i > 0) top += gapBetween(page[i - 1], panel)
                draw(canvas, panel, top)
                top += panel.height
            }
            return bitmap
        }

        private fun draw(canvas: Canvas, panel: Panel, top: Float) {
            when (panel) {
                is Panel.Paragraphs -> drawWrapped(canvas, panel, top)

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
         * others are widened to the text width. A line that carries a reading is left as it is,
         * so the reading stays over the very characters it annotates, and every reading is drawn
         * last, above the line it marks.
         */
        private fun drawWrapped(canvas: Canvas, panel: Panel.Paragraphs, top: Float) {
            val layout = panel.layout
            val last = layout.lineCount - 1
            val annotated = panel.rubies.mapTo(HashSet()) { it.line }
            for (line in 0..last) {
                val text = layout.text.subSequence(layout.getLineStart(line), layout.getLineEnd(line))
                    .toString()
                    .trimEnd('\n')
                if (text.isEmpty()) continue

                val baseline = top + panel.baselines[line]
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

                if (line in annotated || line == last || width <= 0 || !justify(canvas, body, baseline, width)) {
                    canvas.drawText(text, X_PADDING, baseline, bodyPaint)
                } else if (mark != null) {
                    canvas.drawText(text, text.length - 1, text.length, markX, baseline, bodyPaint)
                }
            }

            panel.rubies.forEach { ruby ->
                val baseline = top + panel.baselines[ruby.line] + bodyPaint.ascent() -
                    bodySize * RUBY_GAP_RATIO - rubyPaint.descent()
                canvas.drawText(ruby.text, X_PADDING + (ruby.from + ruby.to) / 2f, baseline, rubyPaint)
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
            val (base, readings) = rubyRuns(paragraph)
            val layout = bodyLayout(base)
            val firstBaseline = -bodyPaint.ascent()
            val first = layout.getLineBaseline(0)
            val rubies = readings.mapNotNull { reading ->
                // bodyLayout prepends the paragraph indent, which shifts every offset by one.
                val start = reading.start + 1
                val end = reading.end + 1
                if (start >= end || start >= layout.text.length) return@mapNotNull null
                val line = layout.getLineForOffset(start)
                DrawRuby(
                    reading.text,
                    line,
                    layout.getPrimaryHorizontal(start),
                    layout.getPrimaryHorizontal(end.coerceAtMost(layout.getLineEnd(line).coerceAtLeast(start))),
                )
            }
            // A reading sits in the blank the line above already leaves, so a line keeps its height.
            val baselines = FloatArray(layout.lineCount) { line ->
                firstBaseline + (layout.getLineBaseline(line) - first)
            }
            return Panel.Paragraphs(paragraph, layout, baselines, rubies, (baselines.last() + bodyPaint.descent()).toInt())
        }

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

        private fun plainText(html: String) = Html.fromHtml(html.withoutReadings(), Html.FROM_HTML_MODE_LEGACY).toString()
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
        // A page's illustrations are only useful while that page is being drawn.
        imageCache.keys.removeIf { it != key }
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

    /**
     * Decodes one fetched illustration and frames it with the reading margins. A [titled] chapter
     * opens on this picture, so its title and divider go above it and the image drops by that much.
     */
    private fun frame(response: Response, titled: Render?): Bitmap {
        val source = response.body.byteStream().use(BitmapFactory::decodeStream)
            ?: throw IOException("插图解码失败")
        val bottom = X_PADDING.toInt()
        val header = titled?.imageHeader ?: 0
        val bitmap = scaled(source, MAX_PAGE_HEIGHT - bottom - header)
        val page = Bitmap.createBitmap(WIDTH, header + bitmap.height + bottom, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        val dark = isDark(pref, appDarkTheme(), systemDarkTheme())
        canvas.drawColor(if (dark) Color.BLACK else Color.parseColor(pref.getString(PREF_SCREEN_STYLE, DEFAULT_SCREEN_STYLE)!!.split(' ')[0]))
        titled?.drawHeading(canvas)
        canvas.drawBitmap(bitmap, (WIDTH - bitmap.width) / 2f, header.toFloat(), null)
        return page
    }

    /** The images a chapter keeps between renders, for the strict "load all images" retry. */
    private val imageCache by lazy { ConcurrentHashMap<String, HashMap<String, Bitmap>>() }

    /** The encoded pages the reader is opening, keyed by what they were drawn from. */
    private val pagesCache = ConcurrentHashMap<String, ByteArray>()

    /** How many bytes of them are held on to, so a page can be opened again. */
    private var cachedBytes = 0

    /** The 200 a rendered page is served with. */
    private fun Response.Builder.ok(bytes: ByteArray): Response = code(200)
        .message("OK")
        .protocol(Protocol.HTTP_1_1)
        .body(bytes.toResponseBody(IMAGE_PNG))
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

        /** The path a drawn page's own text travels in. */
        private const val PAYLOAD = "p"

        /** Bump when a drawn pixel changes: it keeps cached page images from outliving the code. */
        private const val RENDER_VERSION = 4

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
         * How many pages, and how many bytes of them, stay ready to be served again. Encoded a page
         * is a few hundred kB instead of the 9 MB it decodes to, so this holds a chapter's worth of
         * close pages; anything older is drawn again from the layout the chapter already keeps.
         */
        private const val MAX_CACHED_PAGES = 32
        private const val MAX_CACHED_BYTES = 8_000_000

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

/**
 * Splits a paragraph into the text the layout takes and the readings drawn above it. The
 * markup is the `<ruby>base<rt>reading</rt></ruby>` the site and the app both write, the
 * `<rp>` fallback included; a paragraph whose annotation is cut off keeps its text and loses
 * the markup, so a broken reading never costs a paragraph.
 */
private fun rubyRuns(paragraph: String): Pair<String, List<Reading>> {
    if (paragraph.indexOf('<') < 0) return paragraph to emptyList()
    val text = StringBuilder()
    val readings = mutableListOf<Reading>()
    var index = 0
    while (index < paragraph.length) {
        val open = paragraph.indexOf("<ruby", index, ignoreCase = true)
        if (open < 0) {
            text.append(paragraph, index, paragraph.length)
            break
        }
        val openEnd = paragraph.indexOf('>', open)
        val close = if (openEnd < 0) -1 else paragraph.indexOf("</ruby", openEnd, ignoreCase = true)
        val closeEnd = if (close < 0) -1 else paragraph.indexOf('>', close)
        if (openEnd < 0 || close < 0 || closeEnd < 0) {
            text.append(plainMarkup(paragraph.substring(open)))
            break
        }
        text.append(paragraph, index, open)
        val inner = paragraph.substring(openEnd + 1, close)
        val rt = rtTag.find(inner)
        val rtEnd = if (rt == null) -1 else inner.indexOf("</rt", rt.range.last + 1, ignoreCase = true)
        val start = text.length
        text.append(plainMarkup(if (rt == null) inner else inner.substring(0, rt.range.first)))
        val reading = if (rt != null && rtEnd >= 0) plainMarkup(inner.substring(rt.range.last + 1, rtEnd)) else ""
        if (reading.isNotEmpty() && text.length > start) readings += Reading(reading, start, text.length)
        index = closeEnd + 1
    }
    return text.toString() to readings
}

/** A base or a reading as it reads without its markup; the rp fallback is markup too. */
private fun plainMarkup(markup: String) = markup.replace(rpBlock, "").replace(anyTag, "").trim()

/** A heading is drawn as one line, so an annotation there only leaves its base text behind. */
private fun String.withoutReadings() = replace(rtBlock, "").replace(rpBlock, "").replace(rubyWrapper, "")

/** A reading the markup asks to draw above [start]..[end] of the paragraph's plain text. */
private class Reading(val text: String, val start: Int, val end: Int)

private val rtTag = Regex("<rt[^>]*>", RegexOption.IGNORE_CASE)
private val rpBlock = Regex("<rp[^>]*>[^<]*</rp[^>]*>", RegexOption.IGNORE_CASE)
private val rtBlock = Regex("<rt[^>]*>[^<]*</rt[^>]*>", RegexOption.IGNORE_CASE)
private val rubyWrapper = Regex("</?ruby[^>]*>", RegexOption.IGNORE_CASE)
private val anyTag = Regex("<[^>]*>")

/** A run of paragraphs or an illustration, with the room it takes on the page. */
private sealed interface Panel {
    val height: Int

    /**
     * A run of paragraphs, each laid out on its own. Every paragraph is given a line and the blank
     * line below it; a paragraph that wraps asks for one more line step per line it adds, and one
     * that carries a reading asks for the room above the line it marks.
     */
    class Paragraphs(
        val text: String,
        val layout: StaticLayout,
        val baselines: FloatArray,
        val rubies: List<DrawRuby>,
        override val height: Int,
    ) : Panel

    class Figure(val url: String, override val height: Int) : Panel
}

/** A reading drawn above [from]..[to] of one line, in page coordinates. */
private class DrawRuby(val text: String, val line: Int, val from: Float, val to: Float)

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

/** The reading of a ruby: half the body size, with the room and the gap the app draws it at. */
private const val RUBY_SCALE = 0.5f
private const val RUBY_HEADROOM_RATIO = 0.12f
private const val RUBY_GAP_RATIO = 0.04f

/** A page carries its text in its URL: URL-safe base64 keeps it one path segment. */
private fun String.encode() = Base64.encodeToString(toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)

private fun String.decode() = String(Base64.decode(this, Base64.URL_SAFE), Charsets.UTF_8)
