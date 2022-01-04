package leakcanary.internal

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import leakcanary.AndroidLeakFixes

/**
 * Content providers are loaded before the application class is created. [PlumberInstaller] is
 * used to install [leakcanary.AndroidLeakFixes] fixes on application start.
 */
internal class PlumberInstaller : ContentProvider() {

  override fun onCreate(): Boolean {
    val application = context!!.applicationContext as Application
    AndroidLeakFixes.applyFixes(application)
    return true
  }

  override fun query(
    uri: Uri,
    projectionArg: Array<String>?,
    selection: String?,
    selectionArgs: Array<String>?,
    sortOrder: String?
  ): Cursor? = null

  override fun getType(uri: Uri): String? = null

  override fun insert(uri: Uri, contentValues: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

  override fun update(
    uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
  ): Int = 0
}
