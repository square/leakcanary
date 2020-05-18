package shark

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.output.TermUi
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.cooccurring
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.file
import shark.DumpProcessCommand.Companion.dumpHeap
import shark.SharkCliCommand.HeapDumpSource.HprofFileSource
import shark.SharkCliCommand.HeapDumpSource.ProcessSource
import shark.SharkLog.Logger
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Properties
import java.util.concurrent.TimeUnit.SECONDS

class SharkCliCommand : CliktCommand(
    name = "shark-cli",
    // This ASCII art is a remix of a shark from -David "TAZ" Baltazar- and chick from jgs.
    help = """
    |Version: $versionName
    |
    |```
    |$S                ^`.                 .=""=.
    |$S^_              \  \               / _  _ \
    |$S\ \             {   \             |  d  b  |
    |$S{  \           /     `~~~--__     \   /\   /
    |$S{   \___----~~'              `~~-_/'-=\/=-'\,
    |$S \                         /// a  `~.      \ \
    |$S / /~~~~-, ,__.    ,      ///  __,,,,)      \ |
    |$S \/      \/    `~~~;   ,---~~-_`/ \        / \/
    |$S                  /   /            '.    .'
    |$S                 '._.'             _|`~~`|_
    |$S                                   /|\  /|\
    |```
    """.trimMargin()
) {

  private class ProcessOptions : OptionGroup() {
    val processName by option(
        "--process", "-p",
        help = "Full or partial name of a process, e.g. \"example\" would match \"com.example.app\""
    ).required()

    val device by option(
        "-d", "--device", metavar = "ID", help = "device/emulator id"
    )
  }

  private val processOptions by ProcessOptions().cooccurring()

  private val obfuscationMappingPath by option(
      "-m", "--obfuscation-mapping", help = "path to obfuscation mapping file"
  ).file()

  private val verbose by option(
      help = "provide additional details as to what shark-cli is doing"
  ).flag("--no-verbose")

  private val heapDumpFile by option("--hprof", "-h", help = "path to a .hprof file").file(
      exists = true,
      folderOkay = false,
      readable = true
  )

  init {
    versionOption(versionName)
  }

  class CommandParams(
    val source: HeapDumpSource,
    val obfuscationMappingPath: File?
  )

  sealed class HeapDumpSource {
    class HprofFileSource(val file: File) : HeapDumpSource()
    class ProcessSource(
      val processName: String,
      val deviceId: String?
    ) : HeapDumpSource()
  }

  override fun run() {
    if (verbose) {
      setupVerboseLogger()
    }
    if (processOptions != null && heapDumpFile != null) {
      throw UsageError("Option --process cannot be used with --hprof")
    } else if (processOptions != null) {
      context.sharkCliParams = CommandParams(
          source = ProcessSource(processOptions!!.processName, processOptions!!.device),
          obfuscationMappingPath = obfuscationMappingPath
      )
    } else if (heapDumpFile != null) {
      context.sharkCliParams = CommandParams(
          source = HprofFileSource(heapDumpFile!!),
          obfuscationMappingPath = obfuscationMappingPath
      )
    } else {
      throw UsageError("Must provide one of --process, --hprof")
    }
  }

  private fun setupVerboseLogger() {
    class CLILogger : Logger {

      override fun d(message: String) {
        echo(message)
      }

      override fun d(
        throwable: Throwable,
        message: String
      ) {
        d("$message\n${getStackTraceString(throwable)}")
      }

      private fun getStackTraceString(throwable: Throwable): String {
        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter, false)
        throwable.printStackTrace(printWriter)
        printWriter.flush()
        return stringWriter.toString()
      }
    }

    SharkLog.logger = CLILogger()
  }

  companion object {
    /** Zero width space */
    private const val S = '\u200b'

    var Context.sharkCliParams: CommandParams
      get() {
        var ctx: Context? = this
        while (ctx != null) {
          if (ctx.obj is CommandParams) return ctx.obj as CommandParams
          ctx = ctx.parent
        }
        throw IllegalStateException("CommandParams not found in Context.obj")
      }
      set(value) {
        obj = value
      }

    fun CliktCommand.retrieveHeapDumpFile(params: CommandParams): File {
      return when (val source = params.source) {
        is HprofFileSource -> source.file
        is ProcessSource -> dumpHeap(source.processName, source.deviceId)
      }
    }

    fun CliktCommand.echoNewline() {
      echo("")
    }

    /**
     * Copy of [CliktCommand.echo] to make it publicly visible and therefore accessible
     * from [CliktCommand] extension functions
     */
    fun CliktCommand.echo(
      message: Any?,
      trailingNewline: Boolean = true,
      err: Boolean = false,
      lineSeparator: String = context.console.lineSeparator
    ) {
      TermUi.echo(message, trailingNewline, err, context.console, lineSeparator)
    }

    fun runCommand(
      directory: File,
      vararg arguments: String
    ): String {
      val process = ProcessBuilder(*arguments)
          .directory(directory)
          .start()
          .also { it.waitFor(10, SECONDS) }

      // See https://github.com/square/leakcanary/issues/1711
      // On Windows, the process doesn't always exit; calling to readText() makes it finish, so
      // we're reading the output before checking for the exit value
      val output = process.inputStream.bufferedReader().readText()
      if (process.exitValue() != 0) {
        val command = arguments.joinToString(" ")
        val errorOutput = process.errorStream.bufferedReader()
            .readText()
        throw CliktError(
            "Failed command: '$command', error output:\n---\n$errorOutput---"
        )
      }
      return output
    }

    private val versionName = run {
      val properties = Properties()
      properties.load(
          SharkCliCommand::class.java.getResourceAsStream("/version.properties")
              ?: throw IllegalStateException("version.properties missing")
      )
      properties.getProperty("version_name") ?: throw IllegalStateException(
          "version_name property missing"
      )
    }

  }

}