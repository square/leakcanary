package com.example.leakcanary.tests;

import com.example.leakcanary.ExampleApplication;
import com.squareup.leakcanary.InstrumentationLeakDetector;

public class InstrumentationExampleApplication extends ExampleApplication {

  @Override protected void setupLeakCanary() {
    InstrumentationLeakDetector.instrumentationRefWatcher(this)
        .buildAndInstall();
  }
}
