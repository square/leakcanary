package shark

import com.github.ajalt.clikt.core.subcommands

fun main(args: Array<String>) =
  SharkCli().subcommands(
      AnalyzeProcess(),
      DumpProcess(),
      AnalyzeHprof(),
      StripHprof()
  ).main(args)