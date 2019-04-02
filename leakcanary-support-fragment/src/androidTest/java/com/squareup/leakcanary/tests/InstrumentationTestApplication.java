package com.squareup.leakcanary.tests;

import android.app.Application;
import com.squareup.leakcanary.InstrumentationLeakDetector;

public class InstrumentationTestApplication extends Application {
  @Override public void onCreate() {
    super.onCreate();
    InstrumentationLeakDetector.Companion.instrumentationRefWatcher(this)
        .buildAndInstall();
  }
}
