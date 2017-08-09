package com.example.leakcanary;

public class TestExampleApplication extends ExampleApplication {
  @Override protected void setupLeakCanary() {
    // No leakcanary in unit tests.
  }
}
