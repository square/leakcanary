package shark.dive

import java.io.File
import java.util.Date
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionLogTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  private val logDirectory: File get() = File(testFolder.root, "logs")

  @Test fun `a message is written with the time and the thread it came from`() {
    val log = SessionLog.openIn(logDirectory)

    log.d("Opening a heap dump")
    log.close()

    assertThat(log.file.readText())
      .containsPattern("\\d\\d:\\d\\d:\\d\\d\\.\\d\\d\\d ")
      .contains("[${Thread.currentThread().name}]")
      .contains("Opening a heap dump")
  }

  @Test fun `a throwable is written with its stack trace`() {
    val log = SessionLog.openIn(logDirectory)

    log.d(IllegalStateException("Not a heap dump"), "Could not open dump.hprof")
    log.close()

    assertThat(log.file.readText())
      .contains("Could not open dump.hprof")
      .contains("java.lang.IllegalStateException: Not a heap dump")
      .contains("at shark.dive.SessionLogTest")
  }

  @Test fun `every line is written before the log is closed`() {
    val log = SessionLog.openIn(logDirectory)

    log.d("A session that crashes right here")

    assertThat(log.file.readText()).contains("A session that crashes right here")
  }

  @Test fun `each run writes to a file of its own`() {
    val firstRun = SessionLog.openIn(logDirectory, startedAt = Date(0))
    val secondRun = SessionLog.openIn(logDirectory, startedAt = Date(1000))

    firstRun.d("The first run")
    secondRun.d("The second run")
    firstRun.close()
    secondRun.close()

    assertThat(firstRun.file).isNotEqualTo(secondRun.file)
    assertThat(firstRun.file.readText()).contains("The first run").doesNotContain("The second run")
    assertThat(secondRun.file.readText()).contains("The second run").doesNotContain("The first run")
  }

  @Test fun `the log files of older runs are deleted`() {
    val runs = (1..5).map { run ->
      SessionLog.openIn(logDirectory, keepSessionCount = 3, startedAt = Date(run * 1000L))
        .apply { close() }
    }

    assertThat(logDirectory.listFiles()!!.map { it.name })
      .containsExactlyInAnyOrderElementsOf(runs.takeLast(3).map { it.file.name })
  }

  @Test fun `deleting the log file of an older run is logged`() {
    SessionLog.openIn(logDirectory, keepSessionCount = 1, startedAt = Date(0)).apply { close() }

    val secondRun = SessionLog.openIn(logDirectory, keepSessionCount = 1, startedAt = Date(1000))
    secondRun.close()

    assertThat(secondRun.file.readText()).contains("Deleted the log of an older run")
  }

  @Test fun `a file that is not a log of this directory is left alone`() {
    val heapDump = File(logDirectory.apply { mkdirs() }, "dump.hprof").apply { writeText("not a log") }

    SessionLog.openIn(logDirectory, keepSessionCount = 1).close()

    assertThat(heapDump).exists()
  }

  @Test fun `the log directory is created`() {
    val log = SessionLog.openIn(File(logDirectory, "nested"))

    log.close()

    assertThat(log.file).exists()
  }

  @Test fun `logging after the log is closed neither throws nor writes`() {
    val log = SessionLog.openIn(logDirectory)
    log.d("Before closing")
    log.close()

    log.d("After closing")

    assertThat(log.file.readText()).contains("Before closing").doesNotContain("After closing")
  }

  @Test fun `keeping no log file at all is refused`() {
    assertThat(
      runCatching { SessionLog.openIn(logDirectory, keepSessionCount = 0) }.exceptionOrNull()
    ).isInstanceOf(IllegalArgumentException::class.java)
  }
}
