package com.example.leakcanary

import leakcanary.EventListener
import leakcanary.EventListener.Event.HeapAnalysisDone
import leakcanary.LeakCanary
import org.leakcanary.internal.LeakUiAppClient

class DebugExampleApplication : ExampleApplication() {

  override fun onCreate() {
    super.onCreate()

    // TODO We need to decide whether to show the activity icon based on whether
    //  the app library is here (?). Though ideally the embedded activity is also a separate
    //  optional module.
    LeakCanary.config = LeakCanary.config.run {
      copy(eventListeners = eventListeners + EventListener {
        // TODO Move this into an EventListener class, maybe the standard one
        //  TODO Detect if app installed or not and delegate to std leakcanary if not.
        if (it is HeapAnalysisDone<*>) {
          LeakUiAppClient(this@DebugExampleApplication).sendHeapAnalysis(it.heapAnalysis)
        }
      })
    }
  }
}
