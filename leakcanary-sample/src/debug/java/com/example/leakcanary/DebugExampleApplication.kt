package com.example.leakcanary

import leakcanary.LeakCanary

class DebugExampleApplication : ExampleApplication() {

  override fun onCreate() {
    if (LeakCanary.isInAnalyzerProcess(this)) {
      // This process is dedicated to LeakCanary for heap analysis.
      // You should not init your app in this process.
      return
    }
    LeakCanary.config = LeakCanary.config.copy(useExperimentalHeapParser = true)
    super.onCreate()
  }
}