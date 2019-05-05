package leakcanary.internal.activity

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import com.squareup.leakcanary.core.R
import leakcanary.CanaryLog
import leakcanary.internal.HeapAnalyzerService
import leakcanary.internal.InternalLeakCanary
import leakcanary.internal.activity.db.Db
import leakcanary.internal.activity.screen.GroupListScreen
import leakcanary.internal.navigation.NavigatingActivity
import leakcanary.internal.navigation.Screen
import java.io.FileInputStream
import java.io.IOException

internal class LeakActivity : NavigatingActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.leak_canary_leak_activity)

    installNavigation(savedInstanceState, findViewById(R.id.main_container))
  }

  override fun getLauncherScreen(): Screen {
    return GroupListScreen()
  }

  fun requestImportHprof() {
    val requestFileIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
      type = "*/*"
      addCategory(Intent.CATEGORY_OPENABLE)
    }

    val chooserIntent = Intent.createChooser(
        requestFileIntent, resources.getString(R.string.leak_canary_import_from_title)
    )
    startActivityForResult(chooserIntent, FILE_REQUEST_CODE)
  }

  override fun onActivityResult(
    requestCode: Int,
    resultCode: Int,
    returnIntent: Intent?
  ) {
    CanaryLog.d(
        "Got activity result with requestCode=$requestCode resultCode=$resultCode returnIntent=$returnIntent"
    )
    if (requestCode == FILE_REQUEST_CODE && resultCode == RESULT_OK && returnIntent != null) {
      returnIntent.data?.let { fileUri ->
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
          importHprof(fileUri)
        }
      }
    }
  }

  private fun importHprof(fileUri: Uri) {
    try {
      contentResolver.openFileDescriptor(fileUri, "r")
          ?.fileDescriptor?.let { fileDescriptor ->
        val inputStream = FileInputStream(fileDescriptor)
        InternalLeakCanary.leakDirectoryProvider
            .newHeapDumpFile()
            ?.let { target ->
              inputStream.use { input ->
                target.outputStream()
                    .use { output ->
                      input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    }
              }
              HeapAnalyzerService.runAnalysis(this, target)
            }
      }
    } catch (e: IOException) {
      CanaryLog.d(e, "Could not imported Hprof file")
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    if (!isChangingConfigurations) {
      Db.closeDatabase()
    }
  }

  override fun setTheme(resid: Int) {
    // We don't want this to be called with an incompatible theme.
    // This could happen if you implement runtime switching of themes
    // using ActivityLifecycleCallbacks.
    if (resid != R.style.leak_canary_LeakCanary_Base) {
      return
    }
    super.setTheme(resid)
  }

  companion object {
    private const val FILE_REQUEST_CODE = 0

    fun createPendingIntent(
      context: Context,
      screens: ArrayList<Screen>
    ): PendingIntent {
      val intent = Intent(context, LeakActivity::class.java)
      intent.putExtra("screens", screens)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      return PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun createIntent(context: Context): Intent {
      val intent = Intent(context, LeakActivity::class.java)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      return intent
    }
  }

}
