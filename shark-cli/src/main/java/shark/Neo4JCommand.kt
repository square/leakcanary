package shark

import com.github.ajalt.clikt.core.Abort
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.output.TermUi
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.SharkCliCommand.Companion.echo
import shark.SharkCliCommand.Companion.retrieveHeapDumpFile
import shark.SharkCliCommand.Companion.sharkCliParams

class Neo4JCommand : CliktCommand(
  name = "neo4j",
  help = "Convert heap dump to Neo4j database"
) {

  private val optionalDbFolder by argument("NEO4J_DATABASE_DIRECTORY").file(
    exists = true,
    fileOkay = false,
    folderOkay = true,
    writable = true
  ).optional()

  override fun run() {
    val params = context.sharkCliParams
    val heapDumpFile = retrieveHeapDumpFile(params)

    val dbFolder = optionalDbFolder ?: heapDumpFile.parentFile

    dump(heapDumpFile, dbFolder, params.obfuscationMappingPath)
  }

  companion object {
    fun CliktCommand.dump(
      heapDumpFile: File,
      dbParentFolder: File,
      proguardMappingFile: File?
    ) {
      val proguardMapping = proguardMappingFile?.let {
        ProguardMappingReader(it.inputStream()).readProguardMapping()
      }

      val name = heapDumpFile.name.substringBeforeLast(".hprof")
      val dbFolder = File(dbParentFolder, name)

      if (dbFolder.exists()) {
        val continueImport = TermUi.confirm(
          "Directory $dbFolder already exists, delete it and continue?",
          default = true,
          abort = true
        ) ?: false

        if (!continueImport) {
          throw Abort()
        }
        dbFolder.delete()
      }

      // TODO Support driver + embedded?

      echo("Creating db in $dbFolder")
      val managementService =
        DatabaseManagementServiceBuilder(dbFolder.toPath().normalize()).build()
      val dbService = managementService.database("neo4j")
      echo("Done with creating empty db, now importing heap dump")
      heapDumpFile.openHeapGraph(proguardMapping).use { graph ->
        val total = graph.objectCount
        var lastPct = 0

        // TODO Split in several transactions when reaching a predefined threshold.
        val currentTx = dbService.beginTx()
        echo("Progress: 0%")
        graph.objects.forEachIndexed { index, heapObject ->
          val pct = ((index * 10f) / total).toInt()
          if (pct != lastPct) {
            lastPct = pct
            echo("Progress: ${pct*10}%")
          }
          when (heapObject) {
            is HeapClass -> {
              currentTx.execute(
                "create (:Class {name:\$name})",
                mapOf("name" to heapObject.name)
              )
            }
            is HeapInstance -> {
              currentTx.execute(
                "create (:Instance {className:\$className})",
                mapOf("className" to heapObject.instanceClassName)
              )
            }
            is HeapObjectArray -> {
              currentTx.execute(
                "create (:ObjectArray {className:\$className})",
                mapOf("className" to heapObject.arrayClassName)
              )
            }
            is HeapPrimitiveArray -> {
              currentTx.execute(
                "create (:PrimitiveArray {className:\$className})",
                mapOf("className" to heapObject.arrayClassName)
              )
            }
          }
        }
        echo("Progress: 100%")
        currentTx.commit()
      }
      managementService.shutdown()
    }
  }
}
