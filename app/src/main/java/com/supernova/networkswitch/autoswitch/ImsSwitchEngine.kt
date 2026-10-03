package com.supernova.networkswitch.autoswitch

import com.supernova.networkswitch.domain.model.NetworkMode

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
    private val nameOf: (Int) -> String = { it.toString() },
    private val confirmations: Int = DEFAULT_CONFIRMATIONS,
    private val failureCooldown: Int = DEFAULT_FAILURE_COOLDOWN,
) {

    private var lastSample = SAMPLE_UNKNOWN
    private var streak = 0
    private var cooldown = 0

    /** Consumes one sample and returns a short status line describing the situation. */
    suspend fun onSample(sample: Int): String {
        if (sample != SAMPLE_VOLTE && sample != SAMPLE_NO_VOLTE) {
            lastSample = SAMPLE_UNKNOWN
            streak = 0
            return "VoLTE state unknown"
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
            return "Last switch failed, waiting before retrying"
        }
        return if (wantsEnter) enterLteOnly() else restore(saved)
    }

    /**
     * Gives the previous mode back if this engine switched the radio, regardless of VoLTE.
     * Used when the feature is turned off.
     *
     * @return a status line, or null when there was nothing to restore
     */
    suspend fun restoreIfActive(): String? {
        val saved = loadSavedMode()
        if (saved == NO_SAVED_MODE) return null
        return restore(saved)
    }

    private suspend fun enterLteOnly(): String {
        val current = readMode() ?: return "VoLTE on, but the current mode cannot be read - not switching"
        if (current == LTE_ONLY) return "VoLTE on, already 4G only"

        storeSavedMode(current)
        return if (writeMode(LTE_ONLY)) {
            "VoLTE on: 4G only (was ${nameOf(current)})"
        } else {
            storeSavedMode(NO_SAVED_MODE)
            cooldown = failureCooldown
            "VoLTE on, but switching to 4G only failed"
        }
    }

    private suspend fun restore(saved: Int): String {
        return if (writeMode(saved)) {
            storeSavedMode(NO_SAVED_MODE)
            "VoLTE gone: restored ${nameOf(saved)}"
        } else {
            cooldown = failureCooldown
            "VoLTE gone, but restoring ${nameOf(saved)} failed"
        }
    }

    private fun idleStatus(sample: Int, saved: Int): String {
        val active = saved != NO_SAVED_MODE
        return when {
            sample == SAMPLE_VOLTE && active -> "VoLTE on: 4G only (was ${nameOf(saved)})"
            sample == SAMPLE_VOLTE -> "VoLTE detected"
            active -> "VoLTE lost, restoring ${nameOf(saved)} shortly"
            else -> "No VoLTE, network mode untouched"
        }
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
