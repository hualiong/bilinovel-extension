package eu.kanade.tachiyomi.extension.zh.bilinovel

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

internal val THEME_LABELS = arrayOf(
    "不限", "恋爱", "后宫", "校园", "百合", "转生", "异世界",
    "奇幻", "冒险", "欢乐向", "女性视角", "龙傲天", "魔法", "青春",
    "性转", "病娇", "妹妹", "青梅竹马", "战斗", "NTR", "人外",
    "大小姐", "黑暗", "悬疑", "科幻", "伪娘", "战争", "萝莉",
    "复仇", "斗智", "异能", "猎奇", "轻文学", "职场", "经营", "JK",
    "机战", "女儿", "末日", "犯罪", "旅行", "惊悚", "治愈",
    "推理", "日本文学", "游戏", "耽美", "美食", "群像", "大逃杀",
    "音乐", "格斗", "热血", "温馨", "脑洞", "恶役", "JC",
    "间谍", "竞技", "宅文化", "同人",
)

internal val SORT_LABELS = arrayOf(
    "最近更新", "月点击", "周推荐", "月推荐", "周鲜花",
    "月鲜花", "字数", "收藏数", "周点击", "最新入库",
)

fun buildFilterList() = FilterList(
    Filter.Header("筛选条件（搜索关键字时无效）"),
    RegionFilter(),
    ThemeFilter(),
    SortFilter(),
    AnimeFilter(),
    CountFilter(),
    StatusFilter(),
)

class RegionFilter :
    Filter.Select<String>(
        "文库地区",
        arrayOf("不限", "日本轻小说", "华文轻小说", "Web轻小说", "韩国轻小说"),
    ) {
    /** The client API calls this filterRGroup; 0 is left out, which means every group. */
    override fun toString() = arrayOf("", "1", "2", "3", "5")[state]
}

class ThemeFilter : Filter.Select<String>("作品主题", THEME_LABELS) {
    /** The API filters by tag name, so the label itself is what the request carries. */
    override fun toString() = if (state == 0) "" else values[state]
}

class SortFilter : Filter.Select<String>("排序方式", SORT_LABELS) {
    override fun toString() = arrayOf(
        "lastupdate", "monthvisit", "weekvote", "monthvote", "weekflower",
        "monthflower", "words", "goodnum", "weekvisit", "postdate",
    )[state]
}

class AnimeFilter : Filter.Select<String>("是否动画", arrayOf("不限", "已动画化", "未动画化")) {
    override fun toString() = arrayOf("", "1", "0")[state]
}

class CountFilter :
    Filter.Select<String>(
        "作品字数",
        arrayOf("不限", "30万以下", "30-50万", "50-100万", "100-200万", "200万以上"),
    ) {
    val minWords: Int? get() = arrayOf(null, null, 300_000, 500_000, 1_000_000, 2_000_000)[state]
    val maxWords: Int? get() = arrayOf(null, 300_000, 500_000, 1_000_000, 2_000_000, null)[state]
}

class StatusFilter : Filter.Select<String>("写作状态", arrayOf("不限", "连载中", "已经完本")) {
    /** filterFullFlag: 0 连载中, 1 已完结. */
    override fun toString() = arrayOf("", "0", "1")[state]
}
