package leakcanary.internal.activity.screen

import android.content.Context
import android.content.Context.MODE_PRIVATE
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

private const val HEAP_DUMP_SHARED_PREFERENCES = "HEAP_DUMP_SHARED_PREFERENCES"
private const val HEAP_DUMP_SWITCH_ENABLED = "HEAP_DUMP_SWITCH_ENABLED"

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
          heapDumpText.text = getHeapDumpStatusMessage(resources, context)
          val heapDumpSwitchView =
            findViewById<Switch>(R.id.leak_canary_about_heap_dump_switch_button)
          heapDumpSwitchView.isChecked = getHeapDumpSwitchStatus(context)
          heapDumpSwitchView.setOnCheckedChangeListener { _, checked ->
            updateHeapDumpConfig(checked, context)
            heapDumpText.text = getHeapDumpStatusMessage(resources, context)
          }
        }

  private fun getHeapDumpStatusMessage(
    resources: Resources,
    context: Context
  ) =
    when (getHeapDumpStatus(resources, context)) {
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
  private fun updateHeapDumpConfig(
    checked: Boolean,
    context: Context
  ) {
    LeakCanary.config = LeakCanary.config.copy(dumpHeap = checked)
    context
        .getSharedPreferences(HEAP_DUMP_SHARED_PREFERENCES, MODE_PRIVATE)
        .edit()
        .putBoolean(HEAP_DUMP_SWITCH_ENABLED, checked)
        .apply()
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

    fun getHeapDumpSwitchStatus(context: Context): Boolean {
      return context.getSharedPreferences(HEAP_DUMP_SHARED_PREFERENCES, MODE_PRIVATE)
          .getBoolean(HEAP_DUMP_SWITCH_ENABLED, true)
    }

    fun getHeapDumpStatus(
      resources: Resources,
      context: Context
    ): HeapDumpStatus {
      val config = LeakCanary.config
      return when {
        !AppWatcher.isInstalled -> NOT_INSTALLED
        !config.dumpHeap ->
          if (isRunningTests(resources)) {
            DISABLED_RUNNING_TESTS
          } else if (!getHeapDumpSwitchStatus(context)) {
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