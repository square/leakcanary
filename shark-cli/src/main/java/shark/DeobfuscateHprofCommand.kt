package shark

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import shark.SharkCliCommand.Companion.retrieveHeapDumpFile
import shark.SharkCliCommand.Companion.sharkCliParams

class DeobfuscateHprofCommand : CliktCommand(
    name = "deobfuscate-hprof",
    help = "Deobfuscate the provided heap dump and generate a new \"-deobfuscated.hprof\" file."
) {

  override fun run() {
    val params = context.sharkCliParams
    val obfuscationMappingFile = params.obfuscationMappingPath
        ?: throw PrintMessage("Error: Missing obfuscation mapping file")
    val heapDumpFile = retrieveHeapDumpFile(params)
    SharkLog.d { "Deobfuscating heap dump $heapDumpFile" }
    val proguardMapping =
      ProguardMappingReader(obfuscationMappingFile.inputStream()).readProguardMapping()
    val deobfuscator = HprofDeobfuscator()
    val outputFile = deobfuscator.deobfuscate(proguardMapping, heapDumpFile)
    echo("Created deobfuscated hprof to $outputFile")
  }
}