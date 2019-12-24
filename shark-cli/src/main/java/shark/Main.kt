package shark

import com.github.ajalt.clikt.core.subcommands

fun main(args: Array<String>) =
  SharkCliCommand().subcommands(
      InteractiveCommand(),
      AnalyzeCommand(),
      DumpProcessCommand(),
      StripHprofCommand()
  ).main(args)