package leakcanary

import leakcanary.GcTrigger.Default.runGc
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.util.concurrent.Executor

class ObjectWatcherTest {

  private val checkRetainedExecutor: Executor = Executor {
    it.run()
  }

  private val objectWatcher = ObjectWatcher(object : Clock {
    override fun uptimeMillis(): Long {
      return time
    }
  }, checkRetainedExecutor).apply { addOnObjectRetainedListener(OnObjectRetainedListener {}) }
  var time: Long = 0

  var ref: Any? = Any()

  @Test fun `unreachable object not retained`() {
    objectWatcher.watch(ref!!, "unreachable object not retained")
    ref = null
    runGc()
    assertThat(objectWatcher.hasRetainedObjects).isFalse()
  }

  @Test fun `reachable object retained`() {
    objectWatcher.watch(ref!!, "reachable object retained")
    runGc()
    assertThat(objectWatcher.hasRetainedObjects).isTrue()
  }

}