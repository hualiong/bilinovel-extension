package eu.kanade.tachiyomi.extension.zh.bilinovel

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

fun buildWebFilterList() = FilterList(
    Filter.Header("筛选条件（搜索关键字时无效）"),
    WebRegionFilter(),
    WebThemeFilter(),
    WebSortFilter(),
    WebAnimeFilter(),
    WebCountFilter(),
    WebStatusFilter(),
)

class WebRegionFilter : Filter.Select<String>("文库地区", arrayOf("不限", "日本轻小说", "华文轻小说", "Web轻小说", "韩国轻小说")) {
    override fun toString() = arrayOf("0", "1", "2", "3", "5")[state]
}

class WebThemeFilter : Filter.Select<String>("作品主题", THEME_LABELS) {
    override fun toString(): String = arrayOf(
        "0", "64", "48", "63", "27", "26", "47", "15", "61", "222",
        "231", "219", "96", "67", "31", "198", "217", "225", "18",
        "256", "223", "227", "189", "68", "56", "201", "55", "185",
        "229", "199", "131", "241", "191", "60", "226", "246", "135",
        "261", "221", "220", "239", "124", "98", "97", "205", "248",
        "228", "211", "245", "249", "233", "132", "28", "180", "224",
        "328", "304", "254", "146", "263", "333",
    )[state]
}

class WebSortFilter : Filter.Select<String>("排序方式", SORT_LABELS) {
    override fun toString(): String = arrayOf(
        "lastupdate", "monthvisit", "weekvote", "monthvote", "weekflower",
        "monthflower", "words", "goodnum", "weekvisit", "postdate",
    )[state]
}

class WebAnimeFilter : Filter.Select<String>("是否动画", arrayOf("不限", "已动画化", "未动画化")) {
    override fun toString() = arrayOf("0", "1", "2")[state]
}

class WebCountFilter :
    Filter.Select<String>(
        "作品字数",
        arrayOf("不限", "30万以下", "30-50万", "50-100万", "100-200万", "200万以上"),
    ) {
    override fun toString() = arrayOf("0", "1", "2", "3", "4", "5")[state]
}

class WebStatusFilter :
    Filter.Select<String>(
        "写作状态",
        arrayOf("不限", "新书上传", "情节展开", "精彩纷呈", "接近尾声", "已经完本"),
    ) {
    override fun toString() = arrayOf("0", "1", "2", "3", "4", "5")[state]
}
