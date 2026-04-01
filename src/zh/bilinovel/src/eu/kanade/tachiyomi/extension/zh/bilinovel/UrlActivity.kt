package eu.kanade.tachiyomi.extension.zh.bilinovel

import android.app.Activity
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import kotlin.system.exitProcess

class UrlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        try {
            if (uri != null) {
                val mainIntent = Intent(Intent.ACTION_SEARCH).apply {
                    putExtra(SearchManager.QUERY, uri.toString())
                    // setPackage("app.mihon")
                    // flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(mainIntent)
            }
        } catch (e: ActivityNotFoundException) {
            Log.e("BiliNovelUrlActivity", "Mihon not installed", e)
            Toast.makeText(this, "Mihon未安装", Toast.LENGTH_SHORT).show()
        } finally {
            finish()
            exitProcess(0)
        }
    }
}
