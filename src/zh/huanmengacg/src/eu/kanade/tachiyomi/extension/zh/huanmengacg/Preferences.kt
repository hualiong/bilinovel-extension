package eu.kanade.tachiyomi.extension.zh.huanmengacg

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat

const val PREF_SCREEN_COLORS = "SCREEN_COLORS"
const val PREF_SCREEN_FONT_SIZE = "SCREEN_FONT_SIZE"
const val PREF_LINES_PER_PAGE = "LINES_PER_PAGE"
const val PREF_DARK_MODE = "DARK_MODE"

val RGB_REGEX = Regex("^#[0-9A-F]{6} #[0-9A-F]{6}$", RegexOption.IGNORE_CASE)
val FONT_SIZE_REGEX = Regex("^(?:\\d+|\\d+\\.\\d+) (?:\\d+|\\d+\\.\\d+)$")
val NUM_REGEX = Regex("\\d+")

fun preferencesInternal(context: Context, pref: SharedPreferences): Array<Preference> {
    return arrayOf(
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
            key = PREF_LINES_PER_PAGE
            title = "每页段落数"
            summary = pref.getString(key, "30")
            dialogMessage = "设置每页显示的最大段落数，而不是行数。如果喜欢横向翻页，可以尝试调小行数。默认为 100"
            setDefaultValue("100")
            setOnPreferenceChangeListener { _, newValue ->
                if (NUM_REGEX.matches(newValue as String)) {
                    summary = newValue
                    Toast.makeText(context, "已加载章节需清除章节缓存后生效", Toast.LENGTH_LONG).show()
                    true
                } else {
                    Toast.makeText(context, "非法数字！请检查输入", Toast.LENGTH_LONG).show()
                    false
                }
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
