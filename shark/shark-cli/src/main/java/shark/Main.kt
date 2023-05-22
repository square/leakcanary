package shark

import com.github.ajalt.clikt.core.subcommands

fun main(args: Array<String>) =
  SharkCliCommand().subcommands(
    InteractiveCommand(),
    AnalyzeCommand(),
    Neo4JCommand(),
    DumpProcessCommand(),
    StripHprofCommand(),
    DeobfuscateHprofCommand()
  ).main(args)
