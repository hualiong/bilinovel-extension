package eu.kanade.tachiyomi.network

import android.content.Context
import okhttp3.OkHttpClient

@Suppress("unused")
class NetworkHelper(context: Context) {

    val client: OkHttpClient = throw Exception("Stub!")

    /**
     * @deprecated Since extension-lib 1.5
     */
    @Deprecated("The regular client handles Cloudflare by default")
    val cloudflareClient: OkHttpClient = throw Exception("Stub!")
}