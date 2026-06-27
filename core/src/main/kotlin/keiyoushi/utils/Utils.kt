package keiyoushi.utils

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element

fun Element.selectText(css: String, defaultValue: String? = null): String? = select(css).first()?.text() ?: defaultValue

fun Element.selectInt(css: String, defaultValue: Int = 0): Int = select(css).first()?.text()?.toInt() ?: defaultValue

fun Element.attrOrText(css: String): String = if (css != "text") attr(css) else text()

suspend fun OkHttpClient.get(url: String, headers: Headers) = newCall(GET(url, headers)).awaitSuccess()

suspend fun OkHttpClient.get(url: HttpUrl, headers: Headers) = newCall(GET(url, headers)).awaitSuccess()
