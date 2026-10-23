package eu.kanade.tachiyomi.extension.zh.hikarinagi

import keiyoushi.utils.getArrayOrNull
import keiyoushi.utils.getIntOrNull
import keiyoushi.utils.getObjectOrNull
import keiyoushi.utils.getStringOrNull
import keiyoushi.utils.obj
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * How the site ships a novel volume now: instead of one encrypted EPUB the session returns a
 * *publication* — a skeleton piece (the archive's entry list plus its small inline files) and a
 * batch of shard pieces (the illustrations) — each served whole from a short-lived signed url.
 * Every piece is a `RITOSHD1` blob: a 68 byte header, then ciphertext sealed by XChaCha20-Poly1305
 * under the piece's hex key with the header as additional data and the tag at the tail; the
 * plaintext is raw-deflate compressed. [fetch] decrypts them and zips the entries back together.
 */
internal object ReaderPublication {

    private const val HEADER_SIZE = 68
    private const val TAG_SIZE = 16
    private val MAGIC = "RITOSHD1".toByteArray(Charsets.US_ASCII)

    private class Piece(val url: String, val key: String)

    /** Downloads every piece of [publication] and reassembles the volume's EPUB. */
    suspend fun fetch(client: OkHttpClient, publication: JsonObject): ByteArray {
        if (publication.getIntOrNull("format") != 3) broken()

        val skeleton = publication.getObjectOrNull("skeleton")?.toPiece() ?: broken()
        val shards = publication.getArrayOrNull("shards").orEmpty().map { runCatching { it.obj.toPiece() }.getOrNull() ?: broken() }

        // The skeleton lists the archive in order; entries without inline bytes name images the shards
        // carry. Shards are read one at a time so only the images stay in memory.
        val records = readSkeleton(fetchPiece(client, skeleton))
        val images = HashMap<String, ByteArray>()
        shards.forEach { shard ->
            currentCoroutineContext().ensureActive()
            readShard(fetchPiece(client, shard)).forEach { (name, data) -> images[name] = data }
        }

        val out = ByteArrayOutputStream((publication.getIntOrNull("bytes") ?: 0).coerceIn(0, 1 shl 24))
        ZipOutputStream(out).use { zip ->
            records.forEach { (name, data) ->
                val bytes = data ?: images[name] ?: broken()
                val entry = ZipEntry(name)
                // The EPUB spec wants a leading stored mimetype; everything else deflates as usual.
                if (name == "mimetype") {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun JsonObject.toPiece() = Piece(getStringOrNull("url") ?: broken(), getStringOrNull("key") ?: broken())

    /**
     * A piece's signed url carries its own expiry; an answer past it simply fails. Nineteen pieces
     * mean one flaky request would sink the whole volume, so a fault gets one more try.
     */
    private fun fetchPiece(client: OkHttpClient, piece: Piece): ByteArray {
        var failure: IOException? = null
        repeat(2) {
            val response = try {
                client.newCall(Request.Builder().url(piece.url).build()).execute()
            } catch (e: IOException) {
                failure = e
                return@repeat
            }
            response.use {
                if (response.isSuccessful) return decryptPiece(response.body.bytes(), piece.key)
                val message = if (response.code == 429) {
                    IOException("请求过于频繁，请稍后再试")
                } else {
                    IOException("分卷下载失败（HTTP ${response.code}），请刷新重试")
                }
                if (response.code !in 500..599) throw message
                failure = message
            }
        }
        throw failure ?: IOException("分卷下载失败，请刷新重试")
    }

    /** One `RITOSHD1` piece to its (inflated) plaintext. */
    private fun decryptPiece(body: ByteArray, keyHex: String): ByteArray {
        if (
            body.size < HEADER_SIZE + TAG_SIZE ||
            MAGIC.indices.any { body[it] != MAGIC[it] } ||
            u32(body, 8) != 3 || body[15] != 0.toByte() ||
            u64(body, 60) + HEADER_SIZE != body.size.toLong()
        ) {
            broken()
        }

        val header = body.copyOf(HEADER_SIZE)
        val nonce = ByteArray(12).apply { header.copyInto(this, 4, 52, 60) }
        val plain = aeadOpen(
            key = hchacha20(hexBytes(keyHex), header),
            nonce = nonce,
            aad = header,
            sealed = body,
            offset = HEADER_SIZE,
            length = body.size - HEADER_SIZE,
        )
        // Every piece's plaintext is raw-deflate compressed — byte 12 only marks the skeleton
        // apart from its shard pieces (0 vs 1), it is not a compression flag.
        return inflateRaw(plain)
    }

    /** The skeleton's ordered records: inline bytes for small files, `null` where a shard carries them. */
    private fun readSkeleton(buf: ByteArray): List<Pair<String, ByteArray?>> {
        if (u32(buf, 0) != 1) broken()
        var p = 8
        val count = u32(buf, 4)
        val records = ArrayList<Pair<String, ByteArray?>>(count.coerceIn(0, buf.size))
        repeat(count) {
            val (name, afterName) = readName(buf, p)
            p = afterName
            need(buf, p, 10)
            val stored = u64(buf, p)
            p += 8
            p += 1 // kind
            val outOfLine = buf[p].toInt() and 0xff
            p += 1
            if (outOfLine == 0) {
                val len = u64(buf, p)
                p += 8
                if (len != stored || stored > buf.size) broken()
                val n = stored.toInt()
                need(buf, p, n)
                records.add(name to buf.copyOfRange(p, p + n))
                p += n
            } else {
                // shard id, media type, width, height, spare — 4 + 1 + 4 + 4 + 4
                need(buf, p, 17)
                p += 17
                records.add(name to null)
            }
        }
        if (p != buf.size) broken()
        return records
    }

    /** A shard's payload: the whole files it carries, keyed by archive name. */
    private fun readShard(buf: ByteArray): List<Pair<String, ByteArray>> {
        if (u32(buf, 0) != 1) broken()
        var p = 4
        val items = ArrayList<Pair<String, ByteArray>>()
        while (p + 4 <= buf.size) {
            val (name, afterName) = readName(buf, p)
            p = afterName
            val len = u64(buf, p)
            p += 8
            if (len > buf.size) broken()
            val n = len.toInt()
            need(buf, p, n)
            items.add(name to buf.copyOfRange(p, p + n))
            p += n
        }
        if (p != buf.size) broken()
        return items
    }

    /** A `[u32 length][utf8]` name at [p]; returns it and the cursor just past it. */
    private fun readName(buf: ByteArray, p: Int): Pair<String, Int> {
        val len = u32(buf, p)
        need(buf, p + 4, len)
        return String(buf, p + 4, len, Charsets.UTF_8) to (p + 4 + len)
    }

    private fun inflateRaw(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 2)
            val buffer = ByteArray(32768)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(buffer)
                } catch (e: DataFormatException) {
                    broken()
                }
                if (n == 0 && inflater.needsInput()) broken()
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    /**
     * HChaCha20: the ChaCha20 core, 20 rounds, no counter block and no keystroke XOR fold — the
     * subkey is the final state words 0..3 and 12..15. The piece header's 24 byte nonce splits as
     * header[36..52) into the state and header[52..60) becoming the inner cipher's, zero padded.
     */
    private fun hchacha20(key: ByteArray, header: ByteArray): ByteArray {
        val s = IntArray(16)
        s[0] = 0x61707865
        s[1] = 0x3320646e
        s[2] = 0x79622d32
        s[3] = 0x6b206574
        for (i in 0..7) s[4 + i] = u32(key, i * 4)
        for (i in 0..3) s[12 + i] = u32(header, 36 + i * 4)
        chachaPermute(s)

        val out = ByteArray(32)
        for (i in 0..3) w32(out, i * 4, s[i])
        for (i in 0..3) w32(out, 16 + i * 4, s[12 + i])
        return out
    }

    /** The 20 ChaCha20 rounds (column then diagonal) over [s], in place. */
    private fun chachaPermute(s: IntArray) {
        fun rotl(v: Int, n: Int) = (v shl n) or (v ushr (32 - n))
        fun qr(a: Int, b: Int, c: Int, d: Int) {
            s[a] += s[b]
            s[d] = rotl(s[d] xor s[a], 16)
            s[c] += s[d]
            s[b] = rotl(s[b] xor s[c], 12)
            s[a] += s[b]
            s[d] = rotl(s[d] xor s[a], 8)
            s[c] += s[d]
            s[b] = rotl(s[b] xor s[c], 7)
        }
        repeat(10) {
            qr(0, 4, 8, 12)
            qr(1, 5, 9, 13)
            qr(2, 6, 10, 14)
            qr(3, 7, 11, 15)
            qr(0, 5, 10, 15)
            qr(1, 6, 11, 12)
            qr(2, 7, 8, 13)
            qr(3, 4, 9, 14)
        }
    }

    /** One 64 byte ChaCha20 keystream block for a 12 byte [nonce]. */
    private fun chacha20Block(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
        val init = IntArray(16)
        init[0] = 0x61707865
        init[1] = 0x3320646e
        init[2] = 0x79622d32
        init[3] = 0x6b206574
        for (i in 0..7) init[4 + i] = u32(key, i * 4)
        init[12] = counter
        for (i in 0..2) init[13 + i] = u32(nonce, i * 4)
        val s = init.copyOf()
        chachaPermute(s)
        val out = ByteArray(64)
        for (i in 0..15) w32(out, i * 4, s[i] + init[i])
        return out
    }

    private fun chacha20Xor(key: ByteArray, nonce: ByteArray, counter: Int, input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        var blocks = counter
        var p = 0
        while (p < input.size) {
            val stream = chacha20Block(key, nonce, blocks++)
            val n = minOf(64, input.size - p)
            for (i in 0 until n) out[p + i] = (input[p + i].toInt() xor stream[i].toInt()).toByte()
            p += n
        }
        return out
    }

    /**
     * XChaCha20-Poly1305 open, done in plain Kotlin: ChaCha20-Poly1305 only reaches Android from 9.0
     * (minSdk here is 26) and providers spell its name differently, so one self-contained
     * implementation keeps both the old devices and the desktop harness working.
     */
    private fun aeadOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray, offset: Int, length: Int): ByteArray {
        val ciphertext = sealed.copyOfRange(offset, offset + length - TAG_SIZE)
        val tag = sealed.copyOfRange(offset + length - TAG_SIZE, offset + length)
        val oneTimeKey = chacha20Block(key, nonce, 0).copyOf(32)
        if (!constantTimeEquals(aeadTag(oneTimeKey, aad, ciphertext), tag)) broken()
        return chacha20Xor(key, nonce, 1, ciphertext)
    }

    /** Poly1305 over the RFC 8439 AEAD layout: aad ‖ pad ‖ ciphertext ‖ pad ‖ little-endian lengths. */
    private fun aeadTag(oneTimeKey: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val aadPad = (16 - aad.size % 16) % 16
        val textPad = (16 - ciphertext.size % 16) % 16
        val mac = ByteArray(aad.size + aadPad + ciphertext.size + textPad + 16)
        aad.copyInto(mac, 0)
        ciphertext.copyInto(mac, aad.size + aadPad)
        val lengths = mac.size - 16
        var aadLen = aad.size.toLong()
        var textLen = ciphertext.size.toLong()
        for (i in 0..7) {
            mac[lengths + i] = (aadLen and 0xff).toByte()
            aadLen = aadLen ushr 8
            mac[lengths + 8 + i] = (textLen and 0xff).toByte()
            textLen = textLen ushr 8
        }
        return poly1305(oneTimeKey, mac)
    }

    /** Poly1305 with 26 bit limbs (the 32 bit reference structure), overflow-free in 64 bit longs. */
    private fun poly1305(key: ByteArray, message: ByteArray): ByteArray {
        val t0 = u32l(key, 0)
        val t1 = u32l(key, 4)
        val t2 = u32l(key, 8)
        val t3 = u32l(key, 12)
        val r0 = t0 and 0x3ffffffL
        val r1 = ((t0 ushr 26) or (t1 shl 6)) and 0x3ffff03L
        val r2 = ((t1 ushr 20) or (t2 shl 12)) and 0x3ffc0ffL
        val r3 = ((t2 ushr 14) or (t3 shl 18)) and 0x3f03fffL
        val r4 = (t3 ushr 8) and 0x00fffffL
        val s1 = r1 * 5
        val s2 = r2 * 5
        val s3 = r3 * 5
        val s4 = r4 * 5

        var h0 = 0L
        var h1 = 0L
        var h2 = 0L
        var h3 = 0L
        var h4 = 0L

        val block = ByteArray(16)
        var p = 0
        while (p < message.size) {
            val n = minOf(16, message.size - p)
            message.copyInto(block, 0, p, p + n)
            if (n < 16) {
                block.fill(0, n)
                block[n] = 1
            }
            val hibit = if (n == 16) 1L shl 24 else 0L
            val b0 = u32l(block, 0)
            val b1 = u32l(block, 4)
            val b2 = u32l(block, 8)
            val b3 = u32l(block, 12)
            h0 += b0 and 0x3ffffffL
            h1 += ((b0 ushr 26) or (b1 shl 6)) and 0x3ffffffL
            h2 += ((b1 ushr 20) or (b2 shl 12)) and 0x3ffffffL
            h3 += ((b2 ushr 14) or (b3 shl 18)) and 0x3ffffffL
            h4 += (b3 ushr 8) or hibit

            val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
            val d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
            val d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
            val d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
            val d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0

            var c = d0 ushr 26
            h0 = d0 and 0x3ffffffL
            var d = d1 + c
            c = d ushr 26
            h1 = d and 0x3ffffffL
            d = d2 + c
            c = d ushr 26
            h2 = d and 0x3ffffffL
            d = d3 + c
            c = d ushr 26
            h3 = d and 0x3ffffffL
            d = d4 + c
            c = d ushr 26
            h4 = d and 0x3ffffffL
            h0 += c * 5
            c = h0 ushr 26
            h0 = h0 and 0x3ffffffL
            h1 += c
            p += n
        }

        var c = h1 ushr 26
        h1 = h1 and 0x3ffffffL
        h2 += c
        c = h2 ushr 26
        h2 = h2 and 0x3ffffffL
        h3 += c
        c = h3 ushr 26
        h3 = h3 and 0x3ffffffL
        h4 += c
        c = h4 ushr 26
        h4 = h4 and 0x3ffffffL
        h0 += c * 5
        c = h0 ushr 26
        h0 = h0 and 0x3ffffffL
        h1 += c

        // h is now below 2^130; pick h - (2^130 - 5) instead when that is the smaller residue.
        var g0 = h0 + 5
        var gc = g0 ushr 26
        g0 = g0 and 0x3ffffffL
        var g1 = h1 + gc
        gc = g1 ushr 26
        g1 = g1 and 0x3ffffffL
        var g2 = h2 + gc
        gc = g2 ushr 26
        g2 = g2 and 0x3ffffffL
        var g3 = h3 + gc
        gc = g3 ushr 26
        g3 = g3 and 0x3ffffffL
        val g4 = h4 + gc - (1L shl 26)

        val mask = if (g4 < 0) 0L else -1L
        val keep = mask.inv()
        h0 = (h0 and keep) or (g0 and mask)
        h1 = (h1 and keep) or (g1 and mask)
        h2 = (h2 and keep) or (g2 and mask)
        h3 = (h3 and keep) or (g3 and mask)
        h4 = (h4 and keep) or (g4 and mask)

        val f0 = (h0 or (h1 shl 26)) and 0xffffffffL
        val f1 = ((h1 ushr 6) or (h2 shl 20)) and 0xffffffffL
        val f2 = ((h2 ushr 12) or (h3 shl 14)) and 0xffffffffL
        val f3 = ((h3 ushr 18) or (h4 shl 8)) and 0xffffffffL

        var f = f0 + u32l(key, 16)
        val pad0 = f and 0xffffffffL
        f = (f ushr 32) + f1 + u32l(key, 20)
        val pad1 = f and 0xffffffffL
        f = (f ushr 32) + f2 + u32l(key, 24)
        val pad2 = f and 0xffffffffL
        f = (f ushr 32) + f3 + u32l(key, 28)
        val pad3 = f and 0xffffffffL

        val out = ByteArray(16)
        w32(out, 0, pad0.toInt())
        w32(out, 4, pad1.toInt())
        w32(out, 8, pad2.toInt())
        w32(out, 12, pad3.toInt())
        return out
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private fun hexBytes(hex: String): ByteArray {
        if (hex.length != 64) broken()
        return ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toIntOrNull(16)?.toByte() ?: broken() }
    }

    private fun u32l(buf: ByteArray, offset: Int): Long = u32(buf, offset).toLong() and 0xffffffffL

    private fun need(buf: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset.toLong() + length > buf.size) broken()
    }

    private fun u32(buf: ByteArray, offset: Int): Int {
        need(buf, offset, 4)
        return (buf[offset].toInt() and 0xff) or ((buf[offset + 1].toInt() and 0xff) shl 8) or
            ((buf[offset + 2].toInt() and 0xff) shl 16) or ((buf[offset + 3].toInt() and 0xff) shl 24)
    }

    private fun u64(buf: ByteArray, offset: Int): Long {
        need(buf, offset, 8)
        var v = 0L
        for (i in 0..7) v = v or ((buf[offset + i].toLong() and 0xff) shl (8 * i))
        return v
    }

    private fun w32(buf: ByteArray, offset: Int, v: Int) {
        buf[offset] = (v and 0xff).toByte()
        buf[offset + 1] = ((v ushr 8) and 0xff).toByte()
        buf[offset + 2] = ((v ushr 16) and 0xff).toByte()
        buf[offset + 3] = ((v ushr 24) and 0xff).toByte()
    }

    private fun broken(): Nothing = throw IOException(Hikarinagi.BROKEN_VOLUME_MESSAGE)
}
