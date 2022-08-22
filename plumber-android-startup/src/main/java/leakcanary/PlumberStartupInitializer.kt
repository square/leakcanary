package leakcanary

import android.app.Application
import android.content.Context
import androidx.startup.Initializer

class PlumberStartupInitializer : Initializer<PlumberStartupInitializer> {
  override fun create(context: Context) = apply {
    val application = context.applicationContext as Application
    AndroidLeakFixes.applyFixes(application)
  }
  override fun dependencies() = emptyList<Class<out Initializer<*>>>()
}
