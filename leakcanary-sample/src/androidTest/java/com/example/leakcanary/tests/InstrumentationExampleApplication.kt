package com.example.leakcanary.tests

import com.example.leakcanary.ExampleApplication
import com.squareup.leakcanary.InstrumentationLeakDetector

class InstrumentationExampleApplication : ExampleApplication() {

  override fun setupLeakCanary() {
    InstrumentationLeakDetector.instrumentationRefWatcher(this)
        .buildAndInstall()
  }
}
