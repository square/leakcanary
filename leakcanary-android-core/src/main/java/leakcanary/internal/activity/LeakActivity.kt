package leakcanary.internal.activity

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.squareup.leakcanary.core.R
import leakcanary.internal.activity.db.LeaksDbHelper
import leakcanary.internal.activity.screen.GroupListScreen
import leakcanary.internal.navigation.NavigatingActivity
import leakcanary.internal.navigation.Screen

internal class LeakActivity : NavigatingActivity() {

  private lateinit var dbHelper: LeaksDbHelper

  val db get() = dbHelper.writableDatabase!!

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.leak_canary_leak_activity)

    val dbHelperOrNull = lastNonConfigurationInstance
    dbHelper = dbHelperOrNull as LeaksDbHelper? ?: LeaksDbHelper(
        this
    )


    installNavigation(savedInstanceState, findViewById(R.id.main_container))
  }

  override fun onRetainNonConfigurationInstance(): Any {
    return dbHelper
  }

  override fun getLauncherScreen(): Screen {
    return GroupListScreen()
  }

  override fun onDestroy() {
    super.onDestroy()
    if (!isChangingConfigurations) {
      dbHelper.close()
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
