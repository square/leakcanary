package leakcanary.internal

import android.app.Application
import android.content.Context
import androidx.startup.AppInitializer
import androidx.startup.Initializer
import leakcanary.AppWatcher

/**
 * [AppWatcherInitializer] is used to install [leakcanary.AppWatcher] on application start.
 *
 * @see AppInitializer
 */
class AppWatcherInitializer : Initializer<AppWatcher> {

    override fun create(context: Context) = AppWatcher.apply {
        manualInstall(context.applicationContext as Application)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}