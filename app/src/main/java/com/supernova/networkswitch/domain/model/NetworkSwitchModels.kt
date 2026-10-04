package com.supernova.networkswitch.domain.model

import android.content.Context
import androidx.annotation.StringRes
import com.supernova.networkswitch.R

enum class ControlMethod {
    ROOT,
    SHIZUKU
}

/** [displayName] is the English name (kept for logs and tests); [labelRes] is the translated one. */
enum class NetworkMode(val displayName: String, val value: Int, @StringRes val labelRes: Int) {
    // Basic modes
    GSM_ONLY("2G Only (GSM)", 1, R.string.mode_gsm_only),
    WCDMA_ONLY("3G Only (WCDMA)", 2, R.string.mode_wcdma_only),
    LTE_ONLY("4G Only (LTE)", 11, R.string.mode_lte_only),
    NR_ONLY("5G Only (NR)", 23, R.string.mode_nr_only),
    
    // Preferred modes  
    WCDMA_PREF("2G/3G (3G Preferred)", 0, R.string.mode_wcdma_pref),
    GSM_UMTS("2G/3G (Auto)", 3, R.string.mode_gsm_umts),
    
    // Combined modes
    LTE_GSM_WCDMA("2G/3G/4G (LTE/GSM/WCDMA)", 9, R.string.mode_lte_gsm_wcdma),
    LTE_WCDMA("3G/4G (LTE/WCDMA)", 12, R.string.mode_lte_wcdma),
    NR_LTE("4G/5G (NR/LTE)", 24, R.string.mode_nr_lte),
    NR_LTE_GSM_WCDMA("2G/3G/4G/5G (NR/LTE/GSM/WCDMA)", 26, R.string.mode_nr_lte_gsm_wcdma),
    NR_LTE_WCDMA("3G/4G/5G (NR/LTE/WCDMA)", 28, R.string.mode_nr_lte_wcdma),
    
    // CDMA modes for US carriers
    CDMA("CDMA (Auto)", 4, R.string.mode_cdma),
    CDMA_NO_EVDO("CDMA Only", 5, R.string.mode_cdma_no_evdo),
    EVDO_NO_CDMA("EvDo Only", 6, R.string.mode_evdo_no_cdma),
    LTE_CDMA_EVDO("CDMA/4G (LTE/CDMA/EvDo)", 8, R.string.mode_lte_cdma_evdo),
    NR_LTE_CDMA_EVDO("CDMA/4G/5G (NR/LTE/CDMA/EvDo)", 25, R.string.mode_nr_lte_cdma_evdo),
    
    // Global modes
    GLOBAL("Global (All)", 7, R.string.mode_global),
    LTE_CDMA_EVDO_GSM_WCDMA("Global 4G (LTE/CDMA/EvDo/GSM/WCDMA)", 10, R.string.mode_lte_cdma_evdo_gsm_wcdma),
    NR_LTE_CDMA_EVDO_GSM_WCDMA("Global 5G (NR/LTE/CDMA/EvDo/GSM/WCDMA)", 27, R.string.mode_nr_lte_cdma_evdo_gsm_wcdma),
    
    // TD-SCDMA modes for China
    TDSCDMA_ONLY("TD-SCDMA Only", 13, R.string.mode_tdscdma_only),
    TDSCDMA_WCDMA("3G (TD-SCDMA/WCDMA)", 14, R.string.mode_tdscdma_wcdma),
    LTE_TDSCDMA("4G (LTE/TD-SCDMA)", 15, R.string.mode_lte_tdscdma),
    TDSCDMA_GSM("2G/TD-SCDMA", 16, R.string.mode_tdscdma_gsm),
    LTE_TDSCDMA_GSM("2G/4G (LTE/TD-SCDMA/GSM)", 17, R.string.mode_lte_tdscdma_gsm),
    TDSCDMA_GSM_WCDMA("2G/3G (TD-SCDMA/GSM/WCDMA)", 18, R.string.mode_tdscdma_gsm_wcdma),
    LTE_TDSCDMA_WCDMA("3G/4G (LTE/TD-SCDMA/WCDMA)", 19, R.string.mode_lte_tdscdma_wcdma),
    LTE_TDSCDMA_GSM_WCDMA("2G/3G/4G (LTE/TD-SCDMA/GSM/WCDMA)", 20, R.string.mode_lte_tdscdma_gsm_wcdma),
    TDSCDMA_CDMA_EVDO_GSM_WCDMA("Global 3G (TD-SCDMA/CDMA/EvDo/GSM/WCDMA)", 21, R.string.mode_tdscdma_cdma_evdo_gsm_wcdma),
    LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA("Global 4G (LTE/TD-SCDMA/CDMA/EvDo/GSM/WCDMA)", 22, R.string.mode_lte_tdscdma_cdma_evdo_gsm_wcdma),
    NR_LTE_TDSCDMA("4G/5G (NR/LTE/TD-SCDMA)", 29, R.string.mode_nr_lte_tdscdma),
    NR_LTE_TDSCDMA_GSM("2G/4G/5G (NR/LTE/TD-SCDMA/GSM)", 30, R.string.mode_nr_lte_tdscdma_gsm),
    NR_LTE_TDSCDMA_WCDMA("3G/4G/5G (NR/LTE/TD-SCDMA/WCDMA)", 31, R.string.mode_nr_lte_tdscdma_wcdma),
    NR_LTE_TDSCDMA_GSM_WCDMA("2G/3G/4G/5G (NR/LTE/TD-SCDMA/GSM/WCDMA)", 32, R.string.mode_nr_lte_tdscdma_gsm_wcdma),
    NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA("Global 5G + TD-SCDMA (All Networks)", 33, R.string.mode_nr_lte_tdscdma_cdma_evdo_gsm_wcdma);
    
    companion object {
        /**
         * Get NetworkMode by RIL constant value
         */
        fun fromValue(value: Int): NetworkMode? {
            return values().find { it.value == value }
        }
    }
}

/**
 * Configuration for the two modes that can be toggled between
 */
data class ToggleModeConfig(
    val modeA: NetworkMode,
    val modeB: NetworkMode,
    val nextModeIsB: Boolean = true
) {
    fun getNextMode(): NetworkMode {
        return if (nextModeIsB) modeB else modeA
    }
    
    fun getCurrentMode(): NetworkMode {
        return if (nextModeIsB) modeA else modeB
    }
    
    fun toggle(): ToggleModeConfig {
        return copy(nextModeIsB = !nextModeIsB)
    }
}

sealed class CompatibilityState {
    object Pending : CompatibilityState()
    object Compatible : CompatibilityState()
    data class Incompatible(val reason: String) : CompatibilityState()
    data class PermissionDenied(val method: ControlMethod) : CompatibilityState()
}

/** The mode name in the app language. */
fun NetworkMode.label(context: Context): String = context.getString(labelRes)
