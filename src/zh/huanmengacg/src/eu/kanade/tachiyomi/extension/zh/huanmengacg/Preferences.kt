package eu.kanade.tachiyomi.extension.zh.huanmengacg

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat

const val PREF_SCREEN_BG_COLOR = "SCREEN_BG_COLOR"
const val PREF_SCREEN_FONT_COLOR = "FONT_COLOR"
const val PREF_HEADING_FONT_SIZE = "HEADING_FONT_SIZE"
const val PREF_BODY_FONT_SIZE = "BODY_FONT_SIZE"
const val PREF_LINES_PER_PAGE = "LINES_PER_PAGE"
const val PREF_DARK_MODE = "DARK_MODE"

val RGB_REGEX = Regex("^#[0-9A-F]{6}$", RegexOption.IGNORE_CASE)
val FONT_SIZE_REGEX = Regex("^(?:\\d+|\\d+\\.\\d+)$")
val NUM_REGEX = Regex("\\d+")

fun preferencesInternal(context: Context, pref: SharedPreferences): Array<Preference> {
    return arrayOf(
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
        EditTextPreference(context).apply {
            key = PREF_LINES_PER_PAGE
            title = "正文每页行数"
            summary = pref.getString(key, "30")
            dialogMessage = "行数越小，单张图片的缩放程度越小。如果你喜欢横向翻页方式，可以尝试调小行数，如：20（默认值：100）"
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
