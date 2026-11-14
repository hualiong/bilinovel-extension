package eu.kanade.tachiyomi.extension.zh.bilinovel

import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

internal const val API_BASE = "https://api.readpai.com/phone/api/client/bilinovel/"

/**
 * The official app's version code is published nowhere: version/check gives only the name and
 * the gate accepts only the exact (code, name) pair of a real release. The shipped pair below
 * is current at release time and serves as the preference default, so a fresh install sends no
 * discovery request at all; only when the gate rejects it does one request learn the new pair
 * and store it. Bump the default when releases run past default+SPAN.
 */
private const val DEFAULT_VERSION_CODE = 65
private const val DEFAULT_VERSION_NAME = "1.8.1"
private const val VERSION_PROBE_SPAN = 8

private const val VERSION_NAME_FAIL = "获取客户端最新版本号失败"
private const val VERSION_CODE_FAIL = "探测客户端最新版本码失败"
internal const val APP_PACKAGE = "com.linovelib.bilinovel"

internal const val TOKEN = "token"

/** Shown whenever a call needs a login the client cannot (re-)establish by itself. */
internal const val LOGIN_HINT = "请先在「插件设置」中登录后再试"

/** The same hint for a book the account cannot open without logging in. */
internal const val READ_HINT = "该作品阅读受限，请先在「插件设置」中登录后再试"

private val API_URL = API_BASE.toHttpUrl()
private val JSON = "application/json; charset=utf-8".toMediaType()
private val TEN_YEARS = 10L * 365 * 24 * 60 * 60 * 1000

private fun BiliNovel.apiHeaders(token: String?, version: Pair<Int, String>) = headers.newBuilder()
    .set("User-Agent", "Bilinovel/${version.second} (Android 14; Pixel 8)")
    .set("App-Version-Code", version.first.toString())
    .set("App-Version-Name", version.second)
    .set("X-App-Package-Name", APP_PACKAGE)
    .set("X-App-Language", "zh-CN")
    .apply { token?.let { set("Authorization", it) } }
    .build()

/**
 * POSTs one client-API call and returns the envelope. A 401 is retried once after a silent
 * re-login; when that is not possible, the reader is told to log in first.
 */
internal suspend fun BiliNovel.apiPost(
    path: String,
    body: JSONObject = JSONObject(),
    query: Map<String, String> = emptyMap(),
    restricted: Boolean = false,
    retryLogin: Boolean = true,
    retryVersion: Boolean = true,
): JSONObject {
    val version = versionPair()
    val url = API_URL.newBuilder()
        .addPathSegments(path)
        .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
        .build()
    val request = Request.Builder()
        .url(url)
        .headers(apiHeaders(clientToken(), version))
        .post(body.toString().toRequestBody(JSON))
        .build()
    val envelope = client.newCall(request).awaitSuccess().use { JSONObject(it.body.string()) }
    return when (val code = envelope.optInt("code")) {
        200 -> envelope
        401 -> {
            if (retryLogin && login() == null) {
                return apiPost(path, body, query, restricted, retryLogin = false)
            }
            // A token that cannot be renewed would only keep the progress switch looking enabled.
            if (clientToken() != null) clearToken()
            refreshLoginSummary()
            throw Exception(if (restricted) READ_HINT else LOGIN_HINT)
        }

        403, 501 -> {
            if (retryVersion) {
                learnVersion()
                return apiPost(path, body, query, restricted, retryLogin, retryVersion = false)
            }
            throw Exception(VERSION_CODE_FAIL)
        }
        else -> throw Exception(envelope.optString("msg").ifBlank { "请求失败（$code）" })
    }
}

/** The stored pair once learned; the shipped defaults until the gate rejects them. */
internal fun BiliNovel.versionPair(): Pair<Int, String> = pref.getInt(PREF_VERSION_CODE, DEFAULT_VERSION_CODE) to
    (pref.getString(PREF_VERSION_NAME, DEFAULT_VERSION_NAME) ?: DEFAULT_VERSION_NAME)

/**
 * Called when the gate rejects the current pair: asks the ungated check for the newest name,
 * then walks the code window above the rejected code; the winner is stored for good.
 */
private suspend fun BiliNovel.learnVersion() {
    val stale = versionPair()
    val name = runCatching { announcedVersionName(stale) }.getOrElse { throw Exception(VERSION_NAME_FAIL) }
    val code = (stale.first + 1..stale.first + VERSION_PROBE_SPAN).firstOrNull { probe ->
        runCatching { versionAnnouncementOk(probe to name) }.getOrDefault(false)
    } ?: throw Exception(VERSION_CODE_FAIL)
    pref.mirror(PREF_VERSION_CODE, code)
    pref.mirror(PREF_VERSION_NAME, name)
}

/** The update check is not version-gated: even a rejected pair may ask for the newest name. */
private suspend fun BiliNovel.announcedVersionName(version: Pair<Int, String>): String {
    val envelope = versionPost("version/check", version)
    if (envelope.optInt("code") != 200) throw Exception(envelope.optString("msg"))
    return envelope.data().str("versionName")
}

/** The gated announcement answers 200 only for the current release pair. */
private suspend fun BiliNovel.versionAnnouncementOk(version: Pair<Int, String>): Boolean = versionPost("system/startupAnnouncement", version).optInt("code") == 200

private suspend fun BiliNovel.versionPost(path: String, version: Pair<Int, String>): JSONObject {
    val request = Request.Builder()
        .url(API_BASE + path)
        .headers(apiHeaders(null, version))
        .post("{}".toRequestBody(JSON))
        .build()
    return client.newCall(request).awaitSuccess().use { JSONObject(it.body.string()) }
}

internal fun JSONObject.data(): JSONObject = optJSONObject("data") ?: throw Exception("服务端未返回数据")

internal fun JSONObject.dataArray(): JSONArray = optJSONArray("data") ?: throw Exception("服务端未返回数据")

internal fun JSONObject.str(key: String) = optString(key)

internal fun JSONObject.int(key: String) = optInt(key)

internal fun JSONObject.arr(key: String) = optJSONArray(key)

internal fun JSONArray.objects() = List(length()) { getJSONObject(it) }

/** The session token lives in the same cookie jar the web view writes to. */
internal fun BiliNovel.clientToken(): String? = client.cookieJar.loadForRequest(API_URL).firstOrNull { it.name == TOKEN }?.value

internal fun BiliNovel.saveToken(value: String) {
    client.cookieJar.saveFromResponse(
        API_URL,
        listOf(
            Cookie.Builder()
                .domain(API_URL.host)
                .path("/")
                .name(TOKEN)
                .value(value)
                .secure()
                // No expiry logic anywhere: the far date is only how the cookie store keeps it.
                .expiresAt(System.currentTimeMillis() + TEN_YEARS)
                .build(),
        ),
    )
}

internal fun BiliNovel.clearToken() {
    client.cookieJar.saveFromResponse(
        API_URL,
        listOf(Cookie.Builder().domain(API_URL.host).path("/").name(TOKEN).value("").expiresAt(0).build()),
    )
}

/** Logs in with the credentials from the settings; null on success, the reason otherwise. */
internal suspend fun BiliNovel.login(): String? = runCatching {
    val user = pref.getString(PREF_ACCOUNT, null)?.trim()?.takeIf(String::isNotEmpty) ?: return "未填写账号密码"
    val pass = pref.getString(PREF_PASSWORD, null)?.takeIf(String::isNotEmpty) ?: return "未填写账号密码"

    val challenge = apiPost(
        "user/login/challenge",
        JSONObject().put("uname", user).put("pass", pass),
        retryLogin = false,
    ).data()

    val nonce = UUID.randomUUID().toString().replace("-", "")
    val timestamp = System.currentTimeMillis()
    val proof = listOf(challenge.str("challenge"), user, pass, nonce, timestamp.toString())
        .joinToString("|")
        .md5()

    val token = apiPost(
        "user/login",
        JSONObject()
            .put("uname", user)
            .put("pass", pass)
            .put("challengeId", challenge.str("challengeId"))
            .put("proof", proof)
            .put("nonce", nonce)
            .put("timestamp", timestamp),
        retryLogin = false,
    ).data().str("token")

    saveToken(token)
    loginError = null
    null
}.getOrElse { t ->
    loginError = t.message?.takeIf(String::isNotBlank) ?: "网络或服务异常"
    loginError
}

private fun String.md5(): String = MessageDigest.getInstance("MD5").digest(toByteArray()).joinToString("") { "%02x".format(it) }
