package leakcanary

import leakcanary.GcTrigger.Default.runGc
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.concurrent.Executor

class ObjectWatcherTest {

  private val onInstanceRetained = object: OnObjectRetainedListener {
    override fun onObjectRetained() {}
  }

  private val checkRetainedExecutor: Executor = Executor {
    it.run()
  }

  private val objectWatcher = ObjectWatcher(object : Clock {
    override fun uptimeMillis(): Long {
      return time
    }
  }, checkRetainedExecutor, onInstanceRetained)
  var time: Long = 0

  var ref: Any? = Any()

  @Test fun `unreachable object not retained`() {
    objectWatcher.watch(ref!!)
    ref = null
    runGc()
    assertThat(objectWatcher.hasRetainedObjects).isFalse()
  }

  @Test fun `reachable object retained`() {
    objectWatcher.watch(ref!!)
    runGc()
    assertThat(objectWatcher.hasRetainedObjects).isTrue()
  }

}