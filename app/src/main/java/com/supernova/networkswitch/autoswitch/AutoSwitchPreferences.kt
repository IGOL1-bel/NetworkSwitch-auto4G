package com.supernova.networkswitch.autoswitch

import androidx.datastore.preferences.core.longPreferencesKey
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

    /** RIL mode to return to when VoLTE is gone, or [RESTORE_PREVIOUS] for "whatever was set before". */
    val restoreMode: Flow<Int> = dataStore.data
        .map { it[RESTORE_MODE_KEY] ?: RESTORE_PREVIOUS }
        .distinctUntilChanged()

    suspend fun restoreModeNow(): Int = restoreMode.first()

    suspend fun setRestoreMode(mode: Int) {
        dataStore.edit { it[RESTORE_MODE_KEY] = mode }
    }

    /** The two modes offered as buttons in the notification. */
    val actionModeA: Flow<Int> = dataStore.data
        .map { it[ACTION_A_KEY] ?: DEFAULT_ACTION_A }
        .distinctUntilChanged()

    val actionModeB: Flow<Int> = dataStore.data
        .map { it[ACTION_B_KEY] ?: DEFAULT_ACTION_B }
        .distinctUntilChanged()

    suspend fun setActionModeA(mode: Int) {
        dataStore.edit { it[ACTION_A_KEY] = mode }
    }

    suspend fun setActionModeB(mode: Int) {
        dataStore.edit { it[ACTION_B_KEY] = mode }
    }

    suspend fun isEnabled(): Boolean = enabled.first()

    suspend fun setEnabled(value: Boolean) {
        dataStore.edit { it[ENABLED_KEY] = value }
    }

    /** Wall-clock time of the last VoLTE check, 0 before the first one. Tells a sleeping service from an idle one. */
    val lastCheck: Flow<Long> = dataStore.data
        .map { it[LAST_CHECK_KEY] ?: 0L }
        .distinctUntilChanged()

    suspend fun setLastCheck(millis: Long) {
        dataStore.edit { it[LAST_CHECK_KEY] = millis }
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

        const val RESTORE_PREVIOUS = -1

        /** 4G only, and 2G/3G/4G. */
        const val DEFAULT_ACTION_A = 11
        const val DEFAULT_ACTION_B = 9
        private const val EVENTS_VALUE = "events"
        private const val POLLING_VALUE = "polling"

        private val PROBE_ENABLED_KEY = booleanPreferencesKey("volte_auto_switch_probe_enabled")
        private val PROBE_INTERVAL_KEY = intPreferencesKey("volte_auto_switch_probe_interval_min")
        private val LAST_CHECK_KEY = longPreferencesKey("volte_auto_switch_last_check")
        private val RESTORE_MODE_KEY = intPreferencesKey("volte_auto_switch_restore_mode")
        private val ACTION_A_KEY = intPreferencesKey("volte_auto_switch_action_a")
        private val ACTION_B_KEY = intPreferencesKey("volte_auto_switch_action_b")
        private val MODE_KEY = stringPreferencesKey("volte_auto_switch_detection_mode")
        private val POLL_INTERVAL_KEY = intPreferencesKey("volte_auto_switch_poll_interval_sec")
        private val ENABLED_KEY = booleanPreferencesKey("volte_auto_switch_enabled")
        private val STATUS_KEY = stringPreferencesKey("volte_auto_switch_status")
        private val SAVED_SUB_ID_KEY = intPreferencesKey("volte_auto_switch_saved_sub_id")
        private val SAVED_MODE_KEY = intPreferencesKey("volte_auto_switch_saved_mode")
    }
}
