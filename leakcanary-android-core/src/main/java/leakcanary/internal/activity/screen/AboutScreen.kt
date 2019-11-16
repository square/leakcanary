package leakcanary.internal.activity.screen

import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.ViewGroup
import android.widget.TextView
import com.squareup.leakcanary.core.BuildConfig
import com.squareup.leakcanary.core.R
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.inflate

internal class AboutScreen : Screen() {
  override fun createView(container: ViewGroup) =
    container.inflate(R.layout.leak_canary_about_screen).apply {
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
    }

}