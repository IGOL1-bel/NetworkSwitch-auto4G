package com.supernova.networkswitch.autoswitch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.supernova.networkswitch.R
import com.supernova.networkswitch.domain.model.NetworkMode
import com.supernova.networkswitch.domain.repository.NetworkControlRepository
import com.supernova.networkswitch.presentation.ui.activity.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that polls VoLTE availability and lets [ImsSwitchEngine] switch the
 * radio to 4G-only while VoLTE is up, then back to the previous mode when it is gone.
 *
 * Runs while the feature is enabled in [AutoSwitchPreferences]; flipping it off restores the
 * previous mode (if the engine had switched) and stops the service.
 */
@AndroidEntryPoint
class ImsAutoSwitchService : Service() {

    @Inject
    lateinit var imsStateProvider: ImsStateProvider

    @Inject
    lateinit var networkControlRepository: NetworkControlRepository

    @Inject
    lateinit var autoSwitchPreferences: AutoSwitchPreferences

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watching = false
    private var lastStatus = ""

    private val engine = ImsSwitchEngine(
        readMode = { networkControlRepository.getCurrentNetworkMode(currentSubId())?.value },
        writeMode = { mode -> applyMode(mode) },
        loadSavedMode = { autoSwitchPreferences.savedMode() },
        storeSavedMode = { autoSwitchPreferences.setSavedMode(it) },
        nameOf = { NetworkMode.fromValue(it)?.displayName ?: it.toString() },
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!promoteToForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!watching) {
            watching = true
            scope.launch { observeEnabled() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private suspend fun observeEnabled() {
        autoSwitchPreferences.enabled.collectLatest { enabled ->
            if (enabled) {
                pollLoop()
            } else {
                shutDown()
            }
        }
    }

    private suspend fun pollLoop() {
        while (true) {
            try {
                val sample = imsStateProvider.volteState(currentSubId())
                publish(engine.onSample(sample))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "VoLTE poll failed", e)
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun shutDown() {
        try {
            engine.restoreIfActive()?.let { Log.i(TAG, "Auto-switch turned off: $it") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Restoring the previous mode failed", e)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Applies [mode] and checks it stuck. The privileged services swallow failures, so
     * reading the mode back is the only way to notice a refused change.
     */
    private suspend fun applyMode(mode: Int): Boolean {
        val target = NetworkMode.fromValue(mode) ?: return false
        val subId = currentSubId()
        if (networkControlRepository.setNetworkMode(subId, target).isFailure) return false

        delay(VERIFY_DELAY_MS)
        val now = networkControlRepository.getCurrentNetworkMode(subId)?.value ?: return true
        // 2G/3G "preferred" (0) and "auto" (3) map to the same radio bits and read back alike.
        return now == mode || (now in EQUIVALENT_MODES && mode in EQUIVALENT_MODES)
    }

    private fun currentSubId(): Int = SubscriptionManager.getDefaultDataSubscriptionId()

    private suspend fun publish(status: String) {
        if (status == lastStatus) return
        lastStatus = status
        autoSwitchPreferences.setStatus(status)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun promoteToForeground(): Boolean {
        return try {
            createChannel()
            val notification = buildNotification(lastStatus.ifEmpty { "Watching for VoLTE" })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not start in the foreground", e)
            false
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VoLTE auto-switch",
            NotificationManager.IMPORTANCE_MIN,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_5g_big)
            .setContentTitle("VoLTE auto-switch")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "NetworkSwitch"
        private const val CHANNEL_ID = "volte_auto_switch"
        private const val NOTIFICATION_ID = 4101
        private const val POLL_INTERVAL_MS = 10_000L
        private const val VERIFY_DELAY_MS = 1_500L
        private val EQUIVALENT_MODES = setOf(0, 3)

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ImsAutoSwitchService::class.java),
            )
        }
    }
}
