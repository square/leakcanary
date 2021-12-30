package leakcanary.internal

import android.app.Application
import android.content.Context
import androidx.startup.Initializer
import leakcanary.AppWatcher

internal class AppWatcherStartupInitializer : Initializer<AppWatcherStartupInitializer> {
  override fun create(context: Context) = apply {
    val application = context.applicationContext as Application
    AppWatcher.manualInstall(application)
  }
  override fun dependencies() = emptyList<Class<out Initializer<*>>>()
}
