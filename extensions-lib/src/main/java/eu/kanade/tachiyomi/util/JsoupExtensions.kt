package eu.kanade.tachiyomi.util

import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

fun Element.selectText(css: String, defaultValue: String? = null): String? {
    throw Exception("Stub!")
}

fun Element.selectInt(css: String, defaultValue: Int = 0): Int {
    throw Exception("Stub!")
}

fun Element.attrOrText(css: String): String {
    throw Exception("Stub!")
}

/**
 * Returns a Jsoup document for this response.
 * @param html the body of the response. Use only if the body was read before calling this method.
 */
fun Response.asJsoup(html: String? = null): Document {
    throw Exception("Stub!")
}