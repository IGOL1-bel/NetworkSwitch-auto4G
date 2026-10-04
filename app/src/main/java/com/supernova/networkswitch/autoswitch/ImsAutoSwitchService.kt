package com.supernova.networkswitch.autoswitch

import android.app.AlarmManager
import kotlinx.coroutines.flow.first
import android.os.SystemClock
import android.media.AudioManager
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
import com.supernova.networkswitch.domain.model.label
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

    @Volatile
    private var destroyed = false
    private var lastStatus = ""

    /** Whether the privileged process accepted the IMS callbacks. */
    private var listening = false
    private var registeredSubId: Int? = null

    /** Set while the user picked polling; changes the status note. */
    private var pollingSeconds: Int? = null

    private var lastProbeAt = SystemClock.elapsedRealtime()

    private var lastCheckShownAt = 0L

    private val alarmManager by lazy { getSystemService(AlarmManager::class.java) }

    private val heartbeatIntent by lazy {
        PendingIntent.getForegroundService(
            this,
            HEARTBEAT_REQUEST_CODE,
            Intent(this, ImsAutoSwitchService::class.java).setAction(ACTION_HEARTBEAT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

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
            if (destroyed) return
            eventWakeLock.acquire(EVENT_WAKE_MS)
            events.trySend(Unit)
        }
    }

    private val engine = ImsSwitchEngine(
        readMode = { networkControlRepository.getCurrentNetworkMode(targetSubId())?.value },
        writeMode = { mode -> applyMode(mode) },
        loadSavedMode = { autoSwitchPreferences.savedMode() },
        storeSavedMode = { mode ->
            val subId = if (mode == ImsSwitchEngine.NO_SAVED_MODE) -1 else currentSubId()
            autoSwitchPreferences.setSavedMode(mode, subId)
        },
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
        } else if (intent?.action == ACTION_HEARTBEAT) {
            // The alarm woke the process: run a check now instead of waiting for a timer that
            // may have been stuck while the phone slept.
            eventWakeLock.acquire(EVENT_WAKE_MS)
            events.trySend(Unit)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyed = true
        scope.cancel()
        // The privileged side keeps the registration until told otherwise; without this every IMS
        // event would keep waking the app. Best effort, on its own scope since ours is cancelled.
        CoroutineScope(Dispatchers.IO).launch { runCatching { imsStateProvider.stopEvents() } }
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
                if (subId >= 0 && (timedOut || !listening || subId != registeredSubId)) {
                    register(subId)
                }
                evaluate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "VoLTE check failed", e)
            }
            // With probing on, a quiet spell must not outlast the probe interval: nothing else
            // would wake the loop while the phone sits on 3G.
            scheduleHeartbeat()
            val waitMs = if (listening) watchdogMs() else FALLBACK_POLL_MS
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
                    sampleOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "VoLTE check failed", e)
                }
                scheduleHeartbeat()
                // Wakes early on the alarm; otherwise the plain interval.
                withTimeoutOrNull(intervalSec * 1_000L) { events.receive() }
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
    /**
     * Safety net against the phone (or the OEM's power manager) letting the app sleep: an alarm
     * that may fire in Doze wakes the service and forces a check. Inexact on purpose, since exact
     * alarms need a permission the user would have to grant; Doze spaces them out to ~9 minutes.
     */
    private fun scheduleHeartbeat() {
        try {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + HEARTBEAT_MS,
                heartbeatIntent,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not schedule the heartbeat alarm", e)
        }
    }

    private suspend fun watchdogMs(): Long {
        if (!autoSwitchPreferences.probeEnabled.first()) return WATCHDOG_MS
        return minOf(WATCHDOG_MS, autoSwitchPreferences.probeIntervalMin.first() * 60_000L)
    }

    private suspend fun register(subId: Int) {
        listening = imsStateProvider.startEvents(subId, imsListener)
        registeredSubId = if (listening) subId else null
        if (!listening) Log.w(TAG, "IMS callbacks unavailable for subId=$subId, polling instead")
        // Registering reports the current state once; the evaluation right after this already
        // covers it, so drop that echo instead of evaluating twice back to back.
        events.tryReceive()
    }

    /** Two samples [SETTLE_MS] apart, which is what the engine needs to confirm a change. */
    private suspend fun evaluate() {
        eventWakeLock.acquire(EVENT_WAKE_MS)
        repeat(ImsSwitchEngine.DEFAULT_CONFIRMATIONS) { index ->
            if (index > 0) delay(SETTLE_MS)
            sampleOnce()
        }
    }

    /**
     * One engine step. Skips when there is no data SIM, and gives a mode back at once if the
     * default data SIM moved away from the one that was switched.
     */
    private suspend fun sampleOnce() {
        val subId = currentSubId()
        if (subId < 0) {
            publish(getString(R.string.status_no_data_sim))
            return
        }
        if (autoSwitchPreferences.hasSavedMode()) {
            val savedSub = autoSwitchPreferences.savedSubId()
            if (savedSub >= 0 && savedSub != subId) {
                engine.restoreIfActive()?.let { publish(describe(it)) }
            }
        }
        val status = engine.onSample(imsStateProvider.volteState(subId))
        publish(describe(status))
        if (status.code == ImsSwitchEngine.Code.NO_VOLTE_IDLE) maybeProbe(subId)
    }

    /**
     * A phone parked on 3G or 2G never registers IMS, so "no VoLTE" can simply mean "not on LTE".
     * When the user allowed it, move to 4G only now and then to find out, and fall back at once
     * if VoLTE is not there. Never during a call: the switch would cut it.
     */
    private suspend fun maybeProbe(subId: Int) {
        if (!autoSwitchPreferences.probeEnabled.first()) return
        val intervalMs = autoSwitchPreferences.probeIntervalMin.first() * 60_000L
        val now = SystemClock.elapsedRealtime()
        if (now - lastProbeAt < intervalMs) return
        if (getSystemService(AudioManager::class.java).mode != AudioManager.MODE_NORMAL) return
        lastProbeAt = now

        eventWakeLock.acquire(PROBE_WAKE_MS)
        publish(getString(R.string.status_probing))
        val result = engine.probe(
            volteState = { imsStateProvider.volteState(subId) },
            attempts = PROBE_ATTEMPTS,
            pause = { delay(PROBE_STEP_MS) },
        )
        if (result != null) publish(describe(result))
    }

    /**
     * Gives the previous mode back, retrying for a while: the privileged side may be starting up
     * or briefly unreachable, and stopping with the mode still saved would strand the phone on
     * 4G only. The service stays up (and cancellable by turning the feature back on) meanwhile.
     */
    private suspend fun shutDown() {
        try {
            imsStateProvider.stopEvents()
            var attempts = 0
            while (autoSwitchPreferences.hasSavedMode() && attempts < RESTORE_ATTEMPTS) {
                engine.restoreIfActive()?.let { Log.i(TAG, "Auto-switch turned off: $it") }
                if (!autoSwitchPreferences.hasSavedMode()) break
                attempts++
                delay(RESTORE_RETRY_MS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Restoring the previous mode failed", e)
        }
        autoSwitchPreferences.setStatus("")
        autoSwitchPreferences.setLastCheck(0L)
        runCatching { alarmManager.cancel(heartbeatIntent) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Applies [mode] and checks it stuck. The privileged services swallow failures, so
     * reading the mode back is the only way to notice a refused change.
     */
    private suspend fun applyMode(mode: Int): Boolean {
        val target = NetworkMode.fromValue(mode) ?: return false
        val subId = targetSubId()
        if (networkControlRepository.setNetworkMode(subId, target).isFailure) return false

        // The radio applies the change asynchronously, so the first read-back can still show the
        // old value: look a few times before calling it a failure.
        for (waitMs in VERIFY_DELAYS_MS) {
            delay(waitMs)
            val now = networkControlRepository.getCurrentNetworkMode(subId)?.value
            // 2G/3G "preferred" (0) and "auto" (3) map to the same radio bits and read back alike.
            if (now == mode || (now != null && now in EQUIVALENT_MODES && mode in EQUIVALENT_MODES)) return true
        }
        return false
    }

    private fun currentSubId(): Int = SubscriptionManager.getDefaultDataSubscriptionId()

    /** The SIM a pending restore belongs to; otherwise the current default data SIM. */
    private suspend fun targetSubId(): Int {
        if (!autoSwitchPreferences.hasSavedMode()) return currentSubId()
        return autoSwitchPreferences.savedSubId().takeIf { it >= 0 } ?: currentSubId()
    }

    private fun modeName(mode: Int): String =
        NetworkMode.fromValue(mode)?.label(this) ?: mode.toString()

    /** Words an engine result for the notification and the settings card. */
    private fun describe(status: ImsSwitchEngine.Status): String {
        val mode = modeName(status.mode)
        return when (status.code) {
            ImsSwitchEngine.Code.STATE_UNKNOWN -> getString(R.string.status_unknown)
            ImsSwitchEngine.Code.WAITING_TO_RETRY -> getString(R.string.status_waiting_retry)
            ImsSwitchEngine.Code.CANNOT_READ_MODE -> getString(R.string.status_cannot_read_mode)
            ImsSwitchEngine.Code.ALREADY_LTE_ONLY -> getString(R.string.status_already_lte_only)
            ImsSwitchEngine.Code.SWITCHED, ImsSwitchEngine.Code.ACTIVE -> getString(R.string.status_active, mode)
            ImsSwitchEngine.Code.SWITCH_FAILED -> getString(R.string.status_switch_failed)
            ImsSwitchEngine.Code.RESTORED -> getString(R.string.status_restored, mode)
            ImsSwitchEngine.Code.RESTORE_FAILED -> getString(R.string.status_restore_failed, mode)
            ImsSwitchEngine.Code.CHANGED_MEANWHILE -> getString(R.string.status_changed_meanwhile)
            ImsSwitchEngine.Code.VOLTE_DETECTED -> getString(R.string.status_volte_detected)
            ImsSwitchEngine.Code.LOST_RESTORING_SOON -> getString(R.string.status_lost_restoring, mode)
            ImsSwitchEngine.Code.NO_VOLTE_IDLE -> getString(R.string.status_no_volte)
            ImsSwitchEngine.Code.PROBE_NO_VOLTE -> getString(R.string.status_probe_no_volte, mode)
        }
    }

    private suspend fun publish(engineStatus: String) {
        val seconds = pollingSeconds
        val status = when {
            seconds != null -> getString(R.string.status_polling_every, engineStatus, seconds)
            listening -> engineStatus
            else -> getString(R.string.status_events_unavailable, engineStatus)
        }
        val now = System.currentTimeMillis()
        val changed = status != lastStatus
        // Even an unchanged status refreshes the time now and then, so a stale notification
        // can only mean the service is not running its checks.
        if (!changed && now - lastCheckShownAt < LAST_CHECK_UPDATE_MS) return
        lastStatus = status
        lastCheckShownAt = now
        if (changed) autoSwitchPreferences.setStatus(status)
        autoSwitchPreferences.setLastCheck(now)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status, now))
    }

    private fun promoteToForeground(): Boolean {
        return try {
            createChannel()
            val notification = buildNotification(lastStatus.ifEmpty { getString(R.string.notification_watching) })
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
            getString(R.string.auto_switch_title),
            NotificationManager.IMPORTANCE_MIN,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, whenMs: Long = System.currentTimeMillis()): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_5g_big)
            .setContentTitle(getString(R.string.auto_switch_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setWhen(whenMs)
            .setShowWhen(true)
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

        /** Waits before each read-back of a freshly written mode. */
        private val VERIFY_DELAYS_MS = longArrayOf(1_500L, 2_500L, 4_000L)

        /** A probe looks for VoLTE this often, this many times: LTE attach plus IMS registration. */
        private const val PROBE_STEP_MS = 3_000L
        private const val PROBE_ATTEMPTS = 8
        private const val PROBE_WAKE_MS = 60_000L

        private const val ACTION_HEARTBEAT = "com.supernova.networkswitch.HEARTBEAT"
        private const val HEARTBEAT_REQUEST_CODE = 7
        private const val HEARTBEAT_MS = 3 * 60_000L

        /** Refresh interval of the "last check" time when nothing else changed. */
        private const val LAST_CHECK_UPDATE_MS = 30_000L

        private const val RESTORE_ATTEMPTS = 6
        private const val RESTORE_RETRY_MS = 10_000L

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
