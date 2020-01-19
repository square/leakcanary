package leakcanary

import android.app.Application
import leakcanary.internal.InternalAppWatcher

object AppWatcherManualInstaller {
    @JvmStatic
    fun manualInstall(application: Application) = InternalAppWatcher.install(application)
}