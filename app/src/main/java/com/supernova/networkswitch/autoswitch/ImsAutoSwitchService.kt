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
import android.os.PowerManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.supernova.networkswitch.IImsEventListener
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Foreground service that reacts to IMS changes and lets [ImsSwitchEngine] switch the radio
 * to 4G-only while VoLTE is up, then back to the previous mode when it is gone.
 *
 * The privileged process (Shizuku or root) holds `registerImsRegistrationCallback` and
 * MmTel capability callbacks and pings [imsListener] on every change. A ping only wakes this
 * service up: it then asks for the VoLTE state twice, a few seconds apart, and feeds both
 * answers to the engine, so a registration blip does not bounce the radio between modes.
 *
 * A slow watchdog re-registers the callbacks now and then, since they die with the IMS
 * service or the privileged process. If registration is refused, the service falls back to
 * checking every [FALLBACK_POLL_MS] and says so in its status.
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

    /** Whether the privileged process accepted the IMS callbacks. */
    private var listening = false
    private var registeredSubId: Int? = null

    /** Set while the user picked polling; changes the status note. */
    private var pollingSeconds: Int? = null

    /**
     * Keeps the CPU up for the few seconds an event needs. Without it the phone can go back to
     * sleep right after the event woke it, and the second sample (a coroutine timer) only
     * fires on the next wake-up, e.g. when the app is opened. Timed, so never left held.
     */
    private val eventWakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetworkSwitch:volteEvent")
            .apply { setReferenceCounted(false) }
    }

    /** Held for as long as polling mode runs, since a poll timer cannot fire in deep sleep. */
    private val pollWakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetworkSwitch:volteHoldAwake")
            .apply { setReferenceCounted(false) }
    }

    /** Conflated: a burst of IMS events needs one re-check, not one per event. */
    private val events = Channel<Unit>(Channel.CONFLATED)

    private val imsListener = object : IImsEventListener.Stub() {
        override fun onImsChanged() {
            eventWakeLock.acquire(EVENT_WAKE_MS)
            events.trySend(Unit)
        }
    }

    private val engine = ImsSwitchEngine(
        readMode = { networkControlRepository.getCurrentNetworkMode(currentSubId())?.value },
        writeMode = { mode -> applyMode(mode) },
        loadSavedMode = { autoSwitchPreferences.savedMode() },
        storeSavedMode = { autoSwitchPreferences.setSavedMode(it) },
        nameOf = { NetworkMode.fromValue(it)?.displayName ?: it.toString() },
        failureCooldown = FAILURE_COOLDOWN_SAMPLES,
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
        runCatching { if (pollWakeLock.isHeld) pollWakeLock.release() }
        runCatching { if (eventWakeLock.isHeld) eventWakeLock.release() }
    }

    private suspend fun observeEnabled() {
        combine(
            autoSwitchPreferences.enabled,
            autoSwitchPreferences.detectionMode,
            autoSwitchPreferences.pollIntervalSec,
        ) { enabled, mode, interval -> Settings(enabled, mode, interval) }
            .distinctUntilChanged()
            .collectLatest { settings ->
                when {
                    !settings.enabled -> shutDown()
                    settings.mode == DetectionMode.POLLING -> poll(settings.intervalSec)
                    else -> watch()
                }
            }
    }

    /**
     * Evaluates once on entry, then again after every IMS event. With no event for
     * [WATCHDOG_MS] it evaluates anyway and re-registers the callbacks.
     */
    private suspend fun watch() {
        pollingSeconds = null
        lastStatus = ""
        var timedOut = true // first pass registers the callbacks
        while (true) {
            try {
                val subId = currentSubId()
                if (timedOut || !listening || subId != registeredSubId) {
                    register(subId)
                }
                evaluate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "VoLTE check failed", e)
            }
            val waitMs = if (listening) WATCHDOG_MS else FALLBACK_POLL_MS
            timedOut = withTimeoutOrNull(waitMs) {
                events.receive()
                false
            } ?: true
        }
    }

    /**
     * Polling mode: one VoLTE sample every [intervalSec] seconds, and the engine confirms a change
     * after two identical samples in a row. The IMS callbacks are dropped, and the CPU is kept
     * awake while this runs, otherwise the timer stalls whenever the phone sleeps.
     */
    private suspend fun poll(intervalSec: Int) {
        imsStateProvider.stopEvents()
        listening = false
        registeredSubId = null
        pollingSeconds = intervalSec
        lastStatus = ""
        pollWakeLock.acquire()
        try {
            while (true) {
                try {
                    publish(engine.onSample(imsStateProvider.volteState(currentSubId())))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "VoLTE check failed", e)
                }
                delay(intervalSec * 1_000L)
            }
        } finally {
            if (pollWakeLock.isHeld) pollWakeLock.release()
        }
    }

    /**
     * Registering makes the privileged side report the current state right away, so this
     * itself queues one more event. That is harmless: registration only happens on a
     * watchdog timeout or when it was missing, never in reaction to an event.
     */
    private suspend fun register(subId: Int) {
        listening = imsStateProvider.startEvents(subId, imsListener)
        registeredSubId = if (listening) subId else null
        if (!listening) Log.w(TAG, "IMS callbacks unavailable for subId=$subId, polling instead")
    }

    /** Two samples [SETTLE_MS] apart, which is what the engine needs to confirm a change. */
    private suspend fun evaluate() {
        eventWakeLock.acquire(EVENT_WAKE_MS)
        repeat(ImsSwitchEngine.DEFAULT_CONFIRMATIONS) { index ->
            if (index > 0) delay(SETTLE_MS)
            val sample = imsStateProvider.volteState(currentSubId())
            publish(engine.onSample(sample))
        }
    }

    private suspend fun shutDown() {
        try {
            imsStateProvider.stopEvents()
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

    private suspend fun publish(engineStatus: String) {
        val seconds = pollingSeconds
        val status = when {
            seconds != null -> "$engineStatus (polling every $seconds s)"
            listening -> engineStatus
            else -> "$engineStatus (IMS events unavailable, polling)"
        }
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

    private data class Settings(val enabled: Boolean, val mode: DetectionMode, val intervalSec: Int)

    companion object {
        private const val TAG = "NetworkSwitch"
        private const val CHANNEL_ID = "volte_auto_switch"
        private const val NOTIFICATION_ID = 4101

        /** Gap between the two samples taken after an event; also lets capabilities catch up. */
        private const val SETTLE_MS = 4_000L

        /** Longest quiet spell before the callbacks are re-registered as a precaution. */
        private const val WATCHDOG_MS = 120_000L

        /** How often to check when the privileged process refuses the IMS callbacks. */
        private const val FALLBACK_POLL_MS = 10_000L

        /** Covers an evaluation (two samples, a switch and its read-back) with room to spare. */
        private const val EVENT_WAKE_MS = 30_000L

        private const val VERIFY_DELAY_MS = 1_500L

        /** Two evaluations (four samples) is long enough to sit out a failed switch. */
        private const val FAILURE_COOLDOWN_SAMPLES = 4

        private val EQUIVALENT_MODES = setOf(0, 3)

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ImsAutoSwitchService::class.java),
            )
        }
    }
}
