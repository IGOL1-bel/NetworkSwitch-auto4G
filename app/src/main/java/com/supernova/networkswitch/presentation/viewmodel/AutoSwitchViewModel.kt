package com.supernova.networkswitch.presentation.viewmodel

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
            // Turning it off needs no call: the running service watches the flag itself.
            if (value) ImsAutoSwitchService.start(context)
        }
    }

    fun runDiagnostics() {
        if (diagnosticsRunning) return
        viewModelScope.launch {
            diagnosticsRunning = true
            diagnostics = try {
                imsStateProvider.diagnostics(SubscriptionManager.getDefaultDataSubscriptionId())
            } catch (e: Exception) {
                "Diagnostics failed: ${e.message}"
            }
            diagnosticsRunning = false
        }
    }
}
