package codes.t3.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import codes.t3.android.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class FollowUpBehavior { Queue, Steer }

enum class ColorTheme { MaterialYou, T3 }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val colorTheme: ColorTheme = ColorTheme.MaterialYou,
    val pureBlack: Boolean = false,
    val followUp: FollowUpBehavior = FollowUpBehavior.Queue,
    val enterToSend: Boolean = false,
    val wrapCode: Boolean = false,
    val showSettled: Boolean = true,
    /** Upstream keeps the Plan/Build toggle behind a legacy switch; `/plan` and `/default` still work. */
    val legacyPlanMode: Boolean = false,
)

class AppSettingsRepository(private val store: DataStore<Preferences>) {
    private object Keys {
        val themeMode = stringPreferencesKey("themeMode")
        val colorTheme = stringPreferencesKey("colorTheme")
        val pureBlack = booleanPreferencesKey("pureBlack")
        val followUp = stringPreferencesKey("followUp")
        val enterToSend = booleanPreferencesKey("enterToSend")
        val wrapCode = booleanPreferencesKey("wrapCode")
        val showSettled = booleanPreferencesKey("showSettled")
        val legacyPlanMode = booleanPreferencesKey("legacyPlanMode")
    }

    val settings: Flow<AppSettings> = store.data.map { read(it) }

    private fun read(p: Preferences) = AppSettings(
        themeMode = p[Keys.themeMode].enumOr(ThemeMode.System),
        colorTheme = p[Keys.colorTheme].enumOr(ColorTheme.MaterialYou),
        pureBlack = p[Keys.pureBlack] ?: false,
        followUp = p[Keys.followUp].enumOr(FollowUpBehavior.Queue),
        enterToSend = p[Keys.enterToSend] ?: false,
        wrapCode = p[Keys.wrapCode] ?: false,
        showSettled = p[Keys.showSettled] ?: true,
        legacyPlanMode = p[Keys.legacyPlanMode] ?: false,
    )

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val next = transform(read(p))
            p[Keys.themeMode] = next.themeMode.name
            p[Keys.colorTheme] = next.colorTheme.name
            p[Keys.pureBlack] = next.pureBlack
            p[Keys.followUp] = next.followUp.name
            p[Keys.enterToSend] = next.enterToSend
            p[Keys.wrapCode] = next.wrapCode
            p[Keys.showSettled] = next.showSettled
            p[Keys.legacyPlanMode] = next.legacyPlanMode
        }
    }
}

private inline fun <reified E : Enum<E>> String?.enumOr(default: E): E =
    this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
