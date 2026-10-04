package com.supernova.networkswitch.autoswitch

import com.supernova.networkswitch.IImsEventListener
import com.supernova.networkswitch.data.source.RootNetworkControlDataSource
import com.supernova.networkswitch.data.source.ShizukuNetworkControlDataSource
import com.supernova.networkswitch.domain.model.ControlMethod
import com.supernova.networkswitch.domain.repository.PreferencesRepository
import com.supernova.networkswitch.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks the privileged process (Shizuku or root, whichever the user picked) whether VoLTE
 * is currently available. Binder calls into that process can hang, hence the timeout.
 */
@Singleton
class ImsStateProvider @Inject constructor(
    private val rootDataSource: RootNetworkControlDataSource,
    private val shizukuDataSource: ShizukuNetworkControlDataSource,
    private val preferencesRepository: PreferencesRepository,
) {

    /** [ImsSwitchEngine.SAMPLE_VOLTE], [ImsSwitchEngine.SAMPLE_NO_VOLTE] or unknown. */
    suspend fun volteState(subId: Int): Int {
        return timed("volteState") {
            when (preferencesRepository.getControlMethod()) {
                ControlMethod.ROOT -> rootDataSource.getVolteState(subId)
                ControlMethod.SHIZUKU -> shizukuDataSource.getVolteState(subId)
            }
        } ?: ImsSwitchEngine.SAMPLE_UNKNOWN
    }

    suspend fun diagnostics(subId: Int): String {
        return timed("diagnostics") {
            when (preferencesRepository.getControlMethod()) {
                ControlMethod.ROOT -> rootDataSource.getImsDiagnostics(subId)
                ControlMethod.SHIZUKU -> shizukuDataSource.getImsDiagnostics(subId)
            }
        } ?: "Timed out waiting for the privileged service"
    }

    /**
     * Has the privileged process call [listener] whenever IMS registration or MmTel
     * capabilities change on [subId]. Replaces an earlier registration and makes the
     * privileged side report the current state once straight away.
     *
     * @return false when the privileged process could not be reached or refused
     */
    suspend fun startEvents(subId: Int, listener: IImsEventListener): Boolean {
        return timed("startEvents") {
            when (preferencesRepository.getControlMethod()) {
                ControlMethod.ROOT -> rootDataSource.startImsEvents(subId, listener)
                ControlMethod.SHIZUKU -> shizukuDataSource.startImsEvents(subId, listener)
            }
        } ?: false
    }

    /** @return false when the command failed or the privileged process could not be reached */
    suspend fun setAirplaneMode(enabled: Boolean): Boolean {
        return timed("setAirplaneMode") {
            when (preferencesRepository.getControlMethod()) {
                ControlMethod.ROOT -> rootDataSource.setAirplaneMode(enabled)
                ControlMethod.SHIZUKU -> shizukuDataSource.setAirplaneMode(enabled)
            }
        } ?: false
    }

    suspend fun stopEvents() {
        timed("stopEvents") {
            when (preferencesRepository.getControlMethod()) {
                ControlMethod.ROOT -> rootDataSource.stopImsEvents()
                ControlMethod.SHIZUKU -> shizukuDataSource.stopImsEvents()
            }
        }
    }

    /**
     * Runs [block] and waits at most [CALL_TIMEOUT_MS] for it. A binder call that blocks cannot
     * be interrupted by cancelling its coroutine, so it runs on a scope of its own and is simply
     * abandoned when the wait runs out: the caller carries on instead of hanging with it.
     * Calls slower than [SLOW_CALL_MS] and timeouts go to the log.
     */
    private suspend fun <T> timed(name: String, block: suspend () -> T): T? {
        val started = SystemClock.elapsedRealtime()
        val job = callScope.async { block() }
        val result = withTimeoutOrNull(CALL_TIMEOUT_MS) { job.await() }
        val took = SystemClock.elapsedRealtime() - started
        if (!job.isCompleted) {
            AppLog.w("$name: no answer after $took ms, giving up waiting (the call is still running)")
            job.invokeOnCompletion {
                val total = SystemClock.elapsedRealtime() - started
                AppLog.w("$name: the abandoned call finished after $total ms")
            }
        } else if (took >= SLOW_CALL_MS) {
            AppLog.w("$name: slow call, $took ms")
        }
        return result
    }

    private val callScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val CALL_TIMEOUT_MS = 8_000L
        const val SLOW_CALL_MS = 1_000L
    }
}
