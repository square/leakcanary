package org.leakcanary

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.leakcanary.util.ActivityProviderCallbacks

@HiltAndroidApp
class LeakCanaryApp : Application() {

  override fun onCreate() {
    super.onCreate()
    registerActivityLifecycleCallbacks(ActivityProviderCallbacks())
  }
}
