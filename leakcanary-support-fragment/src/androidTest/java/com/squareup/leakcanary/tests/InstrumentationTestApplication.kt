package com.squareup.leakcanary.tests

import android.app.Application
import com.squareup.leakcanary.InstrumentationLeakDetector

class InstrumentationTestApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    InstrumentationLeakDetector.updateConfig()
  }
}
