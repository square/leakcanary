package leaksentry

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.concurrent.Executor

class RefWatcherTest {

  private val onRefRetained: () -> Unit = {
  }

  private val checkRetainedExecutor: Executor = Executor {
    it.run()
  }

  val refWatcher = RefWatcher(object : Clock {
    override fun uptimeMillis(): Long {
      return time
    }
  }, checkRetainedExecutor, onRefRetained)
  var time: Long = 0

  var ref: Any? = Any()

  @Test fun `unreachable object not retained`() {
    refWatcher.watch(ref!!)
    ref = null
    runGc()
    assertThat(refWatcher.hasRetainedReferences).isFalse()
  }

  @Test fun `reachable object retained`() {
    refWatcher.watch(ref!!)
    runGc()
    assertThat(refWatcher.hasRetainedReferences).isTrue()
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