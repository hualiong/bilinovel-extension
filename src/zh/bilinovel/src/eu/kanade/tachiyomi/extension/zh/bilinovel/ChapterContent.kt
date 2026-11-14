package eu.kanade.tachiyomi.extension.zh.bilinovel

sealed interface Block {
    class Text(val text: String) : Block

    class Image(val url: String) : Block
}

/** Wraps an illustration URL inside the text the renderer receives. */
const val IMAGE_MARKER = '\u0000'

private val INLINE_IMAGE = Regex("""<img[^>]*\bsrc=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)

/**
 * The client API returns the whole chapter as plain text, already in reading order; only the
 * illustrations the author inlined remain as tags in it. A chapter that is nothing but
 * illustrations carries them in [images] instead.
 */
fun chapterText(text: String, images: List<String>, appendImages: Boolean, baseUrl: String): String {
    fun normalize(url: String) = url.trim()
        .replace("&amp;", "&")
        .let {
            when {
                it.startsWith("//") -> "https:$it"
                it.startsWith("/") -> baseUrl + it
                else -> it
            }
        }

    val used = HashSet<String>()
    val builder = StringBuilder()
    text.replace("\r\n", "\n").replace('\r', '\n').lines().forEach { line ->
        var last = 0
        INLINE_IMAGE.findAll(line).forEach { match ->
            builder.append(line, last, match.range.first).append('\n')
            val url = normalize(match.groupValues[1])
            builder.append(IMAGE_MARKER).append(url).append(IMAGE_MARKER).append('\n')
            used += url
            last = match.range.last + 1
        }
        builder.append(line.substring(last)).append('\n')
    }
    if (appendImages) {
        images.map(::normalize).filter { it.isNotEmpty() && used.add(it) }.forEach {
            builder.append(IMAGE_MARKER).append(it).append(IMAGE_MARKER).append('\n')
        }
    }
    return builder.toString()
}
