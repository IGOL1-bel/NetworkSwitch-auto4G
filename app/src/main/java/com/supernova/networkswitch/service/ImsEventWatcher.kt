package com.supernova.networkswitch.service

import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.telephony.ims.ImsManager
import android.telephony.ims.ImsMmTelManager
import android.telephony.ims.ImsReasonInfo
import android.telephony.ims.ImsRegistrationAttributes
import android.telephony.ims.RegistrationManager
import android.telephony.ims.feature.MmTelFeature
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Subscribes to IMS registration changes through the public telephony API
 * (`ImsMmTelManager.registerImsRegistrationCallback`) and reports each one through a plain
 * callback. MmTel capability changes are watched too, since they can follow registration
 * without another registration event.
 *
 * Must run in a privileged process (Shizuku user service or root service): the callbacks
 * need READ_PRECISE_PHONE_STATE, which the app process does not hold.
 *
 * The manager comes from `ImsManager.getImsMmTelManager`, which needs a [Context];
 * [contextProvider] supplies the one the privileged service was given. Without one, [start]
 * simply reports failure and the app polls instead.
 *
 * It only says that *something* changed. What the change means is left to whoever listens,
 * which asks for the VoLTE state again, because a registration event alone does not say
 * whether voice over LTE is usable yet.
 */
internal class ImsEventWatcher(
    private val caller: String,
    private val contextProvider: () -> Context?,
) {

    private val lock = Any()
    private var executor: ExecutorService? = null
    private var manager: ImsMmTelManager? = null
    private var registrationCallback: RegistrationManager.RegistrationCallback? = null
    private var capabilityCallback: ImsMmTelManager.CapabilityCallback? = null

    private var rawBinder: IBinder? = null
    private var rawCallback: Binder? = null
    private var rawSubId = -1

    /** Outcome of the last [start], for the diagnostics screen. */
    @Volatile
    var status: String = "not started"
        private set

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
            status = "failed: needs Android 12+"
            return@synchronized false
        }
        val sdkError = startViaSdk(subId, onChange)
        if (sdkError == null) return@synchronized true

        // The SDK path needs an app-like process (TelephonyServiceManager is null in the
        // app_process Shizuku starts), so talk to the phone service directly instead.
        val rawError = startViaTelephonyBinder(subId, onChange)
        if (rawError == null) {
            status = "ok via ITelephony (subId=$subId); SDK path failed: $sdkError"
            Log.i(TAG, "$caller: watching IMS registration through ITelephony for subId=$subId")
            return@synchronized true
        }
        status = "failed: SDK: $sdkError | ITelephony: $rawError"
        false
    }

    /** @return null on success, otherwise a short reason */
    private fun startViaSdk(subId: Int, onChange: () -> Unit): String? {
        val context = contextProvider() ?: return "no Context"
        val newExecutor = Executors.newSingleThreadExecutor()
        return try {
            val imsManager = context.getSystemService(ImsManager::class.java)
                ?: throw IllegalStateException("ImsManager is not available")
            val mmTel = imsManager.getImsMmTelManager(subId)

            val registration = object : RegistrationManager.RegistrationCallback() {
                override fun onRegistered(attributes: ImsRegistrationAttributes) = onChange()
                override fun onUnregistered(info: ImsReasonInfo) = onChange()
                override fun onTechnologyChangeFailed(imsTransportType: Int, info: ImsReasonInfo) = onChange()
            }
            mmTel.registerImsRegistrationCallback(newExecutor, registration)

            executor = newExecutor
            manager = mmTel
            registrationCallback = registration

            try {
                val capability = object : ImsMmTelManager.CapabilityCallback() {
                    override fun onCapabilitiesStatusChanged(capabilities: MmTelFeature.MmTelCapabilities) = onChange()
                }
                mmTel.registerMmTelCapabilityCallback(newExecutor, capability)
                capabilityCallback = capability
            } catch (e: Throwable) {
                Log.w(TAG, "$caller: MmTel capability callback not registered", e)
            }
            status = "ok via SDK (subId=$subId)"
            Log.i(TAG, "$caller: watching IMS registration for subId=$subId")
            null
        } catch (e: Throwable) {
            Log.w(TAG, "$caller: SDK IMS registration refused for subId=$subId", e)
            newExecutor.shutdown()
            stopLocked()
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * Registers a hand-made binder as `IImsRegistrationCallback` straight on the phone service.
     * Every call the phone process makes on it is one-way and only means "IMS state changed", so
     * the arguments are ignored and no AIDL stub is needed. @return null on success
     */
    private fun startViaTelephonyBinder(subId: Int, onChange: () -> Unit): String? {
        return try {
            val binder = TelephonyReflection.telephonyBinder(caller)
                ?: return "phone service not found"
            val registerCode = telephonyTransactionCode("registerImsRegistrationCallback")
            val callback = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code in IBinder.FIRST_CALL_TRANSACTION..IBinder.LAST_CALL_TRANSACTION) {
                        onChange()
                        return true
                    }
                    return super.onTransact(code, data, reply, flags)
                }
            }
            transactRegistration(binder, registerCode, subId, callback)
            rawBinder = binder
            rawCallback = callback
            rawSubId = subId
            null
        } catch (e: Throwable) {
            Log.w(TAG, "$caller: ITelephony IMS registration failed for subId=$subId", e)
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun telephonyTransactionCode(method: String): Int {
        val field = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            .getDeclaredField("TRANSACTION_$method")
        field.isAccessible = true
        return field.getInt(null)
    }

    private fun transactRegistration(binder: IBinder, code: Int, subId: Int, callback: Binder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(TELEPHONY_DESCRIPTOR)
            data.writeInt(subId)
            data.writeStrongBinder(callback)
            binder.transact(code, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        val mmTel = manager
        if (mmTel != null) {
            registrationCallback?.let { runCatching { mmTel.unregisterImsRegistrationCallback(it) } }
            capabilityCallback?.let { runCatching { mmTel.unregisterMmTelCapabilityCallback(it) } }
        }
        val raw = rawBinder
        val rawCb = rawCallback
        if (raw != null && rawCb != null) {
            runCatching {
                transactRegistration(raw, telephonyTransactionCode("unregisterImsRegistrationCallback"), rawSubId, rawCb)
            }
        }
        rawBinder = null
        rawCallback = null
        executor?.shutdown()
        executor = null
        manager = null
        registrationCallback = null
        capabilityCallback = null
    }

    private companion object {
        const val TAG = "NetworkSwitch"
        const val TELEPHONY_DESCRIPTOR = "com.android.internal.telephony.ITelephony"
    }
}
