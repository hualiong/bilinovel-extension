package eu.kanade.tachiyomi.extension.zh.huanmengacg

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

fun buildFilterList() = FilterList(
    Filter.Header("筛选条件（搜索关键字时无效）"),
    CategoryFilter(),
    ThemeFilter(),
    StatusFilter(),
    CountFilter(),
    SortFilter(),
)

class CategoryFilter : Filter.Select<String>(
    "分类",
    arrayOf(
        "全部", "其他文库", "游戏剧本", "少女文库", "讲谈社", "小学馆",
        "集英社", "一迅社", "HJ文库", "GA文库", "Fami通文库", "MF文库J",
        "角川文库", "电击文库", "富士见文库",
    ),
) {
    override fun toString() = arrayOf(
        "", "60", "61", "59", "58", "57", "56", "55",
        "54", "53", "52", "51", "50", "48", "49",
    )[state]
}

class ThemeFilter : Filter.Select<String>(
    "题材",
    arrayOf(
        "全部", "校园", "青春", "恋爱", "治愈", "群像", "竞技", "音乐", "美食",
        "旅行", "欢乐向", "经营", "职场", "斗智", "脑洞", "宅文化", "穿越", "奇幻",
        "魔法", "异能", "战斗", "科幻", "机战", "战争", "冒险", "龙傲天", "悬疑",
        "犯罪", "复仇", "黑暗", "猎奇", "惊悚", "间谍", "末日", "游戏", "大逃杀",
        "青梅竹马", "妹妹", "女儿", "JK", "JC", "大小姐", "性转", "伪娘", "人外",
        "后宫", "百合", "耽美", "NTR", "女性视角",
    ),
) {
    override fun toString(): String {
        return arrayOf(
            "", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12",
            "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23",
            "24", "25", "26", "27", "28", "29", "30", "31", "32", "33", "34",
            "35", "36", "37", "38", "39", "40", "41", "42", "43", "44", "45",
            "46", "47", "48", "49",
        )[state]
    }
}

class SortFilter : Filter.Select<String>("筛选", arrayOf("最具人气", "最新更新", "最多收藏")) {
    override fun toString(): String {
        return arrayOf("", "addtime", "shits")[state]
    }
}

class CountFilter : Filter.Select<String>(
    "字数",
    arrayOf("全部", "30万字以下", "30-50万字", "50-100万字", "100-200万字", "200万字以上"),
) {
    override fun toString() = arrayOf("", "1", "2", "3", "4", "5")[state]
}

class StatusFilter : Filter.Select<String>("进度", arrayOf("全部", "连载", "完结")) {
    override fun toString() = arrayOf("", "1", "2")[state]
}
