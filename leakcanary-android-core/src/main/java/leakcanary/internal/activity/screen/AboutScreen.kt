package leakcanary.internal.activity.screen

import android.content.Context
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import com.squareup.leakcanary.core.BuildConfig
import com.squareup.leakcanary.core.R
import leakcanary.internal.HeapDumpControl
import leakcanary.internal.HeapDumpControl.ICanHazHeap.Nope
import leakcanary.internal.HeapDumpControl.ICanHazHeap.Yup
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.inflate

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

          val heapDumpTextView = findViewById<TextView>(R.id.leak_canary_about_heap_dump_text)
          updateHeapDumpTextView(heapDumpTextView)
          val heapDumpSwitchView =
            findViewById<Switch>(R.id.leak_canary_about_heap_dump_switch_button)
          heapDumpSwitchView.isChecked = context.dumpEnabledInAboutScreen
          heapDumpSwitchView.setOnCheckedChangeListener { _, checked ->
            // Updating the value wouldn't normally immediately trigger a heap dump, however
            // by updating the view we also have a side effect of querying which will notify
            // the heap dumper if the value has become positive.
            context.dumpEnabledInAboutScreen = checked
            updateHeapDumpTextView(heapDumpTextView)
          }
        }

  private fun updateHeapDumpTextView(view: TextView) {
    view.text = when (val iCanHasHeap = HeapDumpControl.iCanHasHeap()) {
      is Yup -> view.resources.getString(R.string.leak_canary_heap_dump_enabled_text)
      is Nope -> view.resources.getString(
          R.string.leak_canary_heap_dump_disabled_text, iCanHasHeap.reason()
      )

    }
  }
}

internal var Context.dumpEnabledInAboutScreen: Boolean
  get() {
    return getSharedPreferences("LeakCanaryHeapDumpPrefs", Context.MODE_PRIVATE)
        .getBoolean("AboutScreenDumpEnabled", true)
  }
  set(value) {
    getSharedPreferences("LeakCanaryHeapDumpPrefs", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("AboutScreenDumpEnabled", value)
        .apply()
  }
