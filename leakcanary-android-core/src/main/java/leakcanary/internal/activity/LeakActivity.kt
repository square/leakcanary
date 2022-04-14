package leakcanary.internal.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.squareup.leakcanary.core.R
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID
import leakcanary.EventListener.Event.HeapDump
import leakcanary.internal.InternalLeakCanary
import leakcanary.internal.activity.db.Db
import leakcanary.internal.activity.screen.AboutScreen
import leakcanary.internal.activity.screen.HeapAnalysisFailureScreen
import leakcanary.internal.activity.screen.HeapDumpScreen
import leakcanary.internal.activity.screen.HeapDumpsScreen
import leakcanary.internal.activity.screen.LeaksScreen
import leakcanary.internal.navigation.NavigatingActivity
import leakcanary.internal.navigation.Screen
import shark.SharkLog

internal class LeakActivity : NavigatingActivity() {

  private val leaksButton by lazy {
    findViewById<View>(R.id.leak_canary_navigation_button_leaks)
  }

  private val leaksButtonIconView by lazy {
    findViewById<View>(R.id.leak_canary_navigation_button_leaks_icon)
  }

  private val heapDumpsButton by lazy {
    findViewById<View>(R.id.leak_canary_navigation_button_heap_dumps)
  }

  private val heapDumpsButtonIconView by lazy {
    findViewById<View>(R.id.leak_canary_navigation_button_heap_dumps_icon)
  }

  private val aboutButton by lazy {
    findViewById<View>(R.id.leak_canary_navigation_button_about)
  }

  private val aboutButtonIconView by lazy {
    findViewById<View>(R.id.leak_canary_navigation_button_about_icon)
  }

  private val bottomNavigationBar by lazy {
    findViewById<View>(R.id.leak_canary_bottom_navigation_bar)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.leak_canary_leak_activity)

    installNavigation(savedInstanceState, findViewById(R.id.leak_canary_main_container))

    leaksButton.setOnClickListener { resetTo(LeaksScreen()) }
    heapDumpsButton.setOnClickListener { resetTo(HeapDumpsScreen()) }
    aboutButton.setOnClickListener { resetTo(AboutScreen()) }

    handleViewHprof(intent)
  }

  private fun handleViewHprof(intent: Intent?) {
    if (intent?.action != Intent.ACTION_VIEW) return
    val uri = intent.data ?: return
    if (uri.lastPathSegment?.endsWith(".hprof") != true) {
      Toast.makeText(this, getString(R.string.leak_canary_import_unsupported_file_extension, uri.lastPathSegment), Toast.LENGTH_LONG).show()
      return
    }
    resetTo(HeapDumpsScreen())
    AsyncTask.THREAD_POOL_EXECUTOR.execute {
      importHprof(uri)
    }
  }

  override fun onNewScreen(screen: Screen) {
    when (screen) {
      is LeaksScreen -> {
        bottomNavigationBar.visibility = View.VISIBLE
        leaksButton.isSelected = true
        leaksButtonIconView.alpha = 1.0f
        heapDumpsButton.isSelected = false
        heapDumpsButtonIconView.alpha = 0.4f
        aboutButton.isSelected = false
        aboutButtonIconView.alpha = 0.4f
      }
      is HeapDumpsScreen -> {
        bottomNavigationBar.visibility = View.VISIBLE
        leaksButton.isSelected = false
        leaksButtonIconView.alpha = 0.4f
        heapDumpsButton.isSelected = true
        heapDumpsButtonIconView.alpha = 1.0f
        aboutButton.isSelected = false
        aboutButtonIconView.alpha = 0.4f
      }
      is AboutScreen -> {
        bottomNavigationBar.visibility = View.VISIBLE
        leaksButton.isSelected = false
        leaksButtonIconView.alpha = 0.4f
        heapDumpsButton.isSelected = false
        heapDumpsButtonIconView.alpha = 0.4f
        aboutButton.isSelected = true
        aboutButtonIconView.alpha = 1.0f
      }
      else -> {
        bottomNavigationBar.visibility = View.GONE
      }
    }
  }

  override fun getLauncherScreen(): Screen {
    return LeaksScreen()
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
    SharkLog.d {
      "Got activity result with requestCode=$requestCode resultCode=$resultCode returnIntent=$returnIntent"
    }
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
          InternalLeakCanary.createLeakDirectoryProvider(this)
            .newHeapDumpFile()
            ?.let { target ->
              inputStream.use { input ->
                target.outputStream()
                  .use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                  }
              }
              InternalLeakCanary.sendEvent(
                HeapDump(
                  uniqueId = UUID.randomUUID().toString(),
                  file = target,
                  durationMillis = -1,
                  reason = "Imported by user"
                )
              )
            }
        }
    } catch (e: IOException) {
      SharkLog.d(e) { "Could not import Hprof file" }
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

  override fun parseIntentScreens(intent: Intent): List<Screen> {
    val heapAnalysisId = intent.getLongExtra("heapAnalysisId", -1L)
    if (heapAnalysisId == -1L) {
      return emptyList()
    }
    val success = intent.getBooleanExtra("success", false)
    return if (success) {
      arrayListOf(HeapDumpsScreen(), HeapDumpScreen(heapAnalysisId))
    } else {
      arrayListOf(HeapDumpsScreen(), HeapAnalysisFailureScreen(heapAnalysisId))
    }
  }

  companion object {
    private const val FILE_REQUEST_CODE = 0

    fun createHomeIntent(context: Context): Intent {
      val intent = Intent(context, LeakActivity::class.java)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      return intent
    }

    fun createSuccessIntent(context: Context, heapAnalysisId: Long): Intent {
      val intent = createHomeIntent(context)
      intent.putExtra("heapAnalysisId", heapAnalysisId)
      intent.putExtra("success", true)
      return intent
    }

    fun createFailureIntent(context: Context, heapAnalysisId: Long): Intent {
      val intent = createHomeIntent(context)
      intent.putExtra("heapAnalysisId", heapAnalysisId)
      intent.putExtra("success", false)
      return intent
    }
  }
}
