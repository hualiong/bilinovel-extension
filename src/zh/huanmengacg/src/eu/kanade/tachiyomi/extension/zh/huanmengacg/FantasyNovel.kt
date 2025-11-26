package eu.kanade.tachiyomi.extension.zh.huanmengacg

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.let

class FantasyNovel : HttpSource(), ConfigurableSource {
    override val baseUrl = "https://www.huanmengacg.com"
    override val lang = "zh"
    override val name = "幻梦轻小说"
    override val supportsLatest = true

    private val pref by getPreferencesLazy()

    override val client = super.client.newBuilder()
        .addInterceptor(HtmlInterceptor(baseUrl, pref)).build()

    // override fun headersBuilder() = super.headersBuilder()
    //     .add("Referer", "$baseUrl/")
    //     .add("Accept-Language", "zh")
    //     .add("Accept", "*/*")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        preferencesInternal(screen.context, pref).forEach(screen::addPreference)
    }

    // Customize

    companion object {
        val DATE_REGEX = Regex("\\d{4}-\\d{1,2}-\\d{1,2}")
        val FILTER_TAGS = listOf("list", "tags", "finish", "size", "order")
        val ADS_STRING = setOf(
            "本文来自 幻梦轻小说",
            "(http://www.huanmengacg.com)",
            "最新最全的日本动漫轻小说 幻梦轻小说",
            "更多轻小说与TXT下载，尽在幻梦轻小说网（www.huanmengacg.com）",
        )
        val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.CHINESE)
    }

    // Popular Page

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/index.php/book/category/page/$page")

    override fun popularMangaParse(response: Response) = response.asJsoup().let { doc ->
        val mangas = (
            doc.selectFirst(".module")?.select(".details-part")
                ?: doc.select(".details-part")
            ).map {
            SManga.create().apply {
                setUrlWithoutDomain(it.absUrl("href"))
                val img = it.selectFirst("img")!!
                thumbnail_url = img.absUrl("data-original")
                title = img.attr("alt")
            }
        }
        val current = response.request.url.pathSegments.last().toIntOrNull() ?: 0
        val total = doc.selectFirst(".pagination > a:nth-last-child(2)")?.text()
        MangasPage(mangas, current < (total?.toInt() ?: 0))
    }

    // Latest Page

    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/index.php/book/category/order/addtime/page/$page")

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // Search Page

    override fun getFilterList() = buildFilterList()

    // /index.php/book/search?action=search&key=
    // /index.php/book/category/list/56/tags/1/finish/1/size/1/order/addtime/page/1
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/index.php/book".toHttpUrl().newBuilder()
        if (query.isNotBlank()) {
            url.addPathSegment("search")
                .addQueryParameter("action", "search").addQueryParameter("key", query)
        } else {
            url.addPathSegment("category")
            FILTER_TAGS.forEachIndexed { i, tag ->
                filters[i].toString().takeIf { it.isNotBlank() }?.let {
                    url.addPathSegments("$tag/$it")
                }
            }
            url.addPathSegments("page/$page")
        }
        return GET(url.build())
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // Manga Detail Page

    override fun mangaDetailsParse(response: Response) = SManga.create().apply {
        val doc = response.asJsoup()
        doc.select(".book-metas").let {
            author = it[0].text().substring(3)
            status = when (it[1].text().substring(3)) {
                "连载" -> SManga.ONGOING
                "完结" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            genre = it[2].text().substring(3).replace(" ", ", ")
        }
        description = doc.selectFirst(".book-summary")?.text()?.trim()
    }

    // Catalog Page

    override fun chapterListParse(response: Response) = response.asJsoup().let { doc ->
        val info = doc.select(".book-metas").last()?.text()?.substring(3)
        val date = DATE_FORMAT.tryParse(info?.let { DATE_REGEX.find(it) }?.value)
        val chapters = doc.select("#chapterlist a")
        chapters.map { e ->
            val text = e.text()
            SChapter.create().apply {
                name = text.substringAfter(" ").trim()
                scanlator = text.substringBefore(" ")
                date_upload = date
                setUrlWithoutDomain(e.absUrl("href"))
            }
        }.reversed()
    }

    // Manga View Page

    override fun pageListParse(response: Response) = response.asJsoup().let { doc ->
        val url = response.request.url.toString()
        val title = doc.selectFirst(".menu-side-p")!!.text().substringAfter(" ").trim()
        val content = doc.selectFirst("#BookText")!!
        content.select("p").let { p ->
            p.removeIf { (!it.hasText() || ADS_STRING.contains(it.text())) && it.childrenSize() == 0 }
            p.chunked(pref.getString(PREF_LINES_PER_PAGE, "100")!!.toInt()).map {
                Element(content.tag(), content.baseUri()).appendChildren(
                    it.map { e ->
                        e.takeIf { e.hasText() }?.text("\u00A0\u00A0\u00A0\u00A0" + e.text()) ?: e
                    },
                )
            }.mapIndexed { i, e ->
                Page(i, url, HtmlInterceptorHelper.createUrl(if (i == 0) title else "", e.html()))
            }
        }
    }

    // Image

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()
}
