package com.supernova.networkswitch.data.source

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.supernova.networkswitch.IImsEventListener
import com.supernova.networkswitch.IShizukuController
import com.supernova.networkswitch.domain.model.CompatibilityState
import com.supernova.networkswitch.domain.model.NetworkMode
import com.supernova.networkswitch.service.ShizukuControllerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import com.supernova.networkswitch.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class  ShizukuNetworkControlDataSource @Inject constructor(
    @ApplicationContext private val context: Context
) : NetworkControlDataSource {
    
    private var userService: IShizukuController? = null
    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()
    
    private companion object {
        private const val SHIZUKU_PERMISSION_REQUEST_ID = 8
    }

    /**
     * Asks Shizuku to show its grant dialog. Without this call the app never appears in
     * Shizuku's list, so there is nothing to allow. Does nothing if the user already chose
     * "deny and don't ask again" (the dialog would not show; Shizuku's own app has to be used).
     */
    private fun requestShizukuPermission() {
        try {
            if (Shizuku.isPreV11() || Shizuku.shouldShowRequestPermissionRationale()) return
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_ID)
        } catch (e: Exception) {
            // Shizuku went away between the checks; the caller reports the state anyway.
        }
    }

    override suspend fun checkCompatibility(subId: Int): CompatibilityState {
        return try {
            // Check if Shizuku service is running
            if (!Shizuku.pingBinder()) {
                return CompatibilityState.Incompatible("Shizuku service not running")
            }
            
            // Check if Shizuku permission is granted
            val permission = try {
                Shizuku.checkSelfPermission()
            } catch (e: Exception) {
                PackageManager.PERMISSION_DENIED
            }
            
            if (permission != PackageManager.PERMISSION_GRANTED) {
                requestShizukuPermission()
                return CompatibilityState.PermissionDenied(com.supernova.networkswitch.domain.model.ControlMethod.SHIZUKU)
            }
            
            // Both service and permission checks passed
            CompatibilityState.Compatible
        } catch (e: Exception) {
            CompatibilityState.Incompatible("Shizuku not available: ${e.message}")
        }
    }

    override suspend fun getCurrentNetworkMode(subId: Int): NetworkMode? {
        return if (ensureServiceBinding()) {
            try {
                val modeValue = userService?.getCurrentNetworkMode(subId) ?: -1
                if (modeValue == -1) null else NetworkMode.fromValue(modeValue)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    override suspend fun setNetworkMode(subId: Int, mode: NetworkMode) {
        if (ensureServiceBinding()) {
            try {
                userService?.setNetworkMode(subId, mode.value)
            } catch (e: Exception) {
                throw e
            }
        } else {
            throw SecurityException("Shizuku permission not granted or service binding failed")
        }
    }

    /** 1 = VoLTE available, 0 = not available, -1 = unknown or Shizuku unreachable. */
    suspend fun getVolteState(subId: Int): Int {
        return try {
            if (ensureServiceBinding()) userService?.getVolteState(subId) ?: -1 else -1
        } catch (e: Exception) {
            -1
        }
    }

    suspend fun getImsDiagnostics(subId: Int): String {
        return try {
            if (ensureServiceBinding()) {
                userService?.getImsDiagnostics(subId) ?: "Shizuku service returned nothing"
            } else {
                "Shizuku is not running or permission is not granted"
            }
        } catch (e: Exception) {
            "Shizuku call failed: ${e.message}"
        }
    }

    override fun isConnected(): Boolean = _isConnected.value

    /** Asks the Shizuku service to report IMS changes on [subId] to [listener]. */
    suspend fun startImsEvents(subId: Int, listener: IImsEventListener): Boolean {
        return try {
            if (ensureServiceBinding()) userService?.startImsEvents(subId, listener) ?: false else false
        } catch (e: Exception) {
            false
        }
    }

    suspend fun stopImsEvents() {
        try {
            if (ensureServiceBinding()) userService?.stopImsEvents()
        } catch (e: Exception) {
            // Nothing to stop if the service is already gone.
        }
    }

    override fun resetConnection() {
        userService = null
        _isConnected.value = false
    }

    /**
     * Simple permission and service check without delays or complex logic
     */
    private fun hasPermissionAndService(): Boolean {
        return try {
            // Only check if service is already connected, don't call Shizuku APIs that might block
            userService != null && _isConnected.value
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Ensure service binding for actual operations (not compatibility checks)
     */
    private suspend fun ensureServiceBinding(): Boolean {
        // Check permissions first
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        
        // If service is already connected, return true
        if (userService != null && _isConnected.value) {
            return true
        }
        
        // Bind service asynchronously with timeout
        return try {
            suspendCancellableCoroutine { continuation ->
                val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuControllerService::class.java))
                    .processNameSuffix("service")
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE)
                    .tag("NetworkSwitch")

                val serviceConnection = object : ServiceConnection {
                    override fun onServiceConnected(componentName: ComponentName?, binder: IBinder?) {
                        if (binder != null && binder.pingBinder()) {
                            userService = IShizukuController.Stub.asInterface(binder)
                            _isConnected.value = true
                            if (continuation.isActive) {
                                continuation.resume(true)
                            }
                        } else {
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                    }

                    override fun onServiceDisconnected(componentName: ComponentName?) {
                        userService = null
                        _isConnected.value = false
                    }
                }

                continuation.invokeOnCancellation {
                    userService = null
                    _isConnected.value = false
                }

                try {
                    Shizuku.bindUserService(args, serviceConnection)
                } catch (e: Exception) {
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }
        } catch (e: Exception) {
            _isConnected.value = false
            false
        }
    }
}
