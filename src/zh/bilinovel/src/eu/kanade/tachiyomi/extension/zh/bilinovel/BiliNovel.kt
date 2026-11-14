package eu.kanade.tachiyomi.extension.zh.bilinovel

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.applicationContext
import keiyoushi.utils.getPreferences
import keiyoushi.utils.getString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

@Source
abstract class BiliNovel :
    HttpSource(),
    ConfigurableSource {
    override val supportsLatest = true

    // The settings of an earlier version are carried over as soon as the source is built, so a
    // reader that never opens this source's preferences still gets the migrated defaults.
    // Both sources share one preference file (stored under the web source's id, i.e. the old one).
    internal val pref by lazy { getPreferences(SHARED_PREFS_ID).also { it.migratePreferences() } }

    /** One @Source class, two generated sources: the old site reader and the app API. */
    internal val isWebMode get() = id == SHARED_PREFS_ID
    private val web by lazy { BiliNovelWeb(this) }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // The screen persists to this source's own file while both sources read the shared one:
        // adopt whatever the old split left behind, then push the shared truth back so the screen
        // opens showing the values the reader actually has.
        if (!isWebMode) {
            val own = getPreferences(id)
            if (own !== pref) {
                own.all.forEach { (key, value) ->
                    if (key in SHARED_PREF_KEYS && !pref.contains(key)) pref.mirror(key, value)
                }
                SHARED_PREF_KEYS.forEach { key -> own.mirror(key, pref.all[key]) }
            }
        }
        // The progress switch only makes sense once a login is stored (cookie, token or credentials).
        val loggedIn = if (isWebMode) {
            client.cookieJar.loadForRequest(baseUrl.toHttpUrl()).any { it.name == "jieqiUserInfo" }
        } else {
            clientToken() != null || hasCredentials()
        }
        preferencesInternal(screen.context, pref, ::onCredentialsChanged, includeLink = isWebMode, isLoggedIn = loggedIn, loginStatus = { currentLoginStatus() }, loginError = { loginError })
            .filterNot { if (isWebMode) it.key in CLIENT_ONLY_PREFS else it.key in WEB_ONLY_PREFS }
            .forEach { p ->
                if (p.key == PREF_ACCOUNT) loginPref = p
                screen.addPreference(p)
            }
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", "zh")
        .add("Accept", "*/*")
        // The site serves the readable mobile layout only to a mobile UA.
        .add("User-Agent", MOBILE_UA)

    internal val textInterceptor = TextInterceptor(super.client, headers, pref)

    /** The case ids of the books already in the official client's shelf, so they are looked up once. */
    private val bookcaseIds = ConcurrentHashMap<Int, Int>()

    /** The login item, so its summary can follow the session once the silent re-login settles. */
    private var loginPref: androidx.preference.Preference? = null

    /** Why the last login attempt failed; null while no failure is on record. */
    @Volatile
    internal var loginError: String? = null

    @Volatile
    private var loginPending = false

    /** A credential edit takes effect at once: the old session and its shelf cache go, a complete new pair logs in right away. */
    internal fun onCredentialsChanged() {
        clearToken()
        bookcaseIds.clear()
        loginError = null
        if (!hasCredentials()) {
            toast("已退出登录")
            refreshLoginSummary()
            return
        }
        loginPending = true
        CoroutineScope(Dispatchers.IO).launch {
            val error = login()
            loginPending = false
            toast(error?.let { "登录失败：$it" } ?: "登录成功")
            refreshLoginSummary()
        }
    }

    private fun currentLoginStatus() = when {
        loginPending -> LoginStatus.PENDING
        clientToken() != null -> LoginStatus.ACTIVE
        loginError != null -> LoginStatus.FAILED
        else -> LoginStatus.NONE
    }

    internal fun refreshLoginSummary() = Handler(Looper.getMainLooper()).post {
        loginPref?.summary = loginSummary(pref, currentLoginStatus(), loginError)
    }

    private fun toast(text: String) = Handler(Looper.getMainLooper()).post {
        Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
    }

    override val client = super.client.newBuilder().also {
        val s = pref.getString(PREF_RATE_LIMIT, "10/10")!!.split("/")
        it.rateLimit(s[0].toInt(), s[1].toInt().seconds) { url ->
            url.host == baseUrl.removePrefix("https://") || url.host == API_HOST
        }
    }.addNetworkInterceptor(WebChapterInterceptor()).addInterceptor(textInterceptor).build()

    // Details and chapter list

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (isWebMode) return web.update(manga, chapters, fetchDetails, fetchChapters)
        val articleId = manga.novelId
        val updated = if (fetchDetails) {
            apiPost(
                "novel/getNovelInfo",
                JSONObject().put("articleid", articleId),
                mapOf("countVisit" to "false"),
                restricted = true,
            ).data().toSManga(complete = true)
        } else {
            manga
        }
        return SMangaUpdate(updated, if (fetchChapters) getChapterList(articleId) else chapters)
    }

    /** A list entry only carries what the list shows; the details answer fills the rest. */
    private fun JSONObject.toSManga(complete: Boolean = false) = SManga.create().apply {
        setUrlWithoutDomain("/novel/${int("articleid")}.html")
        title = str("articlename")
        author = str("author").ifBlank { null }
        artist = str("illustrator").ifBlank { null }
        thumbnail_url = str("cover")
        description = buildDescription(this@toSManga)
        genre = str("keywords").split(' ').filter(String::isNotBlank).joinToString()
        status = if (int("fullflag") == 1) SManga.COMPLETED else SManga.ONGOING
        initialized = complete
    }

    private fun buildDescription(novel: JSONObject): String {
        val configs = pref.getStringSet(PREF_DESCRIPTION, DEFAULT_SET)!!
        val description = StringBuilder(novel.str("intro").formatText("\n\n\n"))
        if ("A" in configs) {
            novel.str("notice").formatText("\n").takeIf(String::isNotBlank)?.let {
                description.insert(0, "> $it\n\n")
            }
        }
        if ("B" in configs) {
            novel.str("backupname").takeIf(String::isNotBlank)?.let {
                description.append("\n\n***别名**：$it* ")
            }
        }
        return description.toString().convert(pref.getBoolean(PREF_DISPLAY_TRADITIONAL, false))
    }

    private suspend fun getChapterList(articleId: Int): List<SChapter> {
        val data = apiPost(
            "novel/getchapter",
            JSONObject().put("articleid", articleId),
            restricted = true,
        ).data()
        val switch = pref.getBoolean(PREF_DISPLAY_TRADITIONAL, false)
        val articleName = data.str("articlename")
        // The API gives no per-chapter date, so every chapter carries the book's last update, as before.
        val updatedAt = data.optLong("lastupdate") * 1000
        val chapters = mutableListOf<SChapter>()

        data.arr("chapters")?.objects()?.forEach { volume ->
            val volumeName = volume.str("chaptername")
            // A root entry is either a volume wrapping chapters or a flat chapter of its own.
            val children = volume.arr("chapterList")?.objects().orEmpty().ifEmpty { listOf(volume) }
            children.forEach { chapter ->
                if (chapter.int("chaptertype") == 1) return@forEach
                chapters += SChapter.create().apply {
                    setUrlWithoutDomain("/novel/$articleId/${chapter.int("chapterid")}.html")
                    name = chapter.str("chaptername").toHalfWidthDigits().convert(switch)
                    scanlator = volumeName.takeIf(String::isNotBlank)?.convert(switch)
                    date_upload = updatedAt
                    // The bookmark sync needs the book title, which the chapter list carries.
                    memo = buildJsonObject { put("title", articleName) }
                }
            }
        }
        // The reader expects the newest chapter first, while the API lists them in reading order.
        return chapters.reversed()
    }

    // Pages

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        if (isWebMode) return web.pages(chapter)
        val (articleId, chapterId) = chapter.ids
        val data = apiPost(
            "novel/getNovelText",
            query = mapOf("articleid" to articleId, "chapterid" to chapterId),
            restricted = true,
        ).data()
        val switch = pref.getBoolean(PREF_DISPLAY_TRADITIONAL, false)
        val key = "$articleId-$chapterId"
        val text = chapterText(
            data.str("text").convert(switch),
            data.arr("images")?.objects()?.map { it.str("path") } ?: emptyList(),
            data.optBoolean("isImage"),
            baseUrl,
        )
        val entries = textInterceptor.prepare(key, data.str("chaptername").convert(switch), text)
        val articleName = runCatching { chapter.memo.getString("title") }.getOrNull()
        syncReadingProgress(articleId.toInt(), chapterId.toInt(), data.str("chaptername"), articleName)
        // An illustration page keeps its own URL: Mihon requests it, and the interceptor frames
        // the response with the reading margins.
        return List(entries.size) { i ->
            Page(i, entries[i]?.let { url -> "$url#${TextInterceptor.FRAME}" } ?: textInterceptor.pageUrl(key, i))
        }
    }

    override suspend fun getImageUrl(page: Page): String = if (isWebMode) web.image(page) else page.url

    // Popular

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (isWebMode) return web.popular(page)
        val suffix = pref.getString(PREF_POPULAR_DISPLAY, DEFAULT_POPULAR)!!
        if (suffix == NEW_BOOK_SUFFIX) {
            return apiPost(
                "novel/getNewBookRanking",
                JSONObject().put("page", page).put("limit", PAGE_SIZE),
            ).bookPage()
        }
        val (type, period) = POPULAR_RANKINGS[suffix] ?: POPULAR_RANKINGS.getValue(DEFAULT_POPULAR)
        val body = JSONObject().put("page", page).put("limit", PAGE_SIZE).put("type", type)
        period?.let { body.put("period", it) }
        return apiPost("novel/getRanking", body).bookPage()
    }

    private fun JSONObject.bookPage(): MangasPage {
        val books = dataArray().objects()
        return MangasPage(books.map { it.toSManga() }, optBoolean("hasMore", books.size >= PAGE_SIZE))
    }

    // Latest

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (isWebMode) return web.latest(page)
        return apiPost(
            "novel/getRanking",
            JSONObject().put("page", page).put("limit", PAGE_SIZE).put("type", LAST_UPDATE_RANKING),
        ).bookPage()
    }

    // Search

    override fun getFilterList() = if (isWebMode) web.filters() else buildFilterList()

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        if (isWebMode) return web.search(page, query, filters)
        NOVEL_ID_REGEX.find(query)?.let { match ->
            val novel = apiPost(
                "novel/getNovelInfo",
                JSONObject().put("articleid", match.groupValues[1].toInt()),
                mapOf("countVisit" to "false"),
                restricted = true,
            ).data().toSManga(complete = true)
            return MangasPage(listOf(novel), false)
        }

        val body = JSONObject().put("searchKey", query).put("recordKeyword", true).put("limit", PAGE_SIZE)
        if (query.isBlank()) {
            filters.filterIsInstance<RegionFilter>().firstOrNull()?.toString()?.takeIf(String::isNotBlank)
                ?.let { body.put("filterRGroup", it.toInt()) }
            filters.filterIsInstance<ThemeFilter>().firstOrNull()?.toString()?.takeIf(String::isNotBlank)?.let {
                body.put("tagNames", JSONArray().put(it))
                body.put("tagMatchMode", "all")
            }
            filters.filterIsInstance<SortFilter>().firstOrNull()?.toString()?.let { body.put("sortBy", it) }
            filters.filterIsInstance<AnimeFilter>().firstOrNull()?.toString()?.takeIf(String::isNotBlank)
                ?.let { body.put("animeFilter", it.toInt()) }
            filters.filterIsInstance<CountFilter>().firstOrNull()?.let {
                it.minWords?.let { min -> body.put("minWords", min) }
                it.maxWords?.let { max -> body.put("maxWords", max) }
            }
            filters.filterIsInstance<StatusFilter>().firstOrNull()?.toString()?.takeIf(String::isNotBlank)
                ?.let { body.put("filterFullFlag", it.toInt()) }
        }
        return apiPost(
            "novel/searchNovel",
            body,
            mapOf("pageNum" to page.toString(), "pageSize" to PAGE_SIZE.toString()),
        ).bookPage()
    }

    /** Mirrors the web bookmark: records the chapter as read in the official client's shelf. */
    private suspend fun syncReadingProgress(articleId: Int, chapterId: Int, chapterName: String, articleName: String?) {
        if (!pref.getBoolean(PREF_AUTO_BOOKMARK, false)) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                if (clientToken() == null) return@runCatching
                var caseId = bookcaseIds[articleId]
                if (caseId == null) {
                    val check = apiPost(
                        "bookcase/check",
                        JSONObject().put("articleid", articleId),
                        retryLogin = false,
                    ).data()
                    if (!check.optBoolean("inBookcase")) {
                        // The web bookmark added the book when it was missing; the client does the same.
                        articleName?.takeIf(String::isNotBlank)?.let {
                            apiPost(
                                "bookcase/add",
                                JSONObject()
                                    .put("articleid", articleId)
                                    .put("articlename", it)
                                    .put("classid", 0)
                                    .put("chapterid", chapterId)
                                    .put("chaptername", chapterName)
                                    .put("pageid", 1),
                            )
                        }
                        return@runCatching
                    }
                    caseId = check.optJSONObject("bookcase")?.optInt("caseid")?.takeIf { it > 0 }
                    caseId?.let { bookcaseIds[articleId] = it }
                }
                caseId?.let {
                    apiPost(
                        "bookcase/updateProgress",
                        JSONObject()
                            .put("caseid", it)
                            .put("chapterid", chapterId)
                            .put("chaptername", chapterName)
                            .put("pageid", 1),
                    )
                }
            }.onFailure { Log.e("BiliNovel", "同步阅读进度失败: ${it.message}") }
        }
    }

    // Helpers

    private fun hasCredentials() = !pref.getString(PREF_ACCOUNT, null).isNullOrBlank() && !pref.getString(PREF_PASSWORD, null).isNullOrBlank()

    private val SManga.novelId get() = NOVEL_ID_REGEX.find(url)!!.groupValues[1].toInt()

    private val SChapter.ids get() = CHAPTER_IDS_REGEX.find(url)!!.groupValues.let { it[1] to it[2] }

    companion object {
        /** Preferences live under the web source's id, so both sources read and write the same file. */
        private const val SHARED_PREFS_ID = 6_832_494_364_245_687_154L
        private const val PAGE_SIZE = 50
        internal const val API_HOST = "api.readpai.com"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val CLIENT_ONLY_PREFS = setOf(PREF_ACCOUNT, PREF_PASSWORD)

        // The strict image preload only concerns the web reader; the client fetches per page.
        private val WEB_ONLY_PREFS = setOf(PREF_LOAD_ALL_IMAGES)

        private const val DEFAULT_POPULAR = "/top/weekvisit/%d.html"
        private const val NEW_BOOK_SUFFIX = "/top/newhot/%d.html"
        private const val LAST_UPDATE_RANKING = 7

        /** Ranking type and period of every choice in the "热门显示内容" setting. */
        private val POPULAR_RANKINGS = mapOf(
            "/top/monthvisit/%d.html" to (1 to "month"),
            "/top/weekvisit/%d.html" to (1 to "week"),
            "/top/monthvote/%d.html" to (6 to "month"),
            "/top/weekvote/%d.html" to (6 to "week"),
            "/top/monthflower/%d.html" to (4 to "month"),
            "/top/weekflower/%d.html" to (4 to "week"),
            "/top/postdate/%d.html" to (2 to null),
            "/top/goodnum/%d.html" to (5 to null),
        )

        internal val TRADITIONAL_CHARACTER_MAP = mapOf(

            '皑' to '皚', '蔼' to '藹', '碍' to '礙', '爱' to '愛', '翱' to '翺', '袄' to '襖',
            '奥' to '奧', '坝' to '壩', '罢' to '罷', '摆' to '擺', '败' to '敗', '颁' to '頒',
            '办' to '辦', '绊' to '絆', '帮' to '幫', '绑' to '綁', '镑' to '鎊', '谤' to '謗',
            '剥' to '剝', '饱' to '飽', '宝' to '寶', '报' to '報', '鲍' to '鮑', '辈' to '輩',
            '贝' to '貝', '钡' to '鋇', '狈' to '狽', '备' to '備', '惫' to '憊', '绷' to '繃',
            '笔' to '筆', '毕' to '畢', '毙' to '斃', '币' to '幣', '闭' to '閉', '边' to '邊',
            '编' to '編', '贬' to '貶', '变' to '變', '辩' to '辯', '辫' to '辮', '标' to '標',
            '鳖' to '鼈', '别' to '別', '瘪' to '癟', '濒' to '瀕', '滨' to '濱', '宾' to '賓',
            '摈' to '擯', '饼' to '餅', '并' to '並', '拨' to '撥', '钵' to '缽', '铂' to '鉑',
            '驳' to '駁', '卜' to '蔔', '补' to '補', '财' to '財', '参' to '參', '蚕' to '蠶',
            '残' to '殘', '惭' to '慚', '惨' to '慘', '灿' to '燦', '苍' to '蒼', '舱' to '艙',
            '仓' to '倉', '沧' to '滄', '厕' to '廁', '侧' to '側', '册' to '冊', '测' to '測',
            '层' to '層', '诧' to '詫', '搀' to '攙', '掺' to '摻', '蝉' to '蟬', '馋' to '饞',
            '谗' to '讒', '缠' to '纏', '铲' to '鏟', '产' to '産', '阐' to '闡', '颤' to '顫',
            '场' to '場', '尝' to '嘗', '长' to '長', '偿' to '償', '肠' to '腸', '厂' to '廠',
            '畅' to '暢', '钞' to '鈔', '车' to '車', '彻' to '徹', '尘' to '塵', '沉' to '沈',
            '陈' to '陳', '衬' to '襯', '撑' to '撐', '称' to '稱', '惩' to '懲', '诚' to '誠',
            '骋' to '騁', '痴' to '癡', '迟' to '遲', '驰' to '馳', '耻' to '恥', '齿' to '齒',
            '炽' to '熾', '冲' to '沖', '虫' to '蟲', '宠' to '寵', '畴' to '疇', '踌' to '躊',
            '筹' to '籌', '绸' to '綢', '丑' to '醜', '橱' to '櫥', '厨' to '廚', '锄' to '鋤',
            '雏' to '雛', '础' to '礎', '储' to '儲', '触' to '觸', '处' to '處', '传' to '傳',
            '疮' to '瘡', '闯' to '闖', '创' to '創', '锤' to '錘', '纯' to '純', '绰' to '綽',
            '辞' to '辭', '词' to '詞', '赐' to '賜', '聪' to '聰', '葱' to '蔥', '囱' to '囪',
            '从' to '從', '丛' to '叢', '凑' to '湊', '蹿' to '躥', '窜' to '竄', '错' to '錯',
            '达' to '達', '带' to '帶', '贷' to '貸', '担' to '擔', '单' to '單', '郸' to '鄲',
            '掸' to '撣', '胆' to '膽', '惮' to '憚', '诞' to '誕', '弹' to '彈', '当' to '當',
            '挡' to '擋', '党' to '黨', '荡' to '蕩', '档' to '檔', '捣' to '搗', '岛' to '島',
            '祷' to '禱', '导' to '導', '盗' to '盜', '灯' to '燈', '邓' to '鄧', '敌' to '敵',
            '涤' to '滌', '递' to '遞', '缔' to '締', '颠' to '顛', '点' to '點', '垫' to '墊',
            '电' to '電', '淀' to '澱', '钓' to '釣', '调' to '調', '迭' to '叠', '谍' to '諜',
            '叠' to '疊', '钉' to '釘', '顶' to '頂', '锭' to '錠', '订' to '訂', '丢' to '丟',
            '东' to '東', '动' to '動', '栋' to '棟', '冻' to '凍', '斗' to '鬥', '犊' to '犢',
            '独' to '獨', '读' to '讀', '赌' to '賭', '镀' to '鍍', '锻' to '鍛', '断' to '斷',
            '缎' to '緞', '兑' to '兌', '队' to '隊', '对' to '對', '吨' to '噸', '顿' to '頓',
            '钝' to '鈍', '夺' to '奪', '堕' to '墮', '鹅' to '鵝', '额' to '額', '讹' to '訛',
            '恶' to '惡', '饿' to '餓', '儿' to '兒', '尔' to '爾', '饵' to '餌', '贰' to '貳',
            '发' to '發', '罚' to '罰', '阀' to '閥', '珐' to '琺', '矾' to '礬', '钒' to '釩',
            '烦' to '煩', '范' to '範', '贩' to '販', '饭' to '飯', '访' to '訪', '纺' to '紡',
            '飞' to '飛', '诽' to '誹', '废' to '廢', '费' to '費', '纷' to '紛', '坟' to '墳',
            '奋' to '奮', '愤' to '憤', '粪' to '糞', '丰' to '豐', '枫' to '楓', '锋' to '鋒',
            '风' to '風', '疯' to '瘋', '冯' to '馮', '缝' to '縫', '讽' to '諷', '凤' to '鳳',
            '肤' to '膚', '辐' to '輻', '抚' to '撫', '辅' to '輔', '赋' to '賦', '复' to '複',
            '负' to '負', '讣' to '訃', '妇' to '婦', '缚' to '縛', '该' to '該', '钙' to '鈣',
            '盖' to '蓋', '干' to '幹', '赶' to '趕', '秆' to '稈', '赣' to '贛', '冈' to '岡',
            '刚' to '剛', '钢' to '鋼', '纲' to '綱', '岗' to '崗', '皋' to '臯', '镐' to '鎬',
            '搁' to '擱', '鸽' to '鴿', '阁' to '閣', '铬' to '鉻', '个' to '個', '给' to '給',
            '龚' to '龔', '宫' to '宮', '巩' to '鞏', '贡' to '貢', '钩' to '鈎', '沟' to '溝',
            '构' to '構', '购' to '購', '够' to '夠', '蛊' to '蠱', '顾' to '顧', '剐' to '剮',
            '关' to '關', '观' to '觀', '馆' to '館', '惯' to '慣', '贯' to '貫', '广' to '廣',
            '规' to '規', '硅' to '矽', '归' to '歸', '龟' to '龜', '闺' to '閨', '轨' to '軌',
            '诡' to '詭', '柜' to '櫃', '贵' to '貴', '刽' to '劊', '辊' to '輥', '滚' to '滾',
            '锅' to '鍋', '国' to '國', '过' to '過', '骇' to '駭', '韩' to '韓', '汉' to '漢',
            '号' to '號', '阂' to '閡', '鹤' to '鶴', '贺' to '賀', '横' to '橫', '轰' to '轟',
            '鸿' to '鴻', '红' to '紅', '后' to '後', '壶' to '壺', '护' to '護', '沪' to '滬',
            '户' to '戶', '哗' to '嘩', '华' to '華', '画' to '畫', '划' to '劃', '话' to '話',
            '怀' to '懷', '坏' to '壞', '欢' to '歡', '环' to '環', '还' to '還', '缓' to '緩',
            '换' to '換', '唤' to '喚', '痪' to '瘓', '焕' to '煥', '涣' to '渙', '黄' to '黃',
            '谎' to '謊', '挥' to '揮', '辉' to '輝', '毁' to '毀', '贿' to '賄', '秽' to '穢',
            '会' to '會', '烩' to '燴', '汇' to '彙', '讳' to '諱', '诲' to '誨', '绘' to '繪',
            '荤' to '葷', '浑' to '渾', '伙' to '夥', '获' to '獲', '货' to '貨', '祸' to '禍',
            '击' to '擊', '机' to '機', '积' to '積', '饥' to '饑', '讥' to '譏', '鸡' to '雞',
            '绩' to '績', '缉' to '緝', '极' to '極', '辑' to '輯', '级' to '級', '挤' to '擠',
            '几' to '幾', '蓟' to '薊', '剂' to '劑', '济' to '濟', '计' to '計', '记' to '記',
            '际' to '際', '继' to '繼', '纪' to '紀', '夹' to '夾', '荚' to '莢', '颊' to '頰',
            '贾' to '賈', '钾' to '鉀', '价' to '價', '驾' to '駕', '歼' to '殲', '监' to '監',
            '坚' to '堅', '笺' to '箋', '间' to '間', '艰' to '艱', '缄' to '緘', '茧' to '繭',
            '检' to '檢', '碱' to '堿', '硷' to '鹼', '拣' to '揀', '捡' to '撿', '简' to '簡',
            '俭' to '儉', '减' to '減', '荐' to '薦', '槛' to '檻', '鉴' to '鑒', '践' to '踐',
            '贱' to '賤', '见' to '見', '键' to '鍵', '舰' to '艦', '剑' to '劍', '饯' to '餞',
            '渐' to '漸', '溅' to '濺', '涧' to '澗', '将' to '將', '浆' to '漿', '蒋' to '蔣',
            '桨' to '槳', '奖' to '獎', '讲' to '講', '酱' to '醬', '胶' to '膠', '浇' to '澆',
            '骄' to '驕', '娇' to '嬌', '搅' to '攪', '铰' to '鉸', '矫' to '矯', '侥' to '僥',
            '脚' to '腳', '饺' to '餃', '缴' to '繳', '绞' to '絞', '轿' to '轎', '较' to '較',
            '秸' to '稭', '阶' to '階', '节' to '節', '茎' to '莖', '鲸' to '鯨', '惊' to '驚',
            '经' to '經', '颈' to '頸', '静' to '靜', '镜' to '鏡', '径' to '徑', '痉' to '痙',
            '竞' to '競', '净' to '淨', '纠' to '糾', '厩' to '廄', '旧' to '舊', '驹' to '駒',
            '举' to '舉', '据' to '據', '锯' to '鋸', '惧' to '懼', '剧' to '劇', '鹃' to '鵑',
            '绢' to '絹', '杰' to '傑', '洁' to '潔', '结' to '結', '诫' to '誡', '届' to '屆',
            '紧' to '緊', '锦' to '錦', '仅' to '僅', '谨' to '謹', '进' to '進', '晋' to '晉',
            '烬' to '燼', '尽' to '盡', '劲' to '勁', '荆' to '荊', '觉' to '覺', '决' to '決',
            '诀' to '訣', '绝' to '絕', '钧' to '鈞', '军' to '軍', '骏' to '駿', '开' to '開',
            '凯' to '凱', '颗' to '顆', '壳' to '殼', '课' to '課', '垦' to '墾', '恳' to '懇',
            '抠' to '摳', '库' to '庫', '裤' to '褲', '夸' to '誇', '块' to '塊', '侩' to '儈',
            '宽' to '寬', '矿' to '礦', '旷' to '曠', '况' to '況', '亏' to '虧', '岿' to '巋',
            '窥' to '窺', '馈' to '饋', '溃' to '潰', '扩' to '擴', '阔' to '闊', '蜡' to '蠟',
            '腊' to '臘', '莱' to '萊', '来' to '來', '赖' to '賴', '蓝' to '藍', '栏' to '欄',
            '拦' to '攔', '篮' to '籃', '阑' to '闌', '兰' to '蘭', '澜' to '瀾', '谰' to '讕',
            '揽' to '攬', '览' to '覽', '懒' to '懶', '缆' to '纜', '烂' to '爛', '滥' to '濫',
            '捞' to '撈', '劳' to '勞', '涝' to '澇', '乐' to '樂', '镭' to '鐳', '垒' to '壘',
            '类' to '類', '泪' to '淚', '篱' to '籬', '离' to '離', '里' to '裡', '鲤' to '鯉',
            '礼' to '禮', '丽' to '麗', '厉' to '厲', '励' to '勵', '砾' to '礫', '历' to '曆',
            '沥' to '瀝', '隶' to '隸', '俩' to '倆', '联' to '聯', '莲' to '蓮', '连' to '連',
            '镰' to '鐮', '怜' to '憐', '涟' to '漣', '帘' to '簾', '敛' to '斂', '脸' to '臉',
            '链' to '鏈', '恋' to '戀', '炼' to '煉', '练' to '練', '粮' to '糧', '凉' to '涼',
            '两' to '兩', '辆' to '輛', '谅' to '諒', '疗' to '療', '辽' to '遼', '镣' to '鐐',
            '猎' to '獵', '临' to '臨', '邻' to '鄰', '鳞' to '鱗', '凛' to '凜', '赁' to '賃',
            '龄' to '齡', '铃' to '鈴', '凌' to '淩', '灵' to '靈', '岭' to '嶺', '领' to '領',
            '馏' to '餾', '刘' to '劉', '龙' to '龍', '聋' to '聾', '咙' to '嚨', '笼' to '籠',
            '垄' to '壟', '拢' to '攏', '陇' to '隴', '楼' to '樓', '娄' to '婁', '搂' to '摟',
            '篓' to '簍', '芦' to '蘆', '卢' to '盧', '颅' to '顱', '庐' to '廬', '炉' to '爐',
            '掳' to '擄', '卤' to '鹵', '虏' to '虜', '鲁' to '魯', '赂' to '賂', '禄' to '祿',
            '录' to '錄', '陆' to '陸', '驴' to '驢', '吕' to '呂', '铝' to '鋁', '侣' to '侶',
            '屡' to '屢', '缕' to '縷', '虑' to '慮', '滤' to '濾', '绿' to '綠', '峦' to '巒',
            '挛' to '攣', '孪' to '孿', '滦' to '灤', '乱' to '亂', '抡' to '掄', '轮' to '輪',
            '伦' to '倫', '仑' to '侖', '沦' to '淪', '纶' to '綸', '论' to '論', '萝' to '蘿',
            '罗' to '羅', '逻' to '邏', '锣' to '鑼', '箩' to '籮', '骡' to '騾', '骆' to '駱',
            '络' to '絡', '妈' to '媽', '玛' to '瑪', '码' to '碼', '蚂' to '螞', '马' to '馬',
            '骂' to '罵', '吗' to '嗎', '买' to '買', '麦' to '麥', '卖' to '賣', '迈' to '邁',
            '脉' to '脈', '瞒' to '瞞', '馒' to '饅', '蛮' to '蠻', '满' to '滿', '谩' to '謾',
            '猫' to '貓', '锚' to '錨', '铆' to '鉚', '贸' to '貿', '么' to '麽', '霉' to '黴',
            '没' to '沒', '镁' to '鎂', '门' to '門', '闷' to '悶', '们' to '們', '锰' to '錳',
            '梦' to '夢', '谜' to '謎', '弥' to '彌', '觅' to '覓', '幂' to '冪', '绵' to '綿',
            '缅' to '緬', '庙' to '廟', '灭' to '滅', '悯' to '憫', '闽' to '閩', '鸣' to '鳴',
            '铭' to '銘', '谬' to '謬', '谋' to '謀', '亩' to '畝', '钠' to '鈉', '纳' to '納',
            '难' to '難', '挠' to '撓', '脑' to '腦', '恼' to '惱', '闹' to '鬧', '馁' to '餒',
            '内' to '內', '拟' to '擬', '腻' to '膩', '撵' to '攆', '捻' to '撚', '酿' to '釀',
            '鸟' to '鳥', '聂' to '聶', '啮' to '齧', '镊' to '鑷', '镍' to '鎳', '柠' to '檸',
            '狞' to '獰', '宁' to '甯', '拧' to '擰', '泞' to '濘', '钮' to '鈕', '纽' to '紐',
            '脓' to '膿', '浓' to '濃', '农' to '農', '疟' to '瘧', '诺' to '諾', '欧' to '歐',
            '鸥' to '鷗', '殴' to '毆', '呕' to '嘔', '沤' to '漚', '盘' to '盤', '庞' to '龐',
            '赔' to '賠', '喷' to '噴', '鹏' to '鵬', '骗' to '騙', '飘' to '飄', '频' to '頻',
            '贫' to '貧', '苹' to '蘋', '凭' to '憑', '评' to '評', '泼' to '潑', '颇' to '頗',
            '扑' to '撲', '铺' to '鋪', '朴' to '樸', '谱' to '譜', '栖' to '棲', '凄' to '淒',
            '脐' to '臍', '齐' to '齊', '骑' to '騎', '岂' to '豈', '启' to '啓', '气' to '氣',
            '弃' to '棄', '讫' to '訖', '牵' to '牽', '扦' to '扡', '钎' to '釺', '铅' to '鉛',
            '迁' to '遷', '签' to '簽', '谦' to '謙', '钱' to '錢', '钳' to '鉗', '潜' to '潛',
            '浅' to '淺', '谴' to '譴', '堑' to '塹', '枪' to '槍', '呛' to '嗆', '墙' to '牆',
            '蔷' to '薔', '强' to '強', '抢' to '搶', '锹' to '鍬', '桥' to '橋', '乔' to '喬',
            '侨' to '僑', '翘' to '翹', '窍' to '竅', '窃' to '竊', '钦' to '欽', '亲' to '親',
            '寝' to '寢', '轻' to '輕', '氢' to '氫', '倾' to '傾', '顷' to '頃', '请' to '請',
            '庆' to '慶', '琼' to '瓊', '穷' to '窮', '趋' to '趨', '区' to '區', '躯' to '軀',
            '驱' to '驅', '龋' to '齲', '颧' to '顴', '权' to '權', '劝' to '勸', '却' to '卻',
            '鹊' to '鵲', '确' to '確', '让' to '讓', '饶' to '饒', '扰' to '擾', '绕' to '繞',
            '热' to '熱', '韧' to '韌', '认' to '認', '纫' to '紉', '荣' to '榮', '绒' to '絨',
            '软' to '軟', '锐' to '銳', '闰' to '閏', '润' to '潤', '洒' to '灑', '萨' to '薩',
            '鳃' to '鰓', '赛' to '賽', '叁' to '三', '伞' to '傘', '丧' to '喪', '骚' to '騷',
            '扫' to '掃', '涩' to '澀', '杀' to '殺', '纱' to '紗', '筛' to '篩', '晒' to '曬',
            '删' to '刪', '闪' to '閃', '陕' to '陝', '赡' to '贍', '缮' to '繕', '伤' to '傷',
            '赏' to '賞', '烧' to '燒', '绍' to '紹', '赊' to '賒', '摄' to '攝', '慑' to '懾',
            '设' to '設', '绅' to '紳', '审' to '審', '婶' to '嬸', '肾' to '腎', '渗' to '滲',
            '声' to '聲', '绳' to '繩', '胜' to '勝', '圣' to '聖', '师' to '師', '狮' to '獅',
            '湿' to '濕', '诗' to '詩', '尸' to '屍', '时' to '時', '蚀' to '蝕', '实' to '實',
            '识' to '識', '驶' to '駛', '势' to '勢', '适' to '適', '释' to '釋', '饰' to '飾',
            '视' to '視', '试' to '試', '寿' to '壽', '兽' to '獸', '枢' to '樞', '输' to '輸',
            '书' to '書', '赎' to '贖', '属' to '屬', '术' to '術', '树' to '樹', '竖' to '豎',
            '数' to '數', '帅' to '帥', '双' to '雙', '谁' to '誰', '税' to '稅', '顺' to '順',
            '说' to '說', '硕' to '碩', '烁' to '爍', '丝' to '絲', '饲' to '飼', '耸' to '聳',
            '怂' to '慫', '颂' to '頌', '讼' to '訟', '诵' to '誦', '擞' to '擻', '苏' to '蘇',
            '诉' to '訴', '肃' to '肅', '虽' to '雖', '随' to '隨', '绥' to '綏', '岁' to '歲',
            '孙' to '孫', '损' to '損', '笋' to '筍', '缩' to '縮', '琐' to '瑣', '锁' to '鎖',
            '獭' to '獺', '挞' to '撻', '抬' to '擡', '态' to '態', '摊' to '攤', '贪' to '貪',
            '瘫' to '癱', '滩' to '灘', '坛' to '壇', '谭' to '譚', '谈' to '談', '叹' to '歎',
            '汤' to '湯', '烫' to '燙', '涛' to '濤', '绦' to '縧', '讨' to '討', '腾' to '騰',
            '誊' to '謄', '锑' to '銻', '题' to '題', '体' to '體', '屉' to '屜', '条' to '條',
            '贴' to '貼', '铁' to '鐵', '厅' to '廳', '听' to '聽', '烃' to '烴', '铜' to '銅',
            '统' to '統', '头' to '頭', '秃' to '禿', '图' to '圖', '涂' to '塗', '团' to '團',
            '颓' to '頹', '蜕' to '蛻', '脱' to '脫', '鸵' to '鴕', '驮' to '馱', '驼' to '駝',
            '椭' to '橢', '洼' to '窪', '袜' to '襪', '弯' to '彎', '湾' to '灣', '顽' to '頑',
            '万' to '萬', '网' to '網', '韦' to '韋', '违' to '違', '围' to '圍', '为' to '爲',
            '潍' to '濰', '维' to '維', '苇' to '葦', '伟' to '偉', '伪' to '僞', '纬' to '緯',
            '谓' to '謂', '卫' to '衛', '温' to '溫', '闻' to '聞', '纹' to '紋', '稳' to '穩',
            '问' to '問', '瓮' to '甕', '挝' to '撾', '蜗' to '蝸', '涡' to '渦', '窝' to '窩',
            '卧' to '臥', '呜' to '嗚', '钨' to '鎢', '乌' to '烏', '污' to '汙', '诬' to '誣',
            '无' to '無', '芜' to '蕪', '吴' to '吳', '坞' to '塢', '雾' to '霧', '务' to '務',
            '误' to '誤', '锡' to '錫', '牺' to '犧', '袭' to '襲', '习' to '習', '铣' to '銑',
            '戏' to '戲', '细' to '細', '虾' to '蝦', '辖' to '轄', '峡' to '峽', '侠' to '俠',
            '狭' to '狹', '厦' to '廈', '吓' to '嚇', '锨' to '鍁', '鲜' to '鮮', '纤' to '纖',
            '咸' to '鹹', '贤' to '賢', '衔' to '銜', '闲' to '閑', '显' to '顯', '险' to '險',
            '现' to '現', '献' to '獻', '县' to '縣', '馅' to '餡', '羡' to '羨', '宪' to '憲',
            '线' to '線', '厢' to '廂', '镶' to '鑲', '乡' to '鄉', '详' to '詳', '响' to '響',
            '项' to '項', '萧' to '蕭', '嚣' to '囂', '销' to '銷', '晓' to '曉', '啸' to '嘯',
            '蝎' to '蠍', '协' to '協', '挟' to '挾', '携' to '攜', '胁' to '脅', '谐' to '諧',
            '写' to '寫', '泻' to '瀉', '谢' to '謝', '锌' to '鋅', '衅' to '釁', '兴' to '興',
            '汹' to '洶', '锈' to '鏽', '绣' to '繡', '虚' to '虛', '嘘' to '噓', '须' to '須',
            '许' to '許', '叙' to '敘', '绪' to '緒', '续' to '續', '轩' to '軒', '悬' to '懸',
            '选' to '選', '癣' to '癬', '绚' to '絢', '学' to '學', '勋' to '勳', '询' to '詢',
            '寻' to '尋', '驯' to '馴', '训' to '訓', '讯' to '訊', '逊' to '遜', '压' to '壓',
            '鸦' to '鴉', '鸭' to '鴨', '哑' to '啞', '亚' to '亞', '讶' to '訝', '阉' to '閹',
            '烟' to '煙', '盐' to '鹽', '严' to '嚴', '颜' to '顔', '阎' to '閻', '艳' to '豔',
            '厌' to '厭', '砚' to '硯', '彦' to '彥', '谚' to '諺', '验' to '驗', '鸯' to '鴦',
            '杨' to '楊', '扬' to '揚', '疡' to '瘍', '阳' to '陽', '痒' to '癢', '养' to '養',
            '样' to '樣', '瑶' to '瑤', '摇' to '搖', '尧' to '堯', '遥' to '遙', '窑' to '窯',
            '谣' to '謠', '药' to '藥', '爷' to '爺', '页' to '頁', '业' to '業', '叶' to '葉',
            '医' to '醫', '铱' to '銥', '颐' to '頤', '遗' to '遺', '仪' to '儀', '彝' to '彜',
            '蚁' to '蟻', '艺' to '藝', '亿' to '億', '忆' to '憶', '义' to '義', '诣' to '詣',
            '议' to '議', '谊' to '誼', '译' to '譯', '异' to '異', '绎' to '繹', '荫' to '蔭',
            '阴' to '陰', '银' to '銀', '饮' to '飲', '隐' to '隱', '樱' to '櫻', '婴' to '嬰',
            '鹰' to '鷹', '应' to '應', '缨' to '纓', '莹' to '瑩', '萤' to '螢', '营' to '營',
            '荧' to '熒', '蝇' to '蠅', '赢' to '贏', '颖' to '穎', '哟' to '喲', '拥' to '擁',
            '佣' to '傭', '痈' to '癰', '踊' to '踴', '咏' to '詠', '涌' to '湧', '优' to '優',
            '忧' to '憂', '邮' to '郵', '铀' to '鈾', '犹' to '猶', '游' to '遊', '诱' to '誘',
            '舆' to '輿', '鱼' to '魚', '渔' to '漁', '娱' to '娛', '与' to '與', '屿' to '嶼',
            '语' to '語', '吁' to '籲', '御' to '禦', '狱' to '獄', '誉' to '譽', '预' to '預',
            '驭' to '馭', '鸳' to '鴛', '渊' to '淵', '辕' to '轅', '园' to '園', '员' to '員',
            '圆' to '圓', '缘' to '緣', '远' to '遠', '愿' to '願', '约' to '約', '跃' to '躍',
            '钥' to '鑰', '岳' to '嶽', '粤' to '粵', '悦' to '悅', '阅' to '閱', '云' to '雲',
            '郧' to '鄖', '匀' to '勻', '陨' to '隕', '运' to '運', '蕴' to '蘊', '酝' to '醞',
            '晕' to '暈', '韵' to '韻', '杂' to '雜', '灾' to '災', '载' to '載', '攒' to '攢',
            '暂' to '暫', '赞' to '贊', '赃' to '贓', '脏' to '髒', '凿' to '鑿', '枣' to '棗',
            '灶' to '竈', '责' to '責', '择' to '擇', '则' to '則', '泽' to '澤', '贼' to '賊',
            '赠' to '贈', '扎' to '紮', '札' to '劄', '轧' to '軋', '铡' to '鍘', '闸' to '閘',
            '栅' to '柵', '诈' to '詐', '斋' to '齋', '债' to '債', '毡' to '氈', '盏' to '盞',
            '斩' to '斬', '辗' to '輾', '崭' to '嶄', '栈' to '棧', '战' to '戰', '绽' to '綻',
            '张' to '張', '涨' to '漲', '帐' to '帳', '账' to '賬', '胀' to '脹', '赵' to '趙',
            '蛰' to '蟄', '辙' to '轍', '锗' to '鍺', '这' to '這', '贞' to '貞', '针' to '針',
            '侦' to '偵', '诊' to '診', '镇' to '鎮', '阵' to '陣', '挣' to '掙', '睁' to '睜',
            '狰' to '猙', '争' to '爭', '帧' to '幀', '郑' to '鄭', '证' to '證', '织' to '織',
            '职' to '職', '执' to '執', '纸' to '紙', '挚' to '摯', '掷' to '擲', '帜' to '幟',
            '质' to '質', '滞' to '滯', '钟' to '鍾', '终' to '終', '种' to '種', '肿' to '腫',
            '众' to '衆', '诌' to '謅', '轴' to '軸', '皱' to '皺', '昼' to '晝', '骤' to '驟',
            '猪' to '豬', '诸' to '諸', '诛' to '誅', '烛' to '燭', '瞩' to '矚', '嘱' to '囑',
            '贮' to '貯', '铸' to '鑄', '筑' to '築', '驻' to '駐', '专' to '專', '砖' to '磚',
            '转' to '轉', '赚' to '賺', '桩' to '樁', '庄' to '莊', '装' to '裝', '妆' to '妝',
            '壮' to '壯', '状' to '狀', '锥' to '錐', '赘' to '贅', '坠' to '墜', '缀' to '綴',
            '谆' to '諄', '着' to '著', '浊' to '濁', '兹' to '茲', '资' to '資', '渍' to '漬',
            '踪' to '蹤', '综' to '綜', '总' to '總', '纵' to '縱', '邹' to '鄒', '诅' to '詛',
            '组' to '組', '钻' to '鑽', '为' to '為',

        )
    }
}

/** Shared by both sources: the site's url shapes and the text cleanups. */
internal val NOVEL_ID_REGEX = Regex("/novel/(\\d+)\\.html")
internal val CHAPTER_IDS_REGEX = Regex("/novel/(\\d+)/(\\d+)(?:_(\\d+))?\\.html")
internal val NEWLINE_REGEX = Regex("(?:\\r?\\n)+")

internal fun String.toHalfWidthDigits() = map { if (it in '０'..'９') it - 65248 else it }.joinToString("")
internal fun String.formatText(c: String) = replace(NEWLINE_REGEX, c).trim()
internal fun String.convert(switch: Boolean) = if (switch) map { BiliNovel.TRADITIONAL_CHARACTER_MAP.getOrDefault(it, it) }.joinToString("") else this
