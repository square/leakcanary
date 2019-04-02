package com.squareup.leakcanary;

import android.app.Application;

public class InstrumentationTestApplication extends Application {
  @Override public void onCreate() {
    super.onCreate();
    InstrumentationLeakDetector.Companion.instrumentationRefWatcher(this)
        .buildAndInstall();
  }
}
