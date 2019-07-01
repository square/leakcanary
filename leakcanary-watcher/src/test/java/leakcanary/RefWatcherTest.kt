package leakcanary

import leakcanary.GcTrigger.Default.runGc
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
    assertThat(refWatcher.hasRetainedInstances).isFalse()
  }

  @Test fun `reachable object retained`() {
    refWatcher.watch(ref!!)
    runGc()
    assertThat(refWatcher.hasRetainedInstances).isTrue()
  }

}