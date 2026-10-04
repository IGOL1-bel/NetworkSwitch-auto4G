package com.supernova.networkswitch.autoswitch

import com.supernova.networkswitch.util.AppLog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Brings the VoLTE watcher back after a reboot or an app update when the user had it switched on.
 *
 * Uses a Hilt entry point rather than `@AndroidEntryPoint`: a Kotlin receiver has to call
 * `super.onReceive` for the generated injection, and that call hits the abstract
 * `BroadcastReceiver.onReceive` at compile time.
 */
class BootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun autoSwitchPreferences(): AutoSwitchPreferences
    }

    override fun onReceive(context: Context, intent: Intent) {
        // After an app update the system stops the service and sends no boot broadcast.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val appContext = context.applicationContext
        AppLog.init(appContext)
        AppLog.i("BootReceiver: ${intent.action}")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val preferences = EntryPointAccessors
                    .fromApplication(appContext, Dependencies::class.java)
                    .autoSwitchPreferences()
                if (preferences.isEnabled()) {
                    ImsAutoSwitchService.start(appContext)
                }
            } catch (e: Exception) {
                AppLog.e("Could not restart the VoLTE watcher", e)
            } finally {
                pending.finish()
            }
        }
    }
}
