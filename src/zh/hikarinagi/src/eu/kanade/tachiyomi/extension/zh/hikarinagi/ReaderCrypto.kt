package eu.kanade.tachiyomi.extension.zh.hikarinagi

import android.util.Base64
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

    private const val DECRYPT_FAILED = "内容解密失败，请刷新后重试"

    private val random = SecureRandom()

    /** The `p` a content request carries; the site encrypts its answer with it. */
    fun newToken(): String = ByteArray(TOKEN_SIZE)
        .also(random::nextBytes)
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }

    /** Decrypts a reader response that was bound to [associatedData]. */
    fun decrypt(token: String, content: ByteArray, associatedData: String): ByteArray {
        if (content.size <= IV_SIZE) throw IOException(DECRYPT_FAILED)
        val tokenBytes = runCatching { Base64.decode(token.padBase64(), Base64.URL_SAFE) }
            .getOrElse { throw IOException(DECRYPT_FAILED) }
        if (tokenBytes.size != TOKEN_SIZE) throw IOException(DECRYPT_FAILED)
        val key = ByteArray(TOKEN_SIZE / 2) { tokenBytes[it].toInt().xor(tokenBytes[it + TOKEN_SIZE / 2].toInt()).toByte() }

        return runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, content, 0, IV_SIZE))
                updateAAD(associatedData.toByteArray(Charsets.UTF_8))
                doFinal(content, IV_SIZE, content.size - IV_SIZE)
            }
        }.getOrElse { throw IOException(DECRYPT_FAILED) }
    }

    private fun String.padBase64() = this + "=".repeat((4 - length % 4) % 4)
}
