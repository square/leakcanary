package com.squareup.leakcanary

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class RefWatcherTest {

  val refWatcher = RefWatcher(object : Clock {
    override fun uptimeMillis(): Long {
      return time
    }
  })
  var time: Long = 0

  var ref: Any? = Any()

  @Test fun `unreachable object not tracked`() {
    refWatcher.watch(ref!!)
    ref = null
    runGc()
    assertThat(refWatcher.isEmpty).isTrue()
  }

  @Test fun `reachable object not tracked`() {
    refWatcher.watch(ref!!)
    runGc()
    assertThat(refWatcher.isEmpty).isFalse()
  }

  private fun runGc() {
    Runtime.getRuntime()
        .gc()
    enqueueReferences()
    System.runFinalization()
  }

  private fun enqueueReferences() {
    try {
      Thread.sleep(100)
    } catch (e: InterruptedException) {
      throw AssertionError()
    }

  }

}