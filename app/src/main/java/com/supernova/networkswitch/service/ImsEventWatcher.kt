package com.supernova.networkswitch.service

import android.os.Build
import android.telephony.ims.ImsMmTelManager
import android.telephony.ims.ImsReasonInfo
import android.telephony.ims.ImsRegistrationAttributes
import android.telephony.ims.RegistrationManager
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Subscribes to IMS registration changes through the public [ImsMmTelManager] API
 * (`registerImsRegistrationCallback`) and reports each one through a plain callback.
 *
 * Must run in a privileged process (Shizuku user service or root service): the callback
 * needs READ_PRECISE_PHONE_STATE, which the app process does not hold.
 *
 * It only says that *something* changed. What the change means is left to whoever listens,
 * which asks for the VoLTE state again, because a registration event alone does not say
 * whether voice over LTE is usable yet.
 */
internal class ImsEventWatcher(private val caller: String) {

    private val lock = Any()
    private var executor: ExecutorService? = null
    private var manager: ImsMmTelManager? = null
    private var registrationCallback: RegistrationManager.RegistrationCallback? = null

    /**
     * Starts watching [subId], replacing any earlier registration.
     * Registering delivers the current state once, as an ordinary change.
     *
     * @return whether the registration callback was accepted
     */
    fun start(subId: Int, onChange: () -> Unit): Boolean = synchronized(lock) {
        stopLocked()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.w(TAG, "$caller: IMS callbacks need Android 12 or newer")
            return@synchronized false
        }

        val newExecutor = Executors.newSingleThreadExecutor()
        try {
            val mmTel = ImsMmTelManager.createForSubscriptionId(subId)

            val registration = object : RegistrationManager.RegistrationCallback() {
                override fun onRegistered(attributes: ImsRegistrationAttributes) = onChange()
                override fun onUnregistered(info: ImsReasonInfo) = onChange()
                override fun onTechnologyChangeFailed(imsTransportType: Int, info: ImsReasonInfo) = onChange()
            }
            mmTel.registerImsRegistrationCallback(newExecutor, registration)

            executor = newExecutor
            manager = mmTel
            registrationCallback = registration

            Log.i(TAG, "$caller: watching IMS registration for subId=$subId")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "$caller: IMS registration callback refused for subId=$subId", e)
            newExecutor.shutdown()
            stopLocked()
            false
        }
    }

    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        val mmTel = manager
        val callback = registrationCallback
        if (mmTel != null && callback != null) {
            runCatching { mmTel.unregisterImsRegistrationCallback(callback) }
        }
        executor?.shutdown()
        executor = null
        manager = null
        registrationCallback = null
    }

    private companion object {
        const val TAG = "NetworkSwitch"
    }
}
