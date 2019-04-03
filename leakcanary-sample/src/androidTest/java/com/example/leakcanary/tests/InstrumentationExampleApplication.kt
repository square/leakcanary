package com.example.leakcanary.tests

import com.example.leakcanary.ExampleApplication
import com.squareup.leakcanary.InstrumentationLeakDetector

class InstrumentationExampleApplication : ExampleApplication() {

  override fun onCreate() {
    super.onCreate()
    InstrumentationLeakDetector.updateConfig()
  }
}
