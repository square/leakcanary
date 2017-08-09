package com.example.leakcanary;

public class TestExampleApplication extends DebugExampleApplication {
  @Override protected void setupLeakCanary() {
    // No leakcanary in unit tests.
  }
}
