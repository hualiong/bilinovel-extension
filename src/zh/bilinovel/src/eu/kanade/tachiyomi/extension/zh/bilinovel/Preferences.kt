package eu.kanade.tachiyomi.extension.zh.bilinovel

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.SwitchPreferenceCompat

const val PREF_POPULAR_DISPLAY = "POPULAR_DISPLAY"
const val PREF_SCREEN_STYLE = "SCREEN_STYLE"
const val PREF_DISPLAY_TRADITIONAL = "DISPLAY_TRADITIONAL"
const val PREF_DESCRIPTION = "DESCRIPTION"
const val PREF_DARK_MODE = "DARK_MODE_V2"
const val PREF_RATE_LIMIT = "RATE_LIMIT"
const val PREF_AUTO_BOOKMARK = "AUTO_BOOKMARK"
const val PREF_LOAD_ALL_IMAGES = "LOAD_ALL_IMAGES"

val STYLE_REGEX = Regex("^#[0-9A-F]{6} #[0-9A-F]{6} (?:\\d+|\\d+\\.\\d+) (?:\\d+|\\d+\\.\\d+)$", RegexOption.IGNORE_CASE)
val RATE_LIMIT_REGEX = Regex("^\\d+/\\d+$")

/** Background, text colour, heading size and body size; the renderer reads the same default. */
const val DEFAULT_SCREEN_STYLE = "#FAFAF8 #000000 56 40"

/** The sizes the extension shipped before the renderer took the light novel layout. */
private const val LEGACY_HEADING_SIZE = 52f
private const val LEGACY_BODY_SIZE = 30f

private const val DARK_APP = "app"
private const val DARK_ALWAYS = "always"
private const val DARK_NEVER = "never"

val DEFAULT_SET = setOf("A", "B", "C")

/**
 * Carries the settings of an older extension version over to the current defaults.
 *
 * The two sizes are judged on their own: a size still sitting at the old default is one the reader
 * never changed, so it follows the new default, while a size they set themselves is left alone. The
 * colours they chose are kept. The old dark mode was a switch, and becomes a choice.
 */
fun SharedPreferences.migratePreferences() {
    val storedDark = all[PREF_DARK_MODE]
    if (storedDark is Boolean) {
        // A switch from the old version, which had no "follow the reader" option.
        edit().putString(PREF_DARK_MODE, if (storedDark) DARK_ALWAYS else DARK_NEVER).apply()
    }
    val style = getString(PREF_SCREEN_STYLE, null)?.split(' ') ?: return
    if (style.size != 4) return
    val sizes = DEFAULT_SCREEN_STYLE.split(' ')
    val heading = if (style[2].toFloatOrNull() == LEGACY_HEADING_SIZE) sizes[2] else style[2]
    val body = if (style[3].toFloatOrNull() == LEGACY_BODY_SIZE) sizes[3] else style[3]
    if (heading == style[2] && body == style[3]) return
    edit().putString(PREF_SCREEN_STYLE, "${style[0]} ${style[1]} $heading $body").apply()
}

/** Whether a rendered page should be dark; "跟随 Mihon" falls back to the system. */
fun isDark(pref: SharedPreferences, appDark: Boolean?, systemDark: Boolean) = when (pref.getString(PREF_DARK_MODE, DARK_APP)) {
    DARK_ALWAYS -> true
    DARK_NEVER -> false
    else -> appDark ?: systemDark
}

fun preferencesInternal(context: Context, pref: SharedPreferences, isLoggedIn: Boolean) = arrayOf(
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
        key = PREF_SCREEN_STYLE
        title = "阅读页样式设置"
        summary = pref.getString(key, DEFAULT_SCREEN_STYLE)!!.split(' ').let {
            "背景色：${it[0]}   |   文本色：${it[1]}\n标题字号：${it[2]}   |   正文字号：${it[3]}"
        }
        dialogMessage = "每项配置用单空格隔开：前两个是颜色样式，格式为十六进制颜色代码，分别配置背景色和文本色；后两个是字号设置，格式为正数，分别配置标题和正文字号\n默认值：$DEFAULT_SCREEN_STYLE"
        setDefaultValue(DEFAULT_SCREEN_STYLE)
        setOnPreferenceChangeListener { _, newValue ->
            if (STYLE_REGEX.matches(newValue as String)) {
                summary = newValue.split(' ').let { "背景色：${it[0]}   |   文本色：${it[1]}\n标题字号：${it[2]}   |   正文字号：${it[3]}" }
                Toast.makeText(context, "已加载章节需清除章节缓存后生效", Toast.LENGTH_LONG).show()
                true
            } else {
                Toast.makeText(context, "格式不正确，请检查输入！", Toast.LENGTH_LONG).show()
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
    MultiSelectListPreference(context).apply {
        key = PREF_DESCRIPTION
        title = "作品信息显示偏好"
        summary = "设置作品简介中需要显示的额外信息"
        dialogTitle = "勾选需要显示的信息"
        entries = arrayOf("作品公告", "作品别名", "跳转链接")
        entryValues = arrayOf("A", "B", "C")
        setDefaultValue(DEFAULT_SET)
    },
    ListPreference(context).apply {
        key = PREF_DARK_MODE
        title = "深色模式"
        summary = "%s"
        entries = arrayOf("跟随 Mihon", "始终开启", "始终关闭")
        entryValues = arrayOf(DARK_APP, DARK_ALWAYS, DARK_NEVER)
        setDefaultValue(DARK_APP)
        setOnPreferenceChangeListener { _, _ ->
            Toast.makeText(context, "已加载章节需清除章节缓存后生效", Toast.LENGTH_LONG).show()
            true
        }
    },
    SwitchPreferenceCompat(context).apply {
        key = PREF_LOAD_ALL_IMAGES
        title = "确保加载所有插图"
        summary = "一旦有插图加载失败，不再用空白图占位，而是直接报错，确保用户可以重试，从而加载完所有插图"
        setDefaultValue(false)
    },
    SwitchPreferenceCompat(context).apply {
        key = PREF_AUTO_BOOKMARK
        title = "自动标记书签（源站功能）"
        summary = "阅读任一章节时，自动调用源站的“书签”功能标记该章节（不建议将章节下载后阅读，会导致超前标记）\n注：该功能需在 WebView 中登录，否则将自动关闭"
        setEnabled(isLoggedIn)
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
