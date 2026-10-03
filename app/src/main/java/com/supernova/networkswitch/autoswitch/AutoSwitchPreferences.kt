package com.supernova.networkswitch.autoswitch

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Persistent state of the VoLTE auto-switch, kept apart from the main preferences. */
@Singleton
class AutoSwitchPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    val enabled: Flow<Boolean> = dataStore.data
        .map { it[ENABLED_KEY] ?: false }
        .distinctUntilChanged()

    val status: Flow<String> = dataStore.data
        .map { it[STATUS_KEY] ?: "" }
        .distinctUntilChanged()

    suspend fun isEnabled(): Boolean = enabled.first()

    suspend fun setEnabled(value: Boolean) {
        dataStore.edit { it[ENABLED_KEY] = value }
    }

    suspend fun setStatus(value: String) {
        dataStore.edit { it[STATUS_KEY] = value }
    }

    /** The RIL mode to give back once VoLTE is gone, or [ImsSwitchEngine.NO_SAVED_MODE]. */
    suspend fun savedMode(): Int =
        dataStore.data.map { it[SAVED_MODE_KEY] ?: ImsSwitchEngine.NO_SAVED_MODE }.first()

    suspend fun setSavedMode(mode: Int) {
        dataStore.edit { it[SAVED_MODE_KEY] = mode }
    }

    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("volte_auto_switch_enabled")
        val STATUS_KEY = stringPreferencesKey("volte_auto_switch_status")
        val SAVED_MODE_KEY = intPreferencesKey("volte_auto_switch_saved_mode")
    }
}
