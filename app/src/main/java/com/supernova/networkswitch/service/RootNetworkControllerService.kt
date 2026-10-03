package com.supernova.networkswitch.service

import android.content.Intent
import android.os.RemoteException
import com.supernova.networkswitch.IImsEventListener
import com.supernova.networkswitch.IRootController
import com.topjohnwu.superuser.ipc.RootService

/** libsu root service for network control. Runs as root in a separate process. */
class RootNetworkControllerService : RootService() {

    private val imsWatcher = ImsEventWatcher(CALLER)

    override fun onBind(intent: Intent) = object : IRootController.Stub() {

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
    }

    private companion object {
        const val CALLER = "Root"
    }
}
