package eu.kanade.tachiyomi.extension.zh.bilinovel

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class ChapterInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val origin = chain.request()
        return chain.proceed(origin.newBuilder().addHeader("Cookie", "night=1").build())
            .also { resp ->
                if (resp.isRedirect && resp.header("Location")?.indexOf("linovelib") != -1) {
                    resp.close()
                    throw IOException("不支持PC端查看，请在设置中更换移动端UA标识")
                }
            }
    }
}
