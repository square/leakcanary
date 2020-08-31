package leakcanary.internal.activity.screen

import android.content.res.Resources
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import com.squareup.leakcanary.core.BuildConfig
import com.squareup.leakcanary.core.R
import leakcanary.AppWatcher
import leakcanary.LeakCanary
import leakcanary.internal.DebuggerControl
import leakcanary.internal.activity.screen.AboutScreen.HeapDumpPolicy.HeapDumpStatus.DISABLED_BY_DEVELOPER
import leakcanary.internal.activity.screen.AboutScreen.HeapDumpPolicy.HeapDumpStatus.DISABLED_DEBUGGER_ATTACHED
import leakcanary.internal.activity.screen.AboutScreen.HeapDumpPolicy.HeapDumpStatus.DISABLED_FROM_ABOUT_SCREEN
import leakcanary.internal.activity.screen.AboutScreen.HeapDumpPolicy.HeapDumpStatus.DISABLED_RUNNING_TESTS
import leakcanary.internal.activity.screen.AboutScreen.HeapDumpPolicy.HeapDumpStatus.ENABLED
import leakcanary.internal.activity.screen.AboutScreen.HeapDumpPolicy.HeapDumpStatus.NOT_INSTALLED
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.inflate

var heapDumpSwitchChecked = true
  private set

internal class AboutScreen : Screen() {

  override fun createView(container: ViewGroup) =
    container.inflate(R.layout.leak_canary_about_screen)
        .apply {
          activity.title =
            resources.getString(R.string.leak_canary_about_title, BuildConfig.LIBRARY_VERSION)
          val aboutTextView = findViewById<TextView>(R.id.leak_canary_about_text)
          aboutTextView.movementMethod = LinkMovementMethod.getInstance()
          val application = activity.application
          val appName = application.packageManager.getApplicationLabel(application.applicationInfo)
          val appPackageName = context.packageName

          aboutTextView.text = Html.fromHtml(
              String.format(
                  resources.getString(R.string.leak_canary_about_message), appName, appPackageName
              )
          )

          val heapDumpText = findViewById<TextView>(R.id.leak_canary_about_heap_dump_text)
          heapDumpText.text = getHeapDumpStatusMessage(resources)
          val heapDumpSwitchView =
            findViewById<Switch>(R.id.leak_canary_about_heap_dump_switch_button)
          heapDumpSwitchView.isChecked = heapDumpSwitchChecked
          heapDumpSwitchView.setOnCheckedChangeListener { _, isChecked ->
            heapDumpSwitchChecked = isChecked
            updateConfig()
            heapDumpText.text = getHeapDumpStatusMessage(resources)
          }
        }

  private fun getHeapDumpStatusMessage(resources: Resources) =
    when (getHeapDumpStatus(resources)) {
      NOT_INSTALLED -> resources.getString(R.string.leak_canary_heap_dump_not_installed_text)
      ENABLED -> resources.getString(R.string.leak_canary_heap_dump_enabled_text)
      DISABLED_DEBUGGER_ATTACHED -> String.format(
          resources.getString(R.string.leak_canary_heap_dump_disabled_text),
          resources.getString(R.string.leak_canary_heap_dump_disabled_build_non_debuggable)
      )
      DISABLED_BY_DEVELOPER -> String.format(
          resources.getString(R.string.leak_canary_heap_dump_disabled_text),
          resources.getString(R.string.leak_canary_heap_dump_disabled_by_app)
      )
      DISABLED_FROM_ABOUT_SCREEN -> String.format(
          resources.getString(R.string.leak_canary_heap_dump_disabled_text),
          resources.getString(R.string.leak_canary_heap_dump_disabled_from_ui)
      )
      DISABLED_RUNNING_TESTS -> String.format(
          resources.getString(R.string.leak_canary_heap_dump_disabled_text),
          resources.getString(R.string.leak_canary_heap_dump_disabled_running_tests)
      )
    }

  /**
   * Updates leak canary config to enable/disable heap dump depending upon the Toggle switch from the about screen.
   */
  private fun updateConfig() {
    LeakCanary.config = LeakCanary.config.copy(dumpHeap = heapDumpSwitchChecked)
  }

  companion object HeapDumpPolicy {
    enum class HeapDumpStatus {
      ENABLED,
      DISABLED_DEBUGGER_ATTACHED,
      DISABLED_BY_DEVELOPER,
      DISABLED_FROM_ABOUT_SCREEN,
      DISABLED_RUNNING_TESTS,
      NOT_INSTALLED
    }

    fun getHeapDumpStatus(resources: Resources): HeapDumpStatus {
      val config = LeakCanary.config
      return when {
        !AppWatcher.isInstalled -> NOT_INSTALLED
        !config.dumpHeap ->
          if (isRunningTests(resources)) {
            DISABLED_RUNNING_TESTS
          } else if (!heapDumpSwitchChecked) {
            DISABLED_FROM_ABOUT_SCREEN
          } else {
            DISABLED_BY_DEVELOPER
          }
        !config.dumpHeapWhenDebugging && DebuggerControl.isDebuggerAttached -> DISABLED_DEBUGGER_ATTACHED
        else -> ENABLED
      }
    }

    private fun isRunningTests(resources: Resources) =
      try {
        Class.forName(resources.getString(R.string.leak_canary_test_class_name))
        true
      } catch (e: Exception) {
        false
      }
  }
}