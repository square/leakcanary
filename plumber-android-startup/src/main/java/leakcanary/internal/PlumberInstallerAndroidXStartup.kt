package  leakcanary.internal

import android.app.Application
import android.content.Context
import androidx.startup.Initializer
import leakcanary.AndroidLeakFixes
import java.util.*

object PlumberInstallerAndroidXStartupPPTX

class PlumberInstallerAndroidXStartup : Initializer<PlumberInstallerAndroidXStartupPPTX> {
    override fun dependencies(): List<Class<out Initializer<*>?>> {
        return Collections.emptyList()
    }

    override fun create(context: Context): PlumberInstallerAndroidXStartupPPTX {
        (context.applicationContext as? Application)?.let {
            AndroidLeakFixes.applyFixes(it)
        }

        return PlumberInstallerAndroidXStartupPPTX
    }
}
