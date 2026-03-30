package eu.kanade.tachiyomi.source

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen

@Suppress("unused")
interface ConfigurableSource : Source {
    /**
     * Gets instance of [SharedPreferences] scoped to the specific source.
     *
     * @since extensions-lib 1.5
     */
    fun getSourcePreferences(): SharedPreferences = throw Exception("Stub!")

    fun setupPreferenceScreen(screen: PreferenceScreen)
}