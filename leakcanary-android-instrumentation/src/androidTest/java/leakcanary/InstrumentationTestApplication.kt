package leakcanary

import android.app.Application

class InstrumentationTestApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    InstrumentationLeakDetector.updateConfig()
  }
}
