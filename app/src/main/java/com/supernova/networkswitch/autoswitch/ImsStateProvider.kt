package com.supernova.networkswitch.autoswitch

import com.supernova.networkswitch.data.source.RootNetworkControlDataSource
import com.supernova.networkswitch.data.source.ShizukuNetworkControlDataSource
import com.supernova.networkswitch.domain.model.ControlMethod
import com.supernova.networkswitch.domain.repository.PreferencesRepository
import kotlinx.coroutines.withTimeoutOrNull
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
        return withTimeoutOrNull(CALL_TIMEOUT_MS) {
            when (preferencesRepository.getControlMethod()) {
                ControlMethod.ROOT -> rootDataSource.getVolteState(subId)
                ControlMethod.SHIZUKU -> shizukuDataSource.getVolteState(subId)
            }
        } ?: ImsSwitchEngine.SAMPLE_UNKNOWN
    }

    suspend fun diagnostics(subId: Int): String {
        return withTimeoutOrNull(CALL_TIMEOUT_MS) {
            when (preferencesRepository.getControlMethod()) {
                ControlMethod.ROOT -> rootDataSource.getImsDiagnostics(subId)
                ControlMethod.SHIZUKU -> shizukuDataSource.getImsDiagnostics(subId)
            }
        } ?: "Timed out waiting for the privileged service"
    }

    private companion object {
        const val CALL_TIMEOUT_MS = 8_000L
    }
}
