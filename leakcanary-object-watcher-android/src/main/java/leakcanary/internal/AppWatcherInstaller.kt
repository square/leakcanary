package leakcanary.internal

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import shark.SharkLog

/**
 * Content providers are loaded before the application class is created. [AppWatcherInstaller] is
 * used to install [leakcanary.AppWatcher] on application start.
 */
internal class AppWatcherInstaller : ContentProvider() {

  override fun onCreate(): Boolean {
    SharkLog.logger = DefaultCanaryLog()
    val application = context!!.applicationContext as Application
    InternalAppWatcher.install(application)
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