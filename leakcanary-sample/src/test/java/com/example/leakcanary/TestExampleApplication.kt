package com.example.leakcanary

class TestExampleApplication : ExampleApplication() {
  override fun setupLeakCanary() {
    // No leakcanary in unit tests.
  }
}
