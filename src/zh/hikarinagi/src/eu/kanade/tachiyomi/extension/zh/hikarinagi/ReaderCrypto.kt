package eu.kanade.tachiyomi.extension.zh.hikarinagi

import android.util.Base64
import okio.BufferedSource
import okio.Source
import okio.cipherSource
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The site's reader hands out every page and every volume encrypted.
 *
 * A request carries a 64 byte token the client generated itself (or, for volumes, one the session
 * returned), and the answer is `iv || AES-256-GCM(ciphertext)`: the key is the token's two halves
 * XORed together, and the token (for pages the ids it stands for) is the additional authenticated
 * data. See `MangaImageInterceptor` and `Hikarinagi.getNovelPageList`.
 */
internal object ReaderCrypto {

    private const val TOKEN_SIZE = 64
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    /** What an answer costs on top of its plaintext: the iv it starts with and the tag it ends with. */
    const val OVERHEAD_SIZE = IV_SIZE + TAG_BITS / 8

    private const val DECRYPT_FAILED = "内容解密失败，请刷新后重试"

    private val random = SecureRandom()

    /** The `p` a content request carries; the site encrypts its answer with it. */
    fun newToken(): String = ByteArray(TOKEN_SIZE)
        .also(random::nextBytes)
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }

    /**
     * Decrypts an answer as it is read: the iv comes off the head of [source] and the rest goes
     * through the cipher, so a page is never held whole in memory. With GCM the tag can only be
     * checked once the stream ends.
     */
    fun decrypting(source: BufferedSource, token: String, associatedData: String): Source = runCatching {
        source.cipherSource(newCipher(token, source.readByteArray(IV_SIZE.toLong()), associatedData))
    }.getOrElse { throw IOException(DECRYPT_FAILED) }

    /** Decrypts a whole answer, for callers that parse the bytes instead of streaming them. */
    fun decrypt(token: String, content: ByteArray, associatedData: String): ByteArray {
        if (content.size <= IV_SIZE) throw IOException(DECRYPT_FAILED)

        return runCatching {
            newCipher(token, content.copyOf(IV_SIZE), associatedData)
                .doFinal(content, IV_SIZE, content.size - IV_SIZE)
        }.getOrElse { throw IOException(DECRYPT_FAILED) }
    }

    /** The cipher an answer is read through, with its iv already taken off the head. */
    private fun newCipher(token: String, iv: ByteArray, associatedData: String): Cipher {
        val tokenBytes = Base64.decode(token.padBase64(), Base64.URL_SAFE)
        if (tokenBytes.size != TOKEN_SIZE) throw IOException(DECRYPT_FAILED)
        val key = ByteArray(TOKEN_SIZE / 2) { tokenBytes[it].toInt().xor(tokenBytes[it + TOKEN_SIZE / 2].toInt()).toByte() }

        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        }
    }

    private fun String.padBase64() = this + "=".repeat((4 - length % 4) % 4)
}
