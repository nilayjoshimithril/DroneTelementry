package com.guardianangel.dronetelementry

import android.app.Application
import com.guardianangel.dronetelementry.di.appModule
import com.guardianangel.dronetelementry.utils.DeviceStatusWatcher
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class DroneTelemetryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@DroneTelemetryApp)
            modules(appModule)
        }
        // Screen always on + low tablet battery dialog, for every screen.
        DeviceStatusWatcher.install(this)
    }
}
