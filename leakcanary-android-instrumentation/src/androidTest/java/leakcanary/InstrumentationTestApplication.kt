package leakcanary

import android.app.Application
import leakcanary.InstrumentationLeakDetector

class InstrumentationTestApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    InstrumentationLeakDetector.updateConfig()
  }
}
