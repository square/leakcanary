package shark

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import shark.SharkCliCommand.Companion.echo

/**
 * Thin client that sends a single command to a running [AiInvestigateCommand] daemon and prints
 * the JSON response. The daemon and this command communicate via two named pipes identified by
 * the session shortcode printed by shark-ai-investigate.
 *
 * Usage (start the session first with shark-ai-investigate, then send commands):
 *   shark-ai-investigate --hprof <file>          # prints SHORT_CODE=<shortcode>
 *   shark-ai-investigate cmd <shortcode> trace
 *   shark-ai-investigate cmd <shortcode> fields 3
 *   shark-ai-investigate cmd <shortcode> mark-leaking 3 mDestroyed is true
 *   shark-ai-investigate cmd <shortcode> close-session
 */
class AiInvestigateCmdCommand : CliktCommand(
  name = "ai-investigate-cmd",
  help = "Send a command to a running ai-investigate daemon. First argument is the session shortcode."
) {

  init {
    context {
      // Disable @-file expansion so `fields @<objectId>` isn't treated as "load args from file".
      expandArgumentFiles = false
      // Stop option-parsing after the first positional arg (session shortcode).
      // Clikt 2.3.0 treats ANY token containing '=' as a long-option attempt, so a reason like
      // "size=1" would otherwise trigger "no such option: ..." before even reaching the daemon.
      allowInterspersedArgs = false
    }
  }

  private val session by argument(help = "Session shortcode printed by shark-ai-investigate (SHORT_CODE=...)")

  private val commandParts by argument(help = "Command and arguments to send to the daemon")
    .multiple(required = true)

  override fun run() {
    val inPath = "/tmp/shark-$session.in"
    val outPath = "/tmp/shark-$session.out"

    if (!File(inPath).exists() || !File(outPath).exists()) {
      echo("""{"error":"No active session '$session'. Run: shark-ai-investigate --hprof <file>"}""")
      return
    }

    val cmd = commandParts.joinToString(" ")

    // Opening a FIFO blocks until both ends are open. Start both opens concurrently so
    // the client is never waiting sequentially while the daemon waits on the other FIFO.
    // supplyAsync uses the common fork-join pool (daemon threads), matching the daemon side.
    val writerFuture = CompletableFuture.supplyAsync { FileOutputStream(inPath).bufferedWriter() }
    val readerFuture = CompletableFuture.supplyAsync { File(outPath).bufferedReader() }

    try {
      CompletableFuture.allOf(writerFuture, readerFuture).get(10_000L, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
      echo("""{"error":"Session '$session' is not responding. Daemon may have exited."}""")
      return
    }

    val writer = writerFuture.get()
    val reader = readerFuture.get()

    writer.write(cmd + "\n")
    writer.flush()
    writer.close()

    val response = reader.readLine()
    reader.close()

    if (response != null) {
      if (commandParts.firstOrNull() == "human-readable-trace") {
        // Daemon sends a single JSON line {"trace":"..."}; extract and print plain text.
        val trace = runCatching {
          Json.parseToJsonElement(response).jsonObject["trace"]?.jsonPrimitive?.content
        }.getOrNull()
        echo(trace ?: response)
      } else {
        echo(response)
      }
    }
  }
}
