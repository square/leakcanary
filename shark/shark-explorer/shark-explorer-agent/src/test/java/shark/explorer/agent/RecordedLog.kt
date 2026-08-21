package shark.explorer.agent

import java.util.concurrent.CopyOnWriteArrayList
import org.junit.rules.ExternalResource
import shark.SharkLog

/**
 * Everything Shark logged during one test, a line per log, read as the list of lines it is.
 *
 * What a session log is for here is being able to follow an investigation afterwards — the reason an agent
 * gave for a call, then the reads that call cost — so the lines and the order they are in are the thing
 * under test rather than a side effect of it.
 *
 * A rule rather than a `@Before`, because putting the logger back is the part that isn't optional: a test
 * that leaves [SharkLog.logger] set breaks every test after it, whichever class those are in. A concurrent
 * list because a connection is served on a thread of its own.
 *
 * Duplicated from the app's tests rather than shared, since a test helper is not worth a module's public
 * API.
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
