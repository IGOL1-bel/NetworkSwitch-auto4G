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

/** How the service finds out that VoLTE appeared or went away. */
enum class DetectionMode { EVENTS, POLLING }

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

    val detectionMode: Flow<DetectionMode> = dataStore.data
        .map { if (it[MODE_KEY] == POLLING_VALUE) DetectionMode.POLLING else DetectionMode.EVENTS }
        .distinctUntilChanged()

    val pollIntervalSec: Flow<Int> = dataStore.data
        .map { (it[POLL_INTERVAL_KEY] ?: DEFAULT_POLL_SEC).coerceIn(MIN_POLL_SEC, MAX_POLL_SEC) }
        .distinctUntilChanged()

    suspend fun setDetectionMode(mode: DetectionMode) {
        dataStore.edit { it[MODE_KEY] = if (mode == DetectionMode.POLLING) POLLING_VALUE else EVENTS_VALUE }
    }

    suspend fun setPollIntervalSec(seconds: Int) {
        dataStore.edit { it[POLL_INTERVAL_KEY] = seconds.coerceIn(MIN_POLL_SEC, MAX_POLL_SEC) }
    }

    val probeEnabled: Flow<Boolean> = dataStore.data
        .map { it[PROBE_ENABLED_KEY] ?: false }
        .distinctUntilChanged()

    val probeIntervalMin: Flow<Int> = dataStore.data
        .map { (it[PROBE_INTERVAL_KEY] ?: DEFAULT_PROBE_MIN).coerceIn(MIN_PROBE_MIN, MAX_PROBE_MIN) }
        .distinctUntilChanged()

    suspend fun setProbeEnabled(value: Boolean) {
        dataStore.edit { it[PROBE_ENABLED_KEY] = value }
    }

    suspend fun setProbeIntervalMin(minutes: Int) {
        dataStore.edit { it[PROBE_INTERVAL_KEY] = minutes.coerceIn(MIN_PROBE_MIN, MAX_PROBE_MIN) }
    }

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

    /** [subId] is the subscription the mode belongs to, so a later default-data change cannot misdirect the restore. */
    suspend fun setSavedMode(mode: Int, subId: Int) {
        dataStore.edit {
            it[SAVED_MODE_KEY] = mode
            it[SAVED_SUB_ID_KEY] = subId
        }
    }

    /** Subscription the saved mode was taken from, or -1 when unknown. */
    suspend fun savedSubId(): Int = dataStore.data.map { it[SAVED_SUB_ID_KEY] ?: -1 }.first()

    suspend fun hasSavedMode(): Boolean = savedMode() != ImsSwitchEngine.NO_SAVED_MODE

    companion object {
        const val MIN_POLL_SEC = 5
        const val MAX_POLL_SEC = 120
        const val DEFAULT_POLL_SEC = 10
        const val MIN_PROBE_MIN = 1
        const val MAX_PROBE_MIN = 60
        const val DEFAULT_PROBE_MIN = 5
        private const val EVENTS_VALUE = "events"
        private const val POLLING_VALUE = "polling"

        private val PROBE_ENABLED_KEY = booleanPreferencesKey("volte_auto_switch_probe_enabled")
        private val PROBE_INTERVAL_KEY = intPreferencesKey("volte_auto_switch_probe_interval_min")
        private val MODE_KEY = stringPreferencesKey("volte_auto_switch_detection_mode")
        private val POLL_INTERVAL_KEY = intPreferencesKey("volte_auto_switch_poll_interval_sec")
        private val ENABLED_KEY = booleanPreferencesKey("volte_auto_switch_enabled")
        private val STATUS_KEY = stringPreferencesKey("volte_auto_switch_status")
        private val SAVED_SUB_ID_KEY = intPreferencesKey("volte_auto_switch_saved_sub_id")
        private val SAVED_MODE_KEY = intPreferencesKey("volte_auto_switch_saved_mode")
    }
}
