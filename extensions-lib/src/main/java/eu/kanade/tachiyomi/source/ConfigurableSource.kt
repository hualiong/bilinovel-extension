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

@Suppress("unused")
fun ConfigurableSource.preferenceKey(): String = "source_$id"

// TODO: use getSourcePreferences once all extensions are on ext-lib 1.5
@Suppress("unused")
fun ConfigurableSource.sourcePreferences(): SharedPreferences = throw Exception("Stub!")
