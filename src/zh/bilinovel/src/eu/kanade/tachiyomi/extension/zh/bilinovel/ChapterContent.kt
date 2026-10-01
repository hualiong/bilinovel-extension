package eu.kanade.tachiyomi.extension.zh.bilinovel

import android.text.Html
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.math.floor

/** The chapter body, in reading order: paragraphs and the illustrations between them. */
class ChapterContent(val blocks: List<Block>) {

    /** The text the renderer draws for this body. */
    val text = buildString {
        blocks.forEach { block ->
            when (block) {
                is Block.Text -> append(block.text).append('\n')
                // The URL travels as a path segment; a malformed one would break the page list.
                is Block.Image -> block.url.takeIf(String::isNotBlank)
                    ?.let { append(IMAGE_MARKER).append(it).append(IMAGE_MARKER).append('\n') }
            }
        }
    }

    companion object {
        /** Wraps an illustration URL inside the text the renderer receives. */
        const val IMAGE_MARKER = '\u0000'
    }
}

sealed interface Block {
    class Text(val text: String) : Block

    class Image(val url: String) : Block
}

/**
 * The chapter title as plain text; the page counter the site appends is dropped.
 *
 * A `/` can only come from the counter, so its presence means the title is "continued".
 */
fun chapterTitle(doc: Document): String {
    val html = doc.selectFirst("#atitle")?.html() ?: return ""
    if (html.indexOf('/') >= 0) return ""
    return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim()
}

/**
 * Reads `#acontent` as paragraphs and illustrations, keeping the scrambled order the site
 * applies on top of the real one.
 */
fun collectBlocks(content: Element, chapterId: Int, salt: Pair<Int, Int>): ChapterContent {
    val nodes = content.children().filter { element ->
        when (element.tagName()) {
            PARAGRAPH_TAG -> element.wholeText().isNotBlank()
            // An illustration carries its url in an attribute and has no text of its own.
            IMAGE_TAG -> element.imageUrl() != null
            // The hidden block the site keeps for its lazy loader holds no illustration of its own.
            "div" -> false
            else -> element.imageUrl() != null || element.children().isNotEmpty() || element.wholeText().isNotBlank()
        }
    }.toMutableList()

    val paragraphs = nodes.filter { it.tagName() == PARAGRAPH_TAG }
    val order = paragraphOrder(paragraphs.size, chapterId, salt)
    val scrambled = arrayOfNulls<Element>(order.size)
    // The generator says where each paragraph goes, not which paragraph goes into each slot.
    order.forEachIndexed { original, target -> scrambled[target] = paragraphs[original] }

    var next = 0
    val shuffled = nodes.map { node -> if (node.tagName() == PARAGRAPH_TAG) scrambled[next++]!! else node }

    val blocks = mutableListOf<Block>()
    shuffled.forEach { node ->
        if (node.tagName() == PARAGRAPH_TAG) {
            node.normalizedText().takeIf(String::isNotBlank)?.let { blocks.add(Block.Text(it)) }
            return@forEach
        }
        collectImageUrls(node).forEach { blocks.add(Block.Image(it)) }
    }
    return ChapterContent(blocks)
}

/**
 * The position every paragraph moves to: the first ones stay put and the rest are shuffled with
 * the generator the site seeds from the chapter.
 */
private fun paragraphOrder(size: Int, chapterId: Int, salt: Pair<Int, Int>): List<Int> {
    val order = MutableList(size) { it }
    if (size <= SORTED_PARAGRAPHS) return order

    // Only the paragraphs after the leading ones are shuffled: their sub-array is what the site
    // permutes, so the swap has to stay inside it too.
    var state = chapterId.toLong() * salt.first + salt.second
    for (i in order.lastIndex downTo SORTED_PARAGRAPHS + 1) {
        state = (state * SORT_SEED_MULTIPLIER + SORT_SEED_ADDEND) % SORT_SEED_MODULUS
        val span = i - SORTED_PARAGRAPHS + 1
        val j = SORTED_PARAGRAPHS + floor((state / SORT_SEED_MODULUS.toDouble()) * span).toInt()
        if (j != i) {
            val swap = order[i]
            order[i] = order[j]
            order[j] = swap
        }
    }
    return order
}

/** The paragraph's own text, with the line breaks the site leaves inside it kept as whitespace. */
private fun Element.normalizedText() = NEWLINE_REGEX.replace(wholeText(), "\n").trim()

/**
 * Collects the illustrations below [element], in the order the site embedded them.
 *
 * `select` matches [element] itself when that already is an illustration, so the element must
 * not be added a second time on its own: doing that drew every illustration of a chapter twice.
 */
private fun collectImageUrls(element: Element): List<String> = element.select(IMAGE_TAG).mapNotNull { it.imageUrl() }

private const val PARAGRAPH_TAG = "p"
private const val IMAGE_TAG = "img"

/** The attribute the site keeps the real illustration url in, until its lazy loader swaps it in. */
private val IMAGE_SOURCES = listOf("data-src", "src", "data-original")

private fun Element.imageUrl(): String? {
    val key = IMAGE_SOURCES.firstOrNull { !attr(it).isBlank() }
    return key?.let { absUrl(it) }?.takeIf(String::isNotBlank)
}
private val NEWLINE_REGEX = Regex("(?:\\r?\\n)+")

/** Paragraphs the reader is meant to see first, kept in the order the site serves them. */
private const val SORTED_PARAGRAPHS = 20

private const val SORT_SEED_MULTIPLIER = 9302L
private const val SORT_SEED_ADDEND = 49397L
private const val SORT_SEED_MODULUS = 233280L
