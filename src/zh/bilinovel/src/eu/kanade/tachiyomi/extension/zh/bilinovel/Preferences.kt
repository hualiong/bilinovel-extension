package eu.kanade.tachiyomi.extension.zh.bilinovel

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat

const val PREF_POPULAR_DISPLAY = "POPULAR_DISPLAY"
const val PREF_SCREEN_BG_COLOR = "SCREEN_BG_COLOR"
const val PREF_SCREEN_FONT_COLOR = "FONT_COLOR"
const val PREF_HEADING_FONT_SIZE = "HEADING_FONT_SIZE"
const val PREF_BODY_FONT_SIZE = "BODY_FONT_SIZE"
const val PREF_DISPLAY_TRADITIONAL = "DISPLAY_TRADITIONAL"
const val PREF_DARK_MODE = "DARK_MODE"

val RGB_REGEX = Regex("^#[0-9A-F]{6}$", RegexOption.IGNORE_CASE)
val FONT_SIZE_REGEX = Regex("^(?:\\d+|\\d+\\.\\d+)$")

fun preferencesInternal(context: Context, pref: SharedPreferences): Array<Preference> {
    return arrayOf(
        ListPreference(context).apply {
            key = PREF_POPULAR_DISPLAY
            title = "热门漫画显示内容"
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
            key = PREF_SCREEN_BG_COLOR
            title = "阅读页背景颜色"
            summary = pref.getString(key, "#FAFAF8")
            dialogMessage = "请输入正确的十六进制颜色代码，如：#FAFAF8"
            setDefaultValue("#FAFAF8")
            setOnPreferenceChangeListener { _, newValue ->
                if (RGB_REGEX.matches(newValue as String)) {
                    summary = newValue
                    Toast.makeText(context, "已加载章节需清除章节缓存后生效", Toast.LENGTH_LONG).show()
                    true
                } else {
                    Toast.makeText(
                        context,
                        "“$newValue” 不是一个正确的十六进制颜色代码！",
                        Toast.LENGTH_LONG,
                    ).show()
                    false
                }
            }
        },
        EditTextPreference(context).apply {
            key = PREF_SCREEN_FONT_COLOR
            title = "阅读页字体颜色"
            summary = pref.getString(key, "#000000")
            dialogMessage = "请输入正确的十六进制颜色代码，否则不生效，如：#000000"
            setDefaultValue("#000000")
            setOnPreferenceChangeListener { _, newValue ->
                if (RGB_REGEX.matches(newValue as String)) {
                    summary = newValue
                    Toast.makeText(context, "已加载章节需清除章节缓存后生效", Toast.LENGTH_LONG).show()
                    true
                } else {
                    Toast.makeText(
                        context,
                        "“$newValue” 不是一个正确的十六进制颜色代码！",
                        Toast.LENGTH_LONG,
                    ).show()
                    false
                }
            }
        },
        EditTextPreference(context).apply {
            key = PREF_HEADING_FONT_SIZE
            title = "阅读页标题大小"
            summary = pref.getString(key, "52")
            dialogMessage = "请输入一个大于0的字号，可以带小数，如：52（默认值）、52.5"
            setDefaultValue("52")
            setOnPreferenceChangeListener { _, newValue ->
                if (FONT_SIZE_REGEX.matches(newValue as String)) {
                    summary = newValue
                    Toast.makeText(context, "已加载章节需清除章节缓存后生效", Toast.LENGTH_LONG).show()
                    true
                } else {
                    Toast.makeText(context, "非法字号！请检查输入格式", Toast.LENGTH_LONG).show()
                    false
                }
            }
        },
        EditTextPreference(context).apply {
            key = PREF_BODY_FONT_SIZE
            title = "阅读页正文大小"
            summary = pref.getString(key, "30")
            dialogMessage = "请输入一个大于0的字号，可以带小数，如：30（默认值）、30.5"
            setDefaultValue("30")
            setOnPreferenceChangeListener { _, newValue ->
                if (FONT_SIZE_REGEX.matches(newValue as String)) {
                    summary = newValue
                    Toast.makeText(context, "已加载章节需清除章节缓存后生效", Toast.LENGTH_LONG).show()
                    true
                } else {
                    Toast.makeText(context, "非法字号！请检查输入格式", Toast.LENGTH_LONG).show()
                    false
                }
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
        SwitchPreferenceCompat(context).apply {
            key = PREF_DARK_MODE
            title = "深色模式"
            summary = "开启后，阅读页面的样式将强制使用黑底白字"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(context, "已加载章节需清除章节缓存后生效", Toast.LENGTH_LONG).show()
                true
            }
        },
    )
}
