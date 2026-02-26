package shark

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.ValueHolder.IntHolder
import java.io.File
import java.util.concurrent.TimeUnit

private const val SESSION = "testXXXX"

/**
 * Integration test for the ai-investigate FIFO daemon.
 *
 * Starts the daemon as a real subprocess (using the current JVM's classpath), then exercises it
 * through a series of ai-investigate-cmd subprocesses — one process per command — mirroring
 * exactly how an AI agent would use it in production.
 */
class AiInvestigateDaemonTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  private var daemon: Process? = null
  private val daemonOutput = mutableListOf<String>()

  @After
  fun cleanup() {
    daemon?.destroyForcibly()
  }

  @Test
  fun `daemon responds to commands via FIFO and exits cleanly on close-session`() {
    // Minimal Android-compatible heap dump.
    // AndroidReferenceMatchers.appDefaults requires android.os.Build and
    // android.os.Build$VERSION in the heap, so we add the fields they read.
    val hprofFile = testFolder.newFile("test.hprof")
    hprofFile.dump {
      val manufacturer = string("Test")
      val buildId = string("TEST")
      clazz(
        className = "android.os.Build",
        staticFields = listOf("MANUFACTURER" to manufacturer, "ID" to buildId)
      )
      clazz(
        className = "android.os.Build\$VERSION",
        staticFields = listOf("SDK_INT" to IntHolder(29))
      )
      "GcRoot" clazz {
        staticField["leak"] = "Leaking" watchedInstance {}
      }
    }

    // Start the daemon with a fixed session shortcode. Daemon prints nothing to stdout.
    daemon = jvmProcess("--hprof", hprofFile.absolutePath, "ai-investigate", "--session", SESSION)

    // Collect daemon stderr for diagnostics on failure.
    Thread {
      daemon!!.errorStream.bufferedReader().forEachLine { line ->
        synchronized(daemonOutput) { daemonOutput += "[stderr] $line" }
      }
    }.also { it.isDaemon = true }.start()

    // Daemon creates FIFOs before loading the heap (so this is fast).
    val inFifo = File("/tmp/shark-$SESSION.in")
    val deadline = System.currentTimeMillis() + 30_000
    while (!inFifo.exists() && System.currentTimeMillis() < deadline) Thread.sleep(100)
    check(inFifo.exists()) {
      "Daemon did not create FIFOs within 30 seconds.\n\nDaemon output:\n${daemonOutput.joinToString("\n")}"
    }

    // summary → exactly one leak group found in the heap dump
    val summary = cmd("summary")
    assertThat(summary["leakGroups"]!!.jsonArray).hasSize(1)

    // trace → nodes array present; the last node (the leaking object) is LEAKING
    val trace = cmd("trace")
    val nodes = trace["nodes"]!!.jsonArray
    assertThat(nodes).isNotEmpty
    assertThat(nodes.last().jsonObject["leakingStatus"]!!.jsonPrimitive.content)
      .isEqualTo("LEAKING")

    // node 0 → has a className (the GC root end of the path)
    val node0 = cmd("node", "0")
    assertThat(node0["className"]).isNotNull

    // fields @<objectId> → returns a FieldsResponse for the leaking instance
    // (node 0 is a class object, so we use the leaking instance via its objectId)
    val leakingObjectId = nodes.last().jsonObject["objectId"]!!.jsonPrimitive.content
    val fields = cmd("fields", "@$leakingObjectId")
    assertThat(fields["objectId"]).isNotNull
    assertThat(fields["fields"]).isNotNull

    // close-session → daemon acknowledges, then exits with code 0
    val close = cmd("close-session")
    assertThat(close["message"]!!.jsonPrimitive.content).isEqualTo("Session closed.")
    assertThat(daemon!!.waitFor(10, TimeUnit.SECONDS)).isTrue
    assertThat(daemon!!.exitValue()).isZero
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Spawns a JVM subprocess with [args] prepended after the main class. */
  private fun jvmProcess(vararg args: String): Process =
    ProcessBuilder(
      "${System.getProperty("java.home")}/bin/java",
      "-cp", System.getProperty("java.class.path"),
      "shark.MainKt",
      *args
    ).start()

  /**
   * Runs `ai-investigate-cmd SESSION [cmdArgs]` as a subprocess, waits for it to finish,
   * and returns the parsed JSON response. Prints any stderr output so failures are easy to diagnose.
   */
  private fun cmd(vararg cmdArgs: String): JsonObject {
    val process = jvmProcess("ai-investigate-cmd", SESSION, *cmdArgs)
    val cmd = "ai-investigate-cmd $SESSION ${cmdArgs.joinToString(" ")}"
    check(process.waitFor(30, TimeUnit.SECONDS)) {
      "$cmd timed out\n\nDaemon output:\n${daemonOutput.joinToString("\n")}"
    }
    val out = process.inputStream.bufferedReader().readText().trim()
    val err = process.errorStream.bufferedReader().readText().trim()
    if (err.isNotEmpty()) {
      System.err.println("[cmd ${cmdArgs.joinToString(" ")} stderr] $err")
    }
    check(out.isNotEmpty()) {
      "Empty output from $cmd\n\nCmd stderr: $err\n\nDaemon output:\n${daemonOutput.joinToString("\n")}"
    }
    return Json.parseToJsonElement(out).jsonObject
  }
}
