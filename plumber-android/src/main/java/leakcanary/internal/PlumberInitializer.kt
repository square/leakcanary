package leakcanary.internal

import android.app.Application
import android.content.Context
import androidx.startup.AppInitializer
import androidx.startup.Initializer
import leakcanary.AndroidLeakFixes

/**
 * [PlumberInitializer] is used to install [leakcanary.AndroidLeakFixes] fixes on application start.
 *
 * @see AppInitializer
 */
class PlumberInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        AndroidLeakFixes.applyFixes(context.applicationContext as Application)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}