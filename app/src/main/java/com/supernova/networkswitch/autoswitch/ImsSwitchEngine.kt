package com.supernova.networkswitch.autoswitch

import com.supernova.networkswitch.domain.model.NetworkMode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Decides when to force 4G-only and when to give the previous mode back.
 *
 * Fed one VoLTE sample per poll: [SAMPLE_VOLTE], [SAMPLE_NO_VOLTE] or anything else for
 * "unknown". A change only counts once [confirmations] identical samples in a row have been
 * seen, so a brief IMS re-registration does not bounce the radio between modes.
 *
 * The mode to return to is persisted through [storeSavedMode] *before* switching, so a
 * killed process or a reboot cannot leave the phone stuck on 4G-only: whatever starts next
 * sees the saved mode and restores it once VoLTE is gone.
 *
 * All I/O is injected, which keeps this class free of Android and testable on the JVM.
 *
 * @param readMode current RIL network mode, or null when it cannot be read
 * @param writeMode applies a RIL network mode, true when it took effect
 * @param loadSavedMode the persisted previous mode, [NO_SAVED_MODE] when nothing is saved
 * @param storeSavedMode persists the previous mode, or [NO_SAVED_MODE] to clear it
 */
class ImsSwitchEngine(
    private val readMode: suspend () -> Int?,
    private val writeMode: suspend (Int) -> Boolean,
    private val loadSavedMode: suspend () -> Int,
    private val storeSavedMode: suspend (Int) -> Unit,
    private val confirmations: Int = DEFAULT_CONFIRMATIONS,
    private val failureCooldown: Int = DEFAULT_FAILURE_COOLDOWN,
) {

    private var lastSample = SAMPLE_UNKNOWN
    private var streak = 0
    private var cooldown = 0

    /** Consumes one sample and returns what the situation is, for the caller to word. */
    suspend fun onSample(sample: Int): Status {
        if (sample != SAMPLE_VOLTE && sample != SAMPLE_NO_VOLTE) {
            lastSample = SAMPLE_UNKNOWN
            streak = 0
            return Status(Code.STATE_UNKNOWN)
        }
        streak = if (sample == lastSample) minOf(streak + 1, confirmations) else 1
        lastSample = sample

        val saved = loadSavedMode()
        val active = saved != NO_SAVED_MODE
        val wantsEnter = sample == SAMPLE_VOLTE && !active
        val wantsRestore = sample == SAMPLE_NO_VOLTE && active

        if (streak < confirmations || !(wantsEnter || wantsRestore)) {
            return idleStatus(sample, saved)
        }
        if (cooldown > 0) {
            cooldown--
            return Status(Code.WAITING_TO_RETRY)
        }
        return if (wantsEnter) enterLteOnly() else restore(saved)
    }

    /**
     * Gives the previous mode back if this engine switched the radio, regardless of VoLTE.
     * Used when the feature is turned off.
     *
     * @return what happened, or null when there was nothing to restore
     */
    suspend fun restoreIfActive(): Status? {
        val saved = loadSavedMode()
        if (saved == NO_SAVED_MODE) return null
        return restore(saved)
    }

    /**
     * Saving the mode, switching and the follow-up bookkeeping run to the end even if the
     * caller is cancelled (a settings change cancels the service's collector): stopping between
     * "saved" and "written" would leave the stored state out of step with the radio.
     */
    private suspend fun enterLteOnly(): Status = withContext(NonCancellable) {
        val current = readMode()
            ?: return@withContext Status(Code.CANNOT_READ_MODE)
        if (current == LTE_ONLY) return@withContext Status(Code.ALREADY_LTE_ONLY)

        storeSavedMode(current)
        if (writeMode(LTE_ONLY) || readMode() == LTE_ONLY) {
            // The second check covers a write that took effect but was reported as failed,
            // e.g. a read-back that lagged behind: forgetting the saved mode then would strand
            // the phone on 4G only.
            Status(Code.SWITCHED, current)
        } else {
            storeSavedMode(NO_SAVED_MODE)
            cooldown = failureCooldown
            Status(Code.SWITCH_FAILED)
        }
    }

    private suspend fun restore(saved: Int): Status = withContext(NonCancellable) {
        val current = readMode()
        if (current != null && current != LTE_ONLY) {
            // Someone else changed the mode while we were holding 4G only (the user, the tile,
            // a carrier update). That choice wins; overwriting it with a stale mode would be wrong.
            storeSavedMode(NO_SAVED_MODE)
            return@withContext Status(Code.CHANGED_MEANWHILE)
        }
        if (writeMode(saved) || readMode() == saved) {
            storeSavedMode(NO_SAVED_MODE)
            Status(Code.RESTORED, saved)
        } else {
            cooldown = failureCooldown
            Status(Code.RESTORE_FAILED, saved)
        }
    }

    private fun idleStatus(sample: Int, saved: Int): Status {
        val active = saved != NO_SAVED_MODE
        return when {
            sample == SAMPLE_VOLTE && active -> Status(Code.ACTIVE, saved)
            sample == SAMPLE_VOLTE -> Status(Code.VOLTE_DETECTED)
            active -> Status(Code.LOST_RESTORING_SOON, saved)
            else -> Status(Code.NO_VOLTE_IDLE)
        }
    }

    /** What the engine found or did. [mode] is the RIL mode the text refers to, when there is one. */
    data class Status(val code: Code, val mode: Int = NO_SAVED_MODE)

    enum class Code {
        STATE_UNKNOWN,
        WAITING_TO_RETRY,
        CANNOT_READ_MODE,
        ALREADY_LTE_ONLY,
        SWITCHED,
        SWITCH_FAILED,
        RESTORED,
        RESTORE_FAILED,
        CHANGED_MEANWHILE,
        ACTIVE,
        VOLTE_DETECTED,
        LOST_RESTORING_SOON,
        NO_VOLTE_IDLE,
    }

    companion object {
        const val SAMPLE_VOLTE = 1
        const val SAMPLE_NO_VOLTE = 0
        const val SAMPLE_UNKNOWN = -1

        const val NO_SAVED_MODE = -1

        const val DEFAULT_CONFIRMATIONS = 2

        /** Samples to sit out after a failed switch before trying again. */
        const val DEFAULT_FAILURE_COOLDOWN = 6

        private val LTE_ONLY = NetworkMode.LTE_ONLY.value
    }
}
