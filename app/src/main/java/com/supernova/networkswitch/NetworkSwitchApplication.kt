package com.supernova.networkswitch

import com.supernova.networkswitch.util.AppLog
import android.os.Build
import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class NetworkSwitchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i("App started: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
    }
}
