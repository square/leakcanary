package com.squareup.leakcanary

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class RefWatcherTest {

  val refWatcher = RefWatcher(Clock { time })
  var time: Long = 0

  var ref: Any? = Any()

  @Test fun `unreachable object not tracked`() {
    refWatcher.watch(ref)
    ref = null
    GcTrigger.DEFAULT.runGc()
    assertThat(refWatcher.isEmpty).isTrue()
  }

  @Test fun `reachable object not tracked`() {
    refWatcher.watch(ref)
    GcTrigger.DEFAULT.runGc()
    assertThat(refWatcher.isEmpty).isFalse()
  }

}