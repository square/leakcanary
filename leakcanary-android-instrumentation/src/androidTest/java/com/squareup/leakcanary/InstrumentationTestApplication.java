package com.squareup.leakcanary;

import android.app.Application;

public class InstrumentationTestApplication extends Application {
  @Override public void onCreate() {
    super.onCreate();
    if (LeakCanary.isInAnalyzerProcess(this)) {
      return;
    }
    InstrumentationLeakDetector.instrumentationRefWatcher(this)
        .buildAndInstall();
  }
}
