package com.supernova.networkswitch.service

import android.util.Log
import java.util.concurrent.TimeUnit

/** Shell commands the privileged services (shell or root uid) can run on behalf of the app. */
internal object ShellCommands {

    private const val TAG = "NetworkSwitch"

    /**
     * Turns airplane mode on or off through `cmd connectivity airplane-mode`, which the shell
     * user may call. Power-cycling the radio this way clears a modem that is stuck searching
     * after 4G disappeared under a 4G-only setting.
     *
     * @return whether the command ran and exited normally
     */
    fun setAirplaneMode(enabled: Boolean, caller: String): Boolean = try {
        val process = ProcessBuilder(
            "cmd", "connectivity", "airplane-mode", if (enabled) "enable" else "disable",
        ).redirectErrorStream(true).start()
        val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            Log.w(TAG, "$caller: airplane-mode command timed out")
            false
        } else {
            val output = process.inputStream.bufferedReader().readText().trim()
            val ok = process.exitValue() == 0
            if (!ok) Log.w(TAG, "$caller: airplane-mode command failed: $output")
            ok
        }
    } catch (e: Exception) {
        Log.w(TAG, "$caller: could not run the airplane-mode command", e)
        false
    }

    private const val COMMAND_TIMEOUT_SECONDS = 6L
}
