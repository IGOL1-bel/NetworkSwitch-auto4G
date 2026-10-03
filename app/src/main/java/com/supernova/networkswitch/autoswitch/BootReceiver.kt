package com.supernova.networkswitch.autoswitch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Brings the VoLTE watcher back after a reboot when the user had it switched on. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var autoSwitchPreferences: AutoSwitchPreferences

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent) // Hilt injects the fields here
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (autoSwitchPreferences.isEnabled()) {
                    ImsAutoSwitchService.start(context.applicationContext)
                }
            } catch (e: Exception) {
                Log.e("NetworkSwitch", "Could not restart the VoLTE watcher after boot", e)
            } finally {
                pending.finish()
            }
        }
    }
}
