package eu.kanade.tachiyomi.extension.zh.bilinovel

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat

const val PREF_POPULAR_DISPLAY = "POPULAR_DISPLAY"
const val PREF_SCREEN_STYLE = "SCREEN_STYLE"
const val PREF_DISPLAY_TRADITIONAL = "DISPLAY_TRADITIONAL"
const val PREF_DESCRIPTION = "DESCRIPTION"
const val PREF_DARK_MODE = "DARK_MODE_V2"
const val PREF_RATE_LIMIT = "RATE_LIMIT"
const val PREF_ACCOUNT = "ACCOUNT"
const val PREF_PASSWORD = "PASSWORD"
const val PREF_AUTO_BOOKMARK = "AUTO_BOOKMARK"
const val PREF_LOAD_ALL_IMAGES = "LOAD_ALL_IMAGES"

/** Machine state, never shown on the screen: the version pair learned from the official app. */
const val PREF_VERSION_CODE = "VERSION_CODE"
const val PREF_VERSION_NAME = "VERSION_NAME"

/** Everything the settings screen owns, so the two files can be kept in step with each other. */
val SHARED_PREF_KEYS = setOf(
    PREF_POPULAR_DISPLAY,
    PREF_SCREEN_STYLE,
    PREF_DISPLAY_TRADITIONAL,
    PREF_DESCRIPTION,
    PREF_DARK_MODE,
    PREF_RATE_LIMIT,
    PREF_ACCOUNT,
    PREF_PASSWORD,
    PREF_AUTO_BOOKMARK,
    PREF_LOAD_ALL_IMAGES,
)

val STYLE_REGEX = Regex("^#[0-9A-F]{6} #[0-9A-F]{6} (?:\\d+|\\d+\\.\\d+) (?:\\d+|\\d+\\.\\d+)$", RegexOption.IGNORE_CASE)
val RATE_LIMIT_REGEX = Regex("^[1-9]\\d*/[1-9]\\d*$")

/** Background, text colour, heading size and body size; the renderer reads the same default. */
const val DEFAULT_SCREEN_STYLE = "#FAFAF8 #000000 56 40"

/** The sizes the extension shipped before the renderer took the light novel layout. */
private const val LEGACY_HEADING_SIZE = 52f
private const val LEGACY_BODY_SIZE = 30f

private const val DARK_APP = "app"
private const val DARK_ALWAYS = "always"
private const val DARK_NEVER = "never"

val DEFAULT_SET = setOf("A", "B", "C")

private const val LOGIN_ADVICE = "未登录（App 端推荐登录账号，否则建议用回网页端）"
private const val LOGIN_DIALOG = "App 端内的非原创作品要登录才能看，有账号的用户建议用 App 端，否则建议用回网页端"

/**
 * Carries the settings of an older extension version over to the current defaults.
 *
 * The two sizes are judged on their own: a size still sitting at the old default is one the reader
 * never changed, so it follows the new default, while a size they set themselves is left alone. The
 * colours they chose are kept. The old dark mode was a switch, and becomes a choice.
 */
fun SharedPreferences.migratePreferences() {
    // The old key held a switch and had no "follow the reader" option; the new key holds a choice.
    val legacyDark = all["DARK_MODE"]
    if (legacyDark is Boolean && !contains(PREF_DARK_MODE)) {
        edit().putString(PREF_DARK_MODE, if (legacyDark) DARK_ALWAYS else DARK_NEVER).apply()
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

/**
 * Mihon's screen persists into the source's own file, while both sources read one shared file.
 * Every change is mirrored into [this], and the screen's file is synced back from it on open.
 */
internal fun SharedPreferences.mirror(key: String, value: Any?) = edit().apply {
    when (value) {
        is Boolean -> putBoolean(key, value)
        is Int -> putInt(key, value)
        is String -> putString(key, value)
        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
        null -> remove(key)
    }
}.apply()

/** The compile stub lacks the Context constructor the real androidx class has had forever. */
private fun plainPreference(context: Context): Preference = Preference::class.java.getConstructor(Context::class.java).newInstance(context)

/** The badge beside the login item: a live session, a recorded failure, or the plain advice. */
enum class LoginStatus { PENDING, ACTIVE, FAILED, NONE }

fun loginSummary(pref: SharedPreferences, status: LoginStatus, error: String?): String {
    val account = pref.getString(PREF_ACCOUNT, null)?.takeIf(String::isNotBlank) ?: return LOGIN_ADVICE
    return when (status) {
        LoginStatus.PENDING -> "登录中......"
        LoginStatus.ACTIVE -> "已登录：$account"
        LoginStatus.FAILED -> "登录失败：" + error?.takeIf(String::isNotBlank)
        LoginStatus.NONE -> LOGIN_ADVICE
    }
}

/** One dialog for both fields, so a credential pair is only ever saved as a pair. */
private fun showLoginDialog(context: Context, pref: SharedPreferences, onSaved: () -> Unit) {
    val density = context.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    // M3 dialog containers sit on surfaceContainerHigh, one step above the plain surface.
    val surface = themeColor(context, "colorSurfaceContainerHigh", themeColor(context, "colorSurface", 0xFF1D1B20.toInt()))
    val onSurface = themeColor(context, "colorOnSurface", 0xFFE6E1E5.toInt())
    val primary = themeColor(context, "colorPrimary", 0xFFD0BCFF.toInt())
    val dim = (onSurface and 0x00FFFFFF) or (0xB3 shl 24)
    val faint = (onSurface and 0x00FFFFFF) or (0x80 shl 24)

    val account = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_TEXT
        textSize = 16f
        hint = "用户名或邮箱"
        setText(pref.getString(PREF_ACCOUNT, "") ?: "")
        setTextColor(onSurface)
        setHintTextColor(faint)
        // The underline nine-patch carries a 4dp inset: spread the view, pull the text back.
        setPadding(dp(4), paddingTop, dp(4), paddingBottom)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = -dp(4)
            marginEnd = -dp(4)
        }
    }
    val password = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        textSize = 16f
        hint = "登录密码"
        setText(pref.getString(PREF_PASSWORD, "") ?: "")
        setTextColor(onSurface)
        setHintTextColor(faint)
        setPadding(dp(4), paddingTop, dp(4), paddingBottom)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = -dp(4)
            marginEnd = -dp(4)
        }
    }

    val dialog = android.app.Dialog(context)

    /** Returning true closes the dialog; a rejected save stays open with its toast. */
    fun button(text: String, action: () -> Boolean) = TextView(context).apply {
        this.text = text
        setTextColor(primary)
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setOnClickListener {
            if (action()) dialog.dismiss()
        }
    }

    val box = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(20), dp(24), dp(8))
        addView(
            TextView(context).apply {
                text = "登录账号"
                textSize = 20f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(onSurface)
                setPadding(0, dp(2), 0, dp(8))
            },
        )
        addView(
            TextView(context).apply {
                text = LOGIN_DIALOG
                textSize = 14f
                setTextColor(dim)
                setPadding(0, 0, 0, dp(2))
            },
        )
        addView(account)
        addView(password)
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
                // Bleed past the box padding so the first and last button text align with the content edges.
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = -dp(14)
                    marginEnd = -dp(14)
                }
                addView(
                    button("退出账号") {
                        pref.mirror(PREF_ACCOUNT, "")
                        pref.mirror(PREF_PASSWORD, "")
                        onSaved()
                        true
                    },
                )
                addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
                addView(
                    button("登录") {
                        val user = account.text.toString().trim()
                        val pass = password.text.toString()
                        if (user.isEmpty() || pass.isEmpty()) {
                            Toast.makeText(context, "账号和密码都不能为空", Toast.LENGTH_SHORT).show()
                            false
                        } else {
                            pref.mirror(PREF_ACCOUNT, user)
                            pref.mirror(PREF_PASSWORD, pass)
                            onSaved()
                            true
                        }
                    },
                )
            },
        )
    }
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setContentView(box)
    account.requestFocus()
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    dialog.window?.apply {
        setBackgroundDrawable(
            GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(28).toFloat()
            },
        )
        setLayout((context.resources.displayMetrics.widthPixels * 0.86f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    dialog.show()
}

/** Colours come from the host's Material 3 theme, so the dialog matches the app in light and dark. */
private fun themeColor(context: Context, name: String, fallback: Int): Int {
    val id = context.resources.getIdentifier(name, "attr", context.packageName)
    val value = TypedValue()
    if (id != 0 && context.theme.resolveAttribute(id, value, true)) {
        if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) return value.data
        if (value.resourceId != 0) return context.resources.getColor(value.resourceId, context.theme)
    }
    return fallback
}

fun preferencesInternal(context: Context, pref: SharedPreferences, onCredentialsChanged: () -> Unit, includeLink: Boolean, isLoggedIn: Boolean, loginStatus: () -> LoginStatus, loginError: () -> String?): Array<Preference> {
    val loadAllImages = SwitchPreferenceCompat(context).apply {
        key = PREF_LOAD_ALL_IMAGES
        title = "确保加载所有插图"
        summary = "一旦有插图加载失败，不再用空白图占位，而是直接报错，确保用户可以重试，从而加载完所有插图"
        setDefaultValue(false)
        setOnPreferenceChangeListener { _, newValue ->
            pref.mirror(key, newValue)
            true
        }
    }
    lateinit var login: Preference
    login = plainPreference(context).apply {
        key = PREF_ACCOUNT
        title = "登录账号"
        summary = loginSummary(pref, loginStatus(), loginError())
        setOnPreferenceClickListener {
            showLoginDialog(context, pref) {
                onCredentialsChanged()
                login.summary = loginSummary(pref, loginStatus(), loginError())
            }
            true
        }
    }
    return arrayOf(
        login,
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
                "/top/postdate/%d.html",
                "/top/goodnum/%d.html",
                "/top/newhot/%d.html",
            )
            setDefaultValue("/top/weekvisit/%d.html")
            setOnPreferenceChangeListener { _, newValue ->
                pref.mirror(key, newValue)
                true
            }
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
                    pref.mirror(key, newValue)
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
                    pref.mirror(key, newValue)
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
            entries = if (includeLink) arrayOf("作品公告", "作品别名", "跳转链接") else arrayOf("作品公告", "作品别名")
            entryValues = if (includeLink) arrayOf("A", "B", "C") else arrayOf("A", "B")
            setDefaultValue(DEFAULT_SET)
            setOnPreferenceChangeListener { _, newValue ->
                pref.mirror(key, newValue)
                true
            }
        },
        ListPreference(context).apply {
            key = PREF_DARK_MODE
            title = "深色模式"
            summary = "%s"
            entries = arrayOf("跟随 Mihon", "始终开启", "始终关闭")
            entryValues = arrayOf(DARK_APP, DARK_ALWAYS, DARK_NEVER)
            setDefaultValue(DARK_APP)
            setOnPreferenceChangeListener { _, newValue ->
                pref.mirror(key, newValue)
                Toast.makeText(context, "已加载章节需清除章节缓存后生效", Toast.LENGTH_LONG).show()
                true
            }
        },
        loadAllImages,
        SwitchPreferenceCompat(context).apply {
            key = PREF_AUTO_BOOKMARK
            title = "自动同步阅读进度（需登录）"
            summary = "阅读章节时自动同步到源站书架的阅读进度"
            setEnabled(isLoggedIn)
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                pref.mirror(key, newValue)
                true
            }
        },
        SwitchPreferenceCompat(context).apply {
            key = PREF_DISPLAY_TRADITIONAL
            title = "显示繁体"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                pref.mirror(key, newValue)
                Toast.makeText(context, "已加载章节需清除章节缓存后生效", Toast.LENGTH_LONG).show()
                true
            }
        },
    )
}
