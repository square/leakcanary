package leakcanary.internal

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import leakcanary.AppWatcher
import leakcanary.LeakCanary

/**
 * Content providers are loaded before the application class is created. [LeakCanaryProcessAppWatcherInstaller] is
 * used to install [leakcanary.AppWatcher] on application start.
 *
 * When using the `leakcanary-android-process` artifact instead of `leakcanary-android`,
 * [LeakCanaryProcessAppWatcherInstaller] automatically sets up the LeakCanary code
 */
internal class LeakCanaryProcessAppWatcherInstaller : ContentProvider() {

  override fun onCreate(): Boolean {
    val application = context!!.applicationContext as Application
    AppWatcher.manualInstall(
      application,
      // Nothing to watch in the :leakcanary process.
      watchersToInstall = emptyList()
    )
    return true
  }

  override fun query(
    uri: Uri,
    strings: Array<String>?,
    s: String?,
    strings1: Array<String>?,
    s1: String?
  ): Cursor? {
    return null
  }

  override fun getType(uri: Uri): String? {
    return null
  }

  override fun insert(
    uri: Uri,
    contentValues: ContentValues?
  ): Uri? {
    return null
  }

  override fun delete(
    uri: Uri,
    s: String?,
    strings: Array<String>?
  ): Int {
    return 0
  }

  override fun update(
    uri: Uri,
    contentValues: ContentValues?,
    s: String?,
    strings: Array<String>?
  ): Int {
    return 0
  }
}
