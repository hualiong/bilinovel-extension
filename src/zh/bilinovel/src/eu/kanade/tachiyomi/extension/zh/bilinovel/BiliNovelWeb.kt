package eu.kanade.tachiyomi.extension.zh.bilinovel

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.get
import keiyoushi.utils.selectInt
import keiyoushi.utils.selectText
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** The site itself: details, catalog, chapter pages and search, as the extension did before the app API. */
internal class BiliNovelWeb(private val source: BiliNovel) {

    private val client get() = source.client
    private val headers get() = source.headers
    private val pref get() = source.pref
    private val textInterceptor get() = source.textInterceptor
    private val baseUrl get() = source.baseUrl

    /** The reading order seed does not change while the extension lives; keep it out of every page request. */
    private var salt: Pair<Int, Int>? = null

    fun filters(): FilterList = buildWebFilterList()

    suspend fun popular(page: Int): MangasPage {
        val suffix = pref.getString(PREF_POPULAR_DISPLAY, "/top/weekvisit/%d.html")!!
        return mangaPageParse(client.get(baseUrl + String.format(suffix, page), headers))
    }

    suspend fun latest(page: Int): MangasPage = mangaPageParse(client.get("$baseUrl/top/lastupdate/$page.html", headers))

    suspend fun search(page: Int, query: String, filters: FilterList): MangasPage {
        val request = NOVEL_ID_REGEX.find(query)?.value?.let { GET(baseUrl + it, headers) } ?: run {
            val url = baseUrl.toHttpUrl().newBuilder()
            if (query.isNotBlank()) {
                url.addPathSegment("search").addPathSegment("${query}_$page.html")
            } else {
                url.addPathSegment("wenku")
                    .addPathSegment("${filters[3]}_${filters[2]}_${filters[6]}_${filters[4]}_${filters[1]}_0_0_${filters[5]}_${page}_0.html")
            }
            GET(url.build(), headers)
        }
        val response = client.newCall(request).awaitSuccess()
        if (response.request.url.pathSegments.contains("novel")) {
            return MangasPage(listOf(response.parseManga()), false)
        }
        if (query.isNotBlank() && response.hasCaptcha()) {
            throw Exception("请先在 WebView 中点击输入框进行搜索，再手动通过验证")
        }
        return mangaPageParse(response)
    }

    suspend fun update(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = supervisorScope {
        val articleId = manga.novelId
        val asyncManga = if (fetchDetails) {
            async { client.get(baseUrl + manga.url, headers).parseManga() }
        } else {
            CompletableDeferred(manga)
        }
        val asyncChapters = if (fetchChapters) {
            async {
                val doc = client.get("$baseUrl/novel/$articleId/catalog", headers).asJsoup()
                val switch = pref.getBoolean(PREF_DISPLAY_TRADITIONAL, false)
                val info = doc.selectText(".chapter-sub-title")!!
                val title = doc.selectText(".book-title")!!
                val date = DATE_FORMAT.tryParse(DATE_REGEX.find(info)?.value)
                doc.select(".catalog-volume").takeIf(Elements::isNotEmpty)?.flatMap {
                    val bar = it.selectText(".chapter-bar")!!.substring(title.length + 1)
                    val volume = if (bar.first().isDigit()) "Vol.$bar" else bar.toHalfWidthDigits()
                    it.select(".chapter-li-a").mapToChapter(switch, date, volume)
                }?.reversed() ?: doc.select(".chapter-li-a").mapToChapter(switch, date).reversed()
            }
        } else {
            CompletableDeferred(chapters)
        }
        SMangaUpdate(asyncManga.await(), asyncChapters.await())
    }

    suspend fun pages(chapter: SChapter): List<Page> = sourcePages(chapter.url).mapIndexed { index, url -> Page(index, url) }

    suspend fun image(page: Page): String {
        val ids = page.ids
        val key = "${ids[0]}-${ids[1]}-web-${page.index}"
        if (!page.url.contains('_') && pref.getBoolean(PREF_AUTO_BOOKMARK, false)) {
            val apiUrl = BOOKMARK_URL.format(baseUrl, ids[0], ids[1])
            CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO) {
                try {
                    val response = client.get(apiUrl, headers)
                    if (response.body.string().startsWith("对不起")) {
                        pref.edit().putBoolean(PREF_AUTO_BOOKMARK, false).apply()
                    }
                } catch (e: Exception) {
                    pref.edit().putBoolean(PREF_AUTO_BOOKMARK, false).apply()
                }
            }
        }
        val doc = client.get(page.url, headers).asJsoup()
        doc.selectFirst("#acontent > .center-note")?.run { throw Exception(text()) }
        val content = doc.selectFirst("#acontent") ?: throw Exception("页面已失效，请刷新本章节")
        val switch = pref.getBoolean(PREF_DISPLAY_TRADITIONAL, false)
        val text = collectText(content, ids[1]!!.toInt(), ensureSalt(doc)).convert(switch)
        val pages = textInterceptor.prepare(key, chapterTitle(doc).convert(switch), text, split = false)
        if (pages.size > 1) throw Exception("本章页面过长，无法生成图片")
        return textInterceptor.pageUrl(key, 0)
    }

    // Details and lists

    private fun mangaPageParse(response: Response) = response.asJsoup().let { doc ->
        val mangas = doc.select(".book-layout").map {
            SManga.create().apply {
                url = it.absUrl("href").relativeToSite()
                val img = it.selectFirst("img")!!
                thumbnail_url = img.absUrl("data-src")
                title = img.attr("alt").convert(pref.getBoolean(PREF_DISPLAY_TRADITIONAL, false))
            }
        }
        val hasNextPage = when (response.request.url.pathSegments.first()) {
            "wenku" -> doc.selectInt("#pagelink > strong") < doc.selectInt("#pagelink > .last")
            "search" -> {
                val find = doc.selectText("#pagelink > span")?.let(PAGE_REGEX::find)
                find != null && find.groups[1]!!.value.toInt() < find.groups[2]!!.value.toInt()
            }

            else -> mangas.size == 50
        }
        MangasPage(mangas, hasNextPage)
    }

    private fun Response.parseManga() = SManga.create().apply {
        val doc = asJsoup()
        val switch = pref.getBoolean(PREF_DISPLAY_TRADITIONAL, false)
        doc.selectFirst(".aui-ver-form")?.run { throw Exception(text()) }
        val meta = doc.select(".book-meta")[1].text().convert(switch).split("|")
        val tags = doc.select(".tag-small").map { it.text().convert(switch) }
        url = doc.location().relativeToSite()
        title = doc.selectText(".book-title")!!.convert(switch)
        thumbnail_url = doc.selectFirst(".book-cover")!!.attr("src")
        description = buildDescription(doc).convert(switch)
        author = doc.selectText(".authorname")?.convert(switch)
        artist = doc.selectText(".illname")?.substringBefore('(')?.convert(switch)
        status = when (meta.getOrNull(1)) {
            "连载", "連載" -> SManga.ONGOING
            "完结", "完結" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        genre = (tags + meta.getOrElse(2) { "" }).joinToString()
        initialized = true
    }

    private fun buildDescription(doc: Document): String {
        val configs = pref.getStringSet(PREF_DESCRIPTION, DEFAULT_SET)!!
        val description = StringBuilder(doc.selectFirst("#bookSummary > content")!!.formatText("\n\n\n"))
        if ("A" in configs) {
            description.insert(0, doc.selectFirst(".notice")?.let { "> ${it.formatText("\n")}\n\n" } ?: "")
        }
        if ("B" in configs) {
            description.append(doc.selectFirst(".bkname-body")?.let { "\n\n***别名**：${it.text()}* " } ?: "")
        }
        if ("C" in configs) {
            description.append(
                doc.selectFirst(".book-detail-btn .btn-group-cell:last-child > a.orange")
                    ?.attr("href")?.substringAfter('?')
                    ?.let { "\n\n***[跳转至「嗶哩漫畫」上的同名漫画](https://www.bilimanga.net/detail/$it.html)*** " }
                    ?: "",
            )
        }
        return description.toString()
    }

    // Chapter pages

    private suspend fun sourcePages(url: String): List<String> {
        val first = url.let { if (it.contains("#")) it else it.replace(".", "_2.") }
        val doc = client.get(baseUrl + first, headers).asJsoup()
        val size = PAGE_SIZE_REGEX.find(doc.selectText("#atitle")!!)?.groups?.get(1)?.value?.toInt() ?: 1
        val prefix = doc.location().substringBeforeLast("_")
        return List(size.coerceAtLeast(1)) { i -> prefix + "${if (i > 0) "_${i + 1}" else ""}.html" }
    }

    private suspend fun ensureSalt(doc: Document): Pair<Int, Int> {
        salt?.let { return it }
        val path = CHAPTERLOG_REGEX.find(doc.body().toString())?.value ?: return DEFAULT_SALT
        var first = 0
        var second = 0
        EXPRESSION_REGEX.findAll(client.get(baseUrl + path, headers).body.string()).forEach { match ->
            val expressions = SALT_REGEX.findAll(match.value)
            if (expressions.count() != 2) return@forEach
            val values = expressions.map { calculate(it.value) }
            values.first().takeIf { it > 100 }?.let { first = it }
            values.last().takeIf { it > 200 }?.let { second = it }
        }
        return (first to second).also { salt = it }
    }

    private fun chapterTitle(doc: Document): String {
        val html = doc.selectFirst("#atitle")?.html() ?: return ""
        if (html.indexOf('/') >= 0) return ""
        return android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
    }

    private fun collectText(content: Element, chapterId: Int, salt: Pair<Int, Int>): String {
        val nodes = content.children().filter { element ->
            when (element.tagName()) {
                "p" -> element.wholeText().isNotBlank()
                "img" -> element.imageUrl() != null
                "div" -> false
                else -> element.imageUrl() != null || element.children().isNotEmpty() || element.wholeText().isNotBlank()
            }
        }.toMutableList()
        val paragraphs = nodes.filter { it.tagName() == "p" }
        val order = paragraphOrder(paragraphs.size, chapterId, salt)
        val scrambled = arrayOfNulls<Element>(order.size)
        order.forEachIndexed { original, target -> scrambled[target] = paragraphs[original] }
        var next = 0
        val blocks = mutableListOf<Block>()
        nodes.map { node -> if (node.tagName() == "p") scrambled[next++]!! else node }.forEach { node ->
            if (node.tagName() == "p") {
                NEWLINE_REGEX.replace(node.wholeText(), "\n").trim().takeIf(String::isNotBlank)?.let { blocks.add(Block.Text(it)) }
                return@forEach
            }
            node.select("img").mapNotNull { it.imageUrl() }.forEach { blocks.add(Block.Image(it)) }
        }
        return buildString {
            blocks.forEach { block ->
                when (block) {
                    is Block.Text -> append(block.text).append('\n')
                    is Block.Image -> append(IMAGE_MARKER).append(block.url).append(IMAGE_MARKER).append('\n')
                }
            }
        }
    }

    private fun paragraphOrder(size: Int, chapterId: Int, salt: Pair<Int, Int>): List<Int> {
        val order = MutableList(size) { it }
        if (size <= SORTED_PARAGRAPHS) return order
        var state = chapterId.toLong() * salt.first + salt.second
        for (i in order.lastIndex downTo SORTED_PARAGRAPHS + 1) {
            state = (state * SORT_SEED_MULTIPLIER + SORT_SEED_ADDEND) % SORT_SEED_MODULUS
            val span = i - SORTED_PARAGRAPHS + 1
            val j = SORTED_PARAGRAPHS + kotlin.math.floor((state / SORT_SEED_MODULUS.toDouble()) * span).toInt()
            if (j != i) {
                val swap = order[i]
                order[i] = order[j]
                order[j] = swap
            }
        }
        return order
    }

    private fun Element.imageUrl(): String? {
        val key = IMAGE_SOURCES.firstOrNull { !attr(it).isBlank() }
        return key?.let { absUrl(it) }?.takeIf(String::isNotBlank)
    }

    private fun calculate(expression: String): Int {
        var newExpression = expression.replace(" ", "")
        newExpression = Regex("(-?)0x([0-9a-fA-F]+)").replace(newExpression) { match ->
            val sign = match.groupValues[1]
            var num = match.groupValues[2].toLong(16)
            if (sign == "-") num = -num
            num.toString()
        }
        val tokens = Regex("-?\\d+|[+*/-]").findAll(newExpression).map(MatchResult::value).toList()
        val intermediate = mutableListOf<String>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token == "*" || token == "/") {
                val left = intermediate.removeAt(intermediate.size - 1).toInt()
                val right = tokens[index + 1].toInt()
                intermediate.add(if (token == "*") (left * right).toString() else (left / right).toString())
                index += 2
            } else {
                intermediate.add(token)
                index++
            }
        }
        var result = intermediate[0].toInt()
        index = 1
        while (index < intermediate.size) {
            val next = intermediate[index + 1].toInt()
            result = if (intermediate[index] == "+") result + next else result - next
            index += 2
        }
        return result
    }

    // Helpers

    private fun Elements.mapToChapter(switch: Boolean, date: Long, volume: String? = null) = mapIndexed { i, element ->
        val url = element.absUrl("href").takeUnless("javascript:cid(1)"::equals)
        SChapter.create().apply {
            this.url = (url ?: getChapterUrlByContext(i, this@mapToChapter)).relativeToSite()
            name = element.text().toHalfWidthDigits().convert(switch)
            date_upload = date
            volume?.let { scanlator = it.convert(switch) }
        }
    }

    private fun getChapterUrlByContext(i: Int, els: Elements) = when (i) {
        els.lastIndex -> "${els[i - 1].attr("href").replace(".html", "_66.html")}#next"
        else -> "${els[i + 1].attr("href")}#prev"
    }

    private fun Element.formatText(c: String) = wholeText().formatText(c)

    private fun String.relativeToSite(): String {
        val parsed = toHttpUrlOrNull() ?: return this
        return parsed.encodedPath + (parsed.encodedQuery?.let { "?$it" } ?: "")
    }

    private val SManga.novelId get() = NOVEL_ID_REGEX.find(url)!!.groupValues[1].toInt()

    private val Page.ids get() = CHAPTER_IDS_REGEX.find(url)!!.groups.drop(1).map { it?.value }

    private fun Response.hasCaptcha() = peekBody(16_384).string().let { it.contains("search_challenge") || it.contains("搜索验证") }

    companion object {
        private const val BOOKMARK_URL = "%s/modules/article/addbookcase.php?bid=%s&cid=%s&pid=1&ajax_request=1"
        private val DATE_REGEX = Regex("\\d{4}-\\d{1,2}-\\d{1,2}")
        private val PAGE_REGEX = Regex("第(\\d+)/(\\d+)页")
        private val PAGE_SIZE_REGEX = Regex("（\\d+/(\\d+)）")
        private val EXPRESSION_REGEX = Regex("Number.*?;")
        private val SALT_REGEX = Regex("(?<![a-zA-Z0-9_])-?0x[0-9a-fA-F]+(?:[+*\\-]-?0x[0-9a-fA-F]+)+")
        private val CHAPTERLOG_REGEX = Regex("/themes/zhmb/js/chapterlog\\.js\\?v[^\"]+")
        private val DEFAULT_SALT = 0 to 0
        private val IMAGE_SOURCES = listOf("data-src", "src", "data-original")
        private const val SORTED_PARAGRAPHS = 20
        private const val SORT_SEED_MULTIPLIER = 9302L
        private const val SORT_SEED_ADDEND = 49397L
        private const val SORT_SEED_MODULUS = 233280L
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("UTC+8")
        }
    }
}
