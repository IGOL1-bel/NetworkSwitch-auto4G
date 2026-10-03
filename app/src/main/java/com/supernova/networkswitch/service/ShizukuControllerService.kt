package com.supernova.networkswitch.service

import android.content.Context
import android.os.RemoteException
import android.util.Log
import androidx.annotation.Keep
import com.supernova.networkswitch.IImsEventListener
import com.supernova.networkswitch.IShizukuController

/**
 * Shizuku user service for network control.
 *
 * Runs as shell (or root) in a separate process that Shizuku starts via app_process,
 * which is not subject to non-SDK interface restrictions.
 */
class ShizukuControllerService() : IShizukuController.Stub() {

    /** Shizuku instantiates the service through this constructor. */
    @Keep
    constructor(context: Context) : this() {
        this.context = context
    }

    private var context: Context? = null

    private val imsWatcher = ImsEventWatcher(CALLER) { context }

    override fun compatibilityCheck(subId: Int): Boolean =
        getCurrentNetworkMode(subId) != -1

    override fun getCurrentNetworkMode(subId: Int): Int =
        TelephonyReflection.getCurrentNetworkMode(subId, CALLER)

    override fun setNetworkMode(subId: Int, networkMode: Int) {
        TelephonyReflection.setNetworkMode(subId, networkMode, CALLER)
    }

    override fun getVolteState(subId: Int): Int =
        TelephonyReflection.getVolteState(subId, CALLER)

    override fun getImsDiagnostics(subId: Int): String =
        TelephonyReflection.describeIms(subId, CALLER)

    override fun startImsEvents(subId: Int, listener: IImsEventListener?): Boolean {
        if (listener == null) return false
        return imsWatcher.start(subId) {
            try {
                listener.onImsChanged()
            } catch (e: RemoteException) {
                // The app process is gone; nobody is left to tell.
                imsWatcher.stop()
            }
        }
    }

    override fun stopImsEvents() {
        imsWatcher.stop()
    }

    override fun destroy() {
        imsWatcher.stop()
        Log.d(TAG, "ShizukuControllerService: destroy")
    }

    private companion object {
        const val TAG = "NetworkSwitch"
        const val CALLER = "Shizuku"
    }
}
