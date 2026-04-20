package eu.kanade.tachiyomi.extension.zh.bilinovel

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat

const val PREF_POPULAR_DISPLAY = "POPULAR_DISPLAY"
const val PREF_SCREEN_COLORS = "SCREEN_COLORS"
const val PREF_SCREEN_FONT_SIZE = "SCREEN_FONT_SIZE"
const val PREF_DISPLAY_TRADITIONAL = "DISPLAY_TRADITIONAL"
const val PREF_DARK_MODE = "DARK_MODE"
const val PREF_RATE_LIMIT = "RATE_LIMIT"
const val PREF_AUTO_BOOKMARK = "AUTO_BOOKMARK"
const val PREF_NOTICE = "NOTICE"
const val PREF_LOAD_ALL_IMAGES = "LOAD_ALL_IMAGES"
const val PREF_HTTP = "HTTP"

val RGB_REGEX = Regex("^#[0-9A-F]{6} #[0-9A-F]{6}$", RegexOption.IGNORE_CASE)
val FONT_SIZE_REGEX = Regex("^(?:\\d+|\\d+\\.\\d+) (?:\\d+|\\d+\\.\\d+)$")
val RATE_LIMIT_REGEX = Regex("^\\d+/\\d+$")

fun preferencesInternal(context: Context, pref: SharedPreferences): Array<Preference> {
    return arrayOf(
        ListPreference(context).apply {
            key = PREF_POPULAR_DISPLAY
            title = "热门显示内容"
            summary = "%s"
            entries = arrayOf(
                "月点击榜",
                "周点击榜",
                "月推荐榜",
                "周推荐榜",
                "月鲜花榜",
                "周鲜花榜",
                "月鸡蛋榜",
                "周鸡蛋榜",
                "最新入库",
                "收藏榜",
                "新书榜",
            )
            entryValues = arrayOf(
                "/top/monthvisit/%d.html",
                "/top/weekvisit/%d.html",
                "/top/monthvote/%d.html",
                "/top/weekvote/%d.html",
                "/top/monthflower/%d.html",
                "/top/weekflower/%d.html",
                "/top/monthegg/%d.html",
                "/top/weekegg/%d.html",
                "/top/postdate/%d.html",
                "/top/goodnum/%d.html",
                "/top/newhot/%d.html",
            )
            setDefaultValue("/top/weekvisit/%d.html")
        },
        EditTextPreference(context).apply {
            key = PREF_SCREEN_COLORS
            title = "阅读页颜色设置" // "背景色：#FAFAF8 | 文本色：#000000"
            summary = pref.getString(key, "#FAFAF8 #000000")!!.split(' ').let {
                "背景色：${it[0]}   |   文本色：${it[1]}"
            }
            dialogMessage = "请用空格隔开输入两个十六进制颜色代码，左边为背景色，右边为文本色。\n默认值：#FAFAF8 #000000"
            setDefaultValue("#FAFAF8 #000000")
            setOnPreferenceChangeListener { _, newValue ->
                if (RGB_REGEX.matches(newValue as String)) {
                    summary = newValue.split(' ').let { "背景色：${it[0]}   |   文本色：${it[1]}" }
                    Toast.makeText(context, "已加载章节需清除章节缓存后生效", Toast.LENGTH_LONG)
                        .show()
                    true
                } else {
                    Toast.makeText(
                        context,
                        "“$newValue” 不是符合格式的十六进制颜色代码！",
                        Toast.LENGTH_LONG,
                    ).show()
                    false
                }
            }
        },
        EditTextPreference(context).apply {
            key = PREF_SCREEN_FONT_SIZE
            title = "阅读页字号设置"
            summary = pref.getString(key, "52 30")!!.split(' ').let {
                "标题大小：${it[0]}   |   正文大小：${it[1]}"
            }
            dialogMessage = "请用空格隔开输入两个大于 0 且可带小数的字号，左边为标题大小，右边为正文大小。\n默认值：52 30"
            setDefaultValue("52 30")
            setOnPreferenceChangeListener { _, newValue ->
                if (FONT_SIZE_REGEX.matches(newValue as String)) {
                    summary = newValue.split(' ').let { "标题大小：${it[0]}   |   正文大小：${it[1]}" }
                    Toast.makeText(context, "已加载章节需清除章节缓存后生效", Toast.LENGTH_LONG)
                        .show()
                    true
                } else {
                    Toast.makeText(context, "非法字号！请检查输入格式", Toast.LENGTH_LONG).show()
                    false
                }
            }
        },
        EditTextPreference(context).apply {
            key = PREF_RATE_LIMIT
            title = "请求速率限制"
            summary = pref.getString(key, "10/10")!!.split("/")
                .let { "每 ${it[1]} 秒内允许 ${it[0]} 个请求通过" }
            dialogMessage = "按照 */* 的格式输入，10/2 则代表每 2 秒内允许 10 个请求通过，默认为 10/10"
            setDefaultValue("10/10")
            setOnPreferenceChangeListener { _, newValue ->
                if (RATE_LIMIT_REGEX.matches(newValue as String)) {
                    val split = newValue.split("/")
                    summary = "每 ${split[1]} 秒内允许 ${split[0]} 个请求通过"
                    Toast.makeText(context, "重启应用后生效", Toast.LENGTH_LONG).show()
                    true
                } else {
                    Toast.makeText(context, "格式错误！请检查输入", Toast.LENGTH_LONG).show()
                    false
                }
            }
        },
        SwitchPreferenceCompat(context).apply {
            key = PREF_DARK_MODE
            title = "深色模式"
            summary = "阅读页面的样式将强制使用黑底白字"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(context, "已加载章节需清除章节缓存后生效", Toast.LENGTH_LONG).show()
                true
            }
        },
        SwitchPreferenceCompat(context).apply {
            key = PREF_NOTICE
            title = "显示作品公告"
            summary = "如果有，则在简介里显示该作品的公告通知"
            setDefaultValue(true)
        },
        SwitchPreferenceCompat(context).apply {
            key = PREF_LOAD_ALL_IMAGES
            title = "确保加载所有插图"
            summary = "一旦有插图加载失败，不再用空白图占位，而是可以进行重试，直到加载完所有插图"
            setDefaultValue(false)
        },
        SwitchPreferenceCompat(context).apply {
            key = PREF_HTTP
            title = "使用旧版插图请求方式"
            summary = "如果频繁遇到大量插图加载失败或一半模糊，可尝试开启该项（纯网络问题的话，那就没啥用了）"
            setDefaultValue(false)
        },
        SwitchPreferenceCompat(context).apply {
            key = PREF_AUTO_BOOKMARK
            title = "自动标记书签（源站功能）"
            summary = "阅读任一章节时，自动调用源站的“书签”功能标记该章节（不建议将章节下载后阅读，会导致超前标记）\n注：该功能需在 WebView 中登录，否则将自动关闭"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newVal ->
                if (newVal as Boolean) {
                    Toast.makeText(context, "已加载章节需清除章节缓存后生效", Toast.LENGTH_LONG).show()
                }
                true
            }
        },
        SwitchPreferenceCompat(context).apply {
            key = PREF_DISPLAY_TRADITIONAL
            title = "显示繁体"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(context, "已加载章节需清除章节缓存后生效", Toast.LENGTH_LONG).show()
                true
            }
        },
    )
}
