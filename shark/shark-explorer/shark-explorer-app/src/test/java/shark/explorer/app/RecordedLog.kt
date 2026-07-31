package shark.explorer.app

import java.util.concurrent.CopyOnWriteArrayList
import org.junit.rules.ExternalResource
import shark.SharkLog

/**
 * Everything Shark logged during one test, a line per log with the throwable it came with appended, read
 * as the list of lines it is.
 *
 * Recorded for every test of a class that takes this rule rather than only for the ones asserting on it: a
 * log line is built from state — an index into a path that has been shortened, a node id — so a line built
 * from the wrong state should fail the test that reaches it rather than wait for a session nobody can read.
 *
 * A rule rather than a `@Before` in each test class, because putting the logger back is the part that isn't
 * optional: a test that leaves [SharkLog.logger] set breaks every test after it, whichever class those are
 * in.
 *
 * The window's thread and the heap dump's both log, hence the concurrent list behind it.
 */
// Public rather than internal because JUnit reaches a `@Rule` through a public getter, which a property of
// an internal type can't have.
class RecordedLog private constructor(
  private val lines: CopyOnWriteArrayList<String>
) : ExternalResource(), List<String> by lines {

  constructor() : this(CopyOnWriteArrayList())

  private var previousLogger: SharkLog.Logger? = null

  override fun before() {
    previousLogger = SharkLog.logger
    SharkLog.logger = object : SharkLog.Logger {
      override fun d(message: String) {
        lines += message
      }

      override fun d(
        throwable: Throwable,
        message: String
      ) {
        lines += "$message: $throwable"
      }
    }
  }

  override fun after() {
    SharkLog.logger = previousLogger
  }
}
