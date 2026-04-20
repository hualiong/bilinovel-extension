package eu.kanade.tachiyomi.extension.zh.bilinovel

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

class UrlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent.data
        if (uri != null) {
            val intent = Intent("eu.kanade.tachiyomi.SEARCH").apply {
                putExtra("query", uri.toString())
                putExtra("filter", packageName)
            }
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Log.e("BiliNovel", "Unable to launch activity", e)
            }
        }

        finish()
        exitProcess(0)
    }
}
