package com.supernova.networkswitch.presentation.viewmodel

import com.supernova.networkswitch.R
import android.content.Context
import android.telephony.SubscriptionManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.supernova.networkswitch.autoswitch.AutoSwitchPreferences
import com.supernova.networkswitch.autoswitch.DetectionMode
import com.supernova.networkswitch.autoswitch.ImsAutoSwitchService
import com.supernova.networkswitch.autoswitch.ImsStateProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Drives the VoLTE auto-switch card in Settings. */
@HiltViewModel
class AutoSwitchViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val autoSwitchPreferences: AutoSwitchPreferences,
    private val imsStateProvider: ImsStateProvider,
) : ViewModel() {

    val enabled: StateFlow<Boolean> = autoSwitchPreferences.enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val status: StateFlow<String> = autoSwitchPreferences.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val detectionMode: StateFlow<DetectionMode> = autoSwitchPreferences.detectionMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DetectionMode.EVENTS)

    val pollIntervalSec: StateFlow<Int> = autoSwitchPreferences.pollIntervalSec
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AutoSwitchPreferences.DEFAULT_POLL_SEC)

    val lastCheck: StateFlow<Long> = autoSwitchPreferences.lastCheck
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val restoreMode: StateFlow<Int> = autoSwitchPreferences.restoreMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AutoSwitchPreferences.RESTORE_PREVIOUS)

    val actionModeA: StateFlow<Int> = autoSwitchPreferences.actionModeA
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AutoSwitchPreferences.DEFAULT_ACTION_A)

    val actionModeB: StateFlow<Int> = autoSwitchPreferences.actionModeB
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AutoSwitchPreferences.DEFAULT_ACTION_B)

    fun setRestoreMode(mode: Int) {
        viewModelScope.launch { autoSwitchPreferences.setRestoreMode(mode) }
    }

    fun setActionModeA(mode: Int) {
        viewModelScope.launch { autoSwitchPreferences.setActionModeA(mode) }
    }

    fun setActionModeB(mode: Int) {
        viewModelScope.launch { autoSwitchPreferences.setActionModeB(mode) }
    }

    val probeEnabled: StateFlow<Boolean> = autoSwitchPreferences.probeEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val probeIntervalMin: StateFlow<Int> = autoSwitchPreferences.probeIntervalMin
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AutoSwitchPreferences.DEFAULT_PROBE_MIN)

    fun setProbeEnabled(value: Boolean) {
        viewModelScope.launch { autoSwitchPreferences.setProbeEnabled(value) }
    }

    fun setProbeIntervalMin(minutes: Int) {
        viewModelScope.launch { autoSwitchPreferences.setProbeIntervalMin(minutes) }
    }

    fun setDetectionMode(mode: DetectionMode) {
        viewModelScope.launch { autoSwitchPreferences.setDetectionMode(mode) }
    }

    fun setPollIntervalSec(seconds: Int) {
        viewModelScope.launch { autoSwitchPreferences.setPollIntervalSec(seconds) }
    }

    var diagnostics by mutableStateOf<String?>(null)
        private set

    var diagnosticsRunning by mutableStateOf(false)
        private set

    fun setEnabled(value: Boolean) {
        viewModelScope.launch {
            autoSwitchPreferences.setEnabled(value)
            // Turning it off is handled by the running service, which restores the previous mode.
            // If the service was killed meanwhile, start it so it can still give the mode back.
            if (value || autoSwitchPreferences.hasSavedMode()) ImsAutoSwitchService.start(context)
        }
    }

    /** Called when the app opens: brings the service back if the system or the user killed it. */
    fun ensureRunning() {
        viewModelScope.launch {
            if (autoSwitchPreferences.isEnabled() || autoSwitchPreferences.hasSavedMode()) {
                ImsAutoSwitchService.start(context)
            }
        }
    }

    fun runDiagnostics() {
        if (diagnosticsRunning) return
        viewModelScope.launch {
            diagnosticsRunning = true
            diagnostics = try {
                imsStateProvider.diagnostics(SubscriptionManager.getDefaultDataSubscriptionId())
            } catch (e: Exception) {
                context.getString(R.string.diag_failed, e.message)
            }
            diagnosticsRunning = false
        }
    }
}
