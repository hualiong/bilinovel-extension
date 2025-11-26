package eu.kanade.tachiyomi.extension.zh.bilinovel

import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.floor

class BiliNovel : HttpSource(), ConfigurableSource {
    override val baseUrl = "https://www.bilinovel.com"
    override val lang = "zh"
    override val name = "哔哩轻小说"
    override val supportsLatest = true

    private val pref by getPreferencesLazy()

    override val client = super.client.newBuilder()
        .addInterceptor(HtmlInterceptor(baseUrl, pref))
        .rateLimit(10, 10).addNetworkInterceptor(NovelInterceptor()).build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", "zh")
        .add("Accept", "*/*")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        preferencesInternal(screen.context, pref).forEach(screen::addPreference)
    }

    // Customize

    companion object {
        val DATE_REGEX = Regex("\\d{4}-\\d{1,2}-\\d{1,2}")
        val PAGE_REGEX = Regex("第(\\d+)/(\\d+)页")
        val MANGA_ID_REGEX = Regex("/novel/(\\d+)\\.html")
        val CHAPTER_ID_REGEX = Regex("/novel/\\d+/(\\d+)(?:_\\d+)?\\.html")
        val PAGE_SIZE_REGEX = Regex("（\\d+/(\\d+)）")
        val EXPRESSION_REGEX = Regex("Number.*?;")
        val SALT_REGEX = Regex("(?<![a-zA-Z0-9_])-?0x[0-9a-fA-F]+(?:[+*\\-]-?0x[0-9a-fA-F]+)+")
        val URL_REGEX = Regex("/themes/zhmb/js/chapterlog\\.js\\?v[^\"]+")
        val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.CHINESE)
        val TRADITIONAL_CHARACTER_MAP = mapOf<Char, Char>(
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

    private var salt: Pair<Int, Int>? = null
    private val SManga.id get() = MANGA_ID_REGEX.find(url)!!.groups[1]!!.value
    private fun String.toHalfWidthDigits(): String {
        return this.map { if (it in '０'..'９') it - 65248 else it }.joinToString("")
    }

    private fun String.convert(
        switch: Boolean = pref.getBoolean(
            PREF_DISPLAY_TRADITIONAL,
            false,
        ),
    ): String {
        return if (switch) {
            this.map { c -> TRADITIONAL_CHARACTER_MAP[c] ?: c }.joinToString("")
        } else {
            this
        }
    }

    private fun parseSalt(url: String) {
        var s1 = 0
        var s2 = 0
        val resp = client.newCall(GET(url, headers)).execute()
        EXPRESSION_REGEX.findAll(resp.body.string()).forEach { m ->
            SALT_REGEX.findAll(m.value).takeIf { it.count() == 2 }?.let {
                s1 = calculate(it.first().value)
                s2 = calculate(it.last().value)
            }
        }
        salt = Pair(s1, s2).apply {
            val version = url.substringAfter("?")
            Log.v("BiliNovel", "chapterlog: $version, salt1: $first, salt2: $second")
        }
    }

    private fun calculate(expression: String): Int {
        var newExpression = expression.replace(" ", "")
        val hexPattern = Regex("(-?)0x([0-9a-fA-F]+)")
        newExpression = hexPattern.replace(newExpression) { matchResult ->
            val sign = matchResult.groupValues[1]
            val hexStr = matchResult.groupValues[2]
            var num = hexStr.toLong(16)
            if (sign == "-") {
                num = -num
            }
            num.toString()
        }
        val tokenRegex = Regex("-?\\d+|[+*/-]")
        val tokens = tokenRegex.findAll(newExpression).map { it.value }.toList()
        if (tokens.isEmpty()) {
            return 0
        }
        val intermediateTokens = mutableListOf<String>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token == "*" || token == "/") {
                if (intermediateTokens.isEmpty()) {
                    throw IllegalArgumentException("Invalid expression: operator without left operand")
                }
                val left = intermediateTokens.removeAt(intermediateTokens.size - 1).toInt()
                if (index + 1 >= tokens.size) {
                    throw IllegalArgumentException("Invalid expression: operator without right operand")
                }
                val right = tokens[index + 1].toInt()
                if (token == "/" && right == 0) {
                    throw ArithmeticException("Division by zero")
                }
                val result = if (token == "*") left * right else left / right
                intermediateTokens.add(result.toString())
                index += 2
            } else {
                intermediateTokens.add(token)
                index++
            }
        }
        if (intermediateTokens.isEmpty()) {
            return 0
        }
        var result = intermediateTokens[0].toInt()
        index = 1
        while (index < intermediateTokens.size) {
            val op = intermediateTokens[index]
            if (op == "+" || op == "-") {
                if (index + 1 >= intermediateTokens.size) {
                    throw IllegalArgumentException("Invalid expression: operator without right operand")
                }
                val nextNum = intermediateTokens[index + 1].toInt()
                if (op == "+") {
                    result += nextNum
                } else {
                    result -= nextNum
                }
                index += 2
            } else {
                throw IllegalArgumentException("Invalid operator: $op")
            }
        }
        return result
    }

    private fun hasNextPage(doc: Document, size: Int): Boolean {
        val url = doc.location()
        return when {
            url.contains("wenku") -> {
                val total = doc.selectFirst("#pagelink > .last")!!.text().toInt()
                val cur = doc.selectFirst("#pagelink > strong")!!.text().toInt()
                cur < total
            }

            url.contains("search") -> {
                val find = PAGE_REGEX.find(doc.selectFirst("#pagelink > span")!!.text())!!
                find.groups[1]!!.value.toInt() < find.groups[1]!!.value.toInt()
            }

            else -> size == 50
        }
    }

    private fun getChapterUrlByContext(i: Int, els: Elements) = when (i) {
        0 -> "${els[1].attr("href")}#prev"
        else -> "${els[i - 1].attr("href")}#next"
    }

    private fun sort(content: Element, chapterId: Int): String {
        // 1. 计算种子
        val seed = chapterId * salt!!.first + salt!!.second

        // 2. 获取所有子节点（包括文本节点等）
        val childNodes = content.children().toMutableList().also {
            it.removeIf { e ->
                e.tagName() != "img" && (e.tagName() != "p" || e.text().trim().isBlank())
            }
            it.forEachIndexed { i, e ->
                if (e.tagName() == "img" && e.hasAttr("data-src")) {
                    it[i] = e.attr("src", e.attr("data-src"))
                }
            }
        }

        // 3. 过滤出有效的<p>元素节点
        val paragraphs = childNodes.filter { it.tagName() == "p" }.toMutableList()

        // 5. 创建排列数组
        val n = paragraphs.size
        val permutation = mutableListOf<Int>().apply {
            // 前20个保持原顺序
            addAll(0 until minOf(20, n))
            // 处理超过20的部分
            if (n > 20) {
                val after20 = (20 until n).toMutableList()
                var num = seed.toLong()
                for (i in after20.size - 1 downTo 1) {
                    num = (num * 9302L + 49397L) % 233280L
                    val j = floor((num / 233280.0) * (i + 1)).toInt()
                    after20[j] = after20[i].also { after20[i] = after20[j] }
                }
                addAll(after20)
            }
        }

        // 6. 创建重排序后的段落数组
        val shuffled = arrayOfNulls<Element>(n).apply {
            for (i in 0 until n) {
                this[permutation[i]] = paragraphs[i].apply {
                    text("\u00A0\u00A0\u00A0\u00A0" + text())
                }
            }
        }.map { it!! } // 转换为非空列表

        // 7. 替换原始节点中的<p>元素
        var paraIndex = 0
        childNodes.forEachIndexed { i, e ->
            if (e.tagName() == "p") {
                childNodes[i] = shuffled[paraIndex++]
            }
        }

        // 8. 清空并重新添加处理后的节点
        content.html("")
        content.appendChildren(childNodes)

        // 9. 返回最终HTML
        return content.html()
    }

    // Popular Page

    override fun popularMangaRequest(page: Int): Request {
        val suffix = pref.getString(PREF_POPULAR_DISPLAY, "/top/weekvisit/%d.html")!!
        return GET(baseUrl + String.format(suffix, page), headers)
    }

    override fun popularMangaParse(response: Response) = response.asJsoup().let { doc ->
        val mangas = doc.select(".book-layout").map {
            SManga.create().apply {
                setUrlWithoutDomain(it.absUrl("href"))
                val img = it.selectFirst("img")!!
                thumbnail_url = img.absUrl("data-src")
                title = img.attr("alt").convert()
            }
        }
        MangasPage(mangas, hasNextPage(doc, mangas.size))
    }

    // Latest Page

    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/top/lastupdate/$page.html", headers)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // Search Page

    override fun getFilterList() = buildFilterList()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
        if (query.isNotBlank()) {
            url.addPathSegment("search").addPathSegment("${query}_$page.html")
        } else {
            url.addPathSegment("wenku")
                .addPathSegment("${filters[3]}_${filters[2]}_${filters[6]}_${filters[4]}_${filters[1]}_0_0_${filters[5]}_${page}_0.html")
        }
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.pathSegments.contains("novel")) {
            return MangasPage(listOf(mangaDetailsParse(response)), false)
        }
        return popularMangaParse(response)
    }

    // Manga Detail Page

    override fun mangaDetailsParse(response: Response) = SManga.create().apply {
        val switch = pref.getBoolean(PREF_DISPLAY_TRADITIONAL, false)
        val doc = response.asJsoup()
        val meta = doc.select(".book-meta")[1].text().convert(switch).split("|")
        val tags = doc.select(".tag-small").map { it.text().convert(switch) }
        val bkname = doc.selectFirst(".bkname-body")?.let {
            "**別名**：${it.text()}\n\n---\n\n"
        } ?: ""
        val desc = doc.selectFirst("#bookSummary > content")?.wholeText()?.trim()
        setUrlWithoutDomain(doc.location())
        title = doc.selectFirst(".book-title")!!.text().convert(switch)
        thumbnail_url = doc.selectFirst(".book-cover")!!.attr("src")
        description = bkname.convert(switch) + desc?.convert(switch)
        author = doc.selectFirst(".authorname")?.text()?.convert(switch)
        status = when (meta.getOrNull(1)) {
            "连载", "連載" -> SManga.ONGOING
            "完结", "完結" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        genre = (tags + meta.getOrElse(2) { "" }).joinToString()
        initialized = true
    }

    // Catalog Page

    override fun chapterListRequest(manga: SManga) =
        GET("$baseUrl/novel/${manga.id}/catalog", headers)

    override fun chapterListParse(response: Response) = response.asJsoup().let {
        val switch = pref.getBoolean(PREF_DISPLAY_TRADITIONAL, false)
        val info = it.selectFirst(".chapter-sub-title")!!.text()
        val date = DATE_FORMAT.tryParse(DATE_REGEX.find(info)?.value)
        it.select(".catalog-volume").flatMap { v ->
            val chapterBar = v.selectFirst(".chapter-bar")!!.text().toHalfWidthDigits()
            val chapters = v.select(".chapter-li-a")
            chapters.mapIndexed { i, e ->
                val url = e.absUrl("href").takeUnless("javascript:cid(1)"::equals)
                SChapter.create().apply {
                    name = e.text().toHalfWidthDigits().convert(switch)
                    date_upload = date
                    scanlator = chapterBar.convert(switch)
                    setUrlWithoutDomain(url ?: getChapterUrlByContext(i, chapters))
                }
            }
        }.reversed()
    }

    // Manga View Page

    override fun pageListRequest(chapter: SChapter) =
        GET(baseUrl + chapter.url.replace(".", "_2."), headers)

    override fun pageListParse(response: Response) = response.asJsoup().let { doc ->
        doc.selectFirst("#acontent > .center-note")?.run { throw Exception(text()) }
        val size = PAGE_SIZE_REGEX.find(doc.selectFirst("#atitle")!!.text())!!.groups[1]!!.value
        val prefix = doc.location().substringBeforeLast("_")
        List(size.toInt().takeUnless { it == 0 } ?: 1) { i ->
            Page(i, prefix + "${if (i > 0) "_${i + 1}" else ""}.html")
        }
    }

    // Image

    override fun imageUrlParse(response: Response) = response.asJsoup().let { doc ->
        if (salt == null) parseSalt(baseUrl + URL_REGEX.find(doc.body().toString())!!.value)
        val switch = pref.getBoolean(PREF_DISPLAY_TRADITIONAL, false)
        val title = doc.selectFirst("#atitle")?.html()?.takeIf { it.indexOf("/") < 0 } ?: ""
        val content = doc.selectFirst("#acontent")!!
        val chapterId = CHAPTER_ID_REGEX.find(doc.location())!!.groups[1]!!.value.toInt()
        HtmlInterceptorHelper.createUrl(
            title.convert(switch),
            sort(content, chapterId).convert(switch),
        )
    }
}
