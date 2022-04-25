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
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.DoubleArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.FloatArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ShortArrayDump
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
        echo("Deleting $dbFolder")
        dbFolder.deleteRecursively()
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

        dbService.executeTransactionally("create constraint for (object:Object) require object.objectId is unique")
        // TODO Split in several transactions when reaching a predefined threshold.
        val nodeTx = dbService.beginTx()
        echo("Progress nodes: 0%")
        graph.objects.forEachIndexed { index, heapObject ->
          val pct = ((index * 10f) / total).toInt()
          if (pct != lastPct) {
            lastPct = pct
            echo("Progress nodes: ${pct * 10}%")
          }
          when (heapObject) {
            is HeapClass -> {
              heapObject.readStaticFields().forEach { field ->
                field.name
                field.value
              }
              nodeTx.execute(
                "create (:Object {objectType: 'Class', name:\$name, objectId:\$objectId})",
                mapOf(
                  "name" to heapObject.name,
                  "objectId" to heapObject.objectId
                )
              )
            }
            is HeapInstance -> {
              nodeTx.execute(
                "create (:Object {objectType: 'Instance', className:\$className, objectId:\$objectId})",
                mapOf(
                  "className" to heapObject.instanceClassName,
                  "objectId" to heapObject.objectId
                )
              )
            }
            is HeapObjectArray -> {
              nodeTx.execute(
                "create (:Object {objectType: 'ObjectArray', className:\$className, objectId:\$objectId})",
                mapOf(
                  "className" to heapObject.arrayClassName,
                  "objectId" to heapObject.objectId
                )
              )
            }
            is HeapPrimitiveArray -> {
              nodeTx.execute(
                "create (:Object {objectType: 'PrimitiveArray', className:\$className, objectId:\$objectId})",
                mapOf(
                  "className" to heapObject.arrayClassName,
                  "objectId" to heapObject.objectId
                )
              )
            }
          }
        }
        echo("Progress nodes: 100%")
        nodeTx.commit()
        val edgeTx = dbService.beginTx()
        echo("Progress edges: 0%")
        lastPct = 0
        graph.objects.forEachIndexed { index, heapObject ->
          val pct = ((index * 10f) / total).toInt()
          if (pct != lastPct) {
            lastPct = pct
            echo("Progress edges: ${pct * 10}%")
          }
          when (heapObject) {
            is HeapClass -> {
              val fields = heapObject.readStaticFields().mapNotNull { field ->
                if (field.value.isNonNullReference) {
                  mapOf(
                    "targetObjectId" to field.value.asObjectId!!,
                    "name" to field.name
                  )
                } else {
                  null
                }
              }.toList()

              edgeTx.execute(
                "unwind \$fields as field" +
                  " match (source:Object{objectId:\$sourceObjectId}), (target:Object{objectId:field.targetObjectId})" +
                  " create (source)-[:REFERENCE {name:field.name}]->(target)", mapOf(
                  "sourceObjectId" to heapObject.objectId,
                  "fields" to fields
                )
              )

              val primitiveAndNullFields = heapObject.readStaticFields().mapNotNull { field ->
                if (!field.value.isNonNullReference) {
                  "${field.name} = ${field.value.holder}"
                } else {
                  null
                }
              }.toList()

              edgeTx.execute(
                "match (node:Object{objectId:\$objectId})" +
                  " set node.staticFields = \$values",
                mapOf(
                  "objectId" to heapObject.objectId,
                  "values" to primitiveAndNullFields
                )
              )
            }
            is HeapInstance -> {
              val fields = heapObject.readFields().mapNotNull { field ->
                if (field.value.isNonNullReference) {
                  mapOf(
                    "targetObjectId" to field.value.asObjectId!!,
                    "name" to "${heapObject.instanceClassName}.${field.name}"
                  )
                } else {
                  null
                }
              }.toList()

              edgeTx.execute(
                "unwind \$fields as field" +
                  " match (source:Object{objectId:\$sourceObjectId}), (target:Object{objectId:field.targetObjectId})" +
                  " create (source)-[:REFERENCE {name:field.name}]->(target)", mapOf(
                  "sourceObjectId" to heapObject.objectId,
                  "fields" to fields
                )
              )

              val primitiveAndNullFields = heapObject.readFields().mapNotNull { field ->
                if (!field.value.isNonNullReference) {
                  "${heapObject.instanceClassName}.${field.name} = ${field.value.holder}"
                } else {
                  null
                }
              }.toList()

              edgeTx.execute(
                "match (node:Object{objectId:\$objectId})" +
                  " set node.fields = \$values",
                mapOf(
                  "objectId" to heapObject.objectId,
                  "values" to primitiveAndNullFields
                )
              )
            }
            is HeapObjectArray -> {
              // TODO Add null values somehow?

              val elements = heapObject.readRecord().elementIds.mapIndexed { arrayIndex, objectId ->
                if (objectId != ValueHolder.NULL_REFERENCE) {
                  mapOf(
                    "targetObjectId" to objectId,
                    "name" to "[$arrayIndex]"
                  )
                } else {
                  null
                }
              }.filterNotNull().toList()

              edgeTx.execute(
                "unwind \$elements as element" +
                  " match (source:Object{objectId:\$sourceObjectId}), (target:Object{objectId:element.targetObjectId})" +
                  " create (source)-[:REFERENCE {name:element.name}]->(target)", mapOf(
                  "sourceObjectId" to heapObject.objectId,
                  "elements" to elements
                )
              )
            }
            is HeapPrimitiveArray -> {
              when (val record = heapObject.readRecord()) {
                is BooleanArrayDump -> {
                  edgeTx.execute(
                    "match (node:Object{objectId:\$objectId})" +
                      " set node.values = \$values",
                    mapOf(
                      "objectId" to heapObject.objectId,
                      "values" to record.array
                    )
                  )
                }
                is ByteArrayDump -> {
                  edgeTx.execute(
                    "match (node:Object{objectId:\$objectId})" +
                      " set node.values = \$values",
                    mapOf(
                      "objectId" to heapObject.objectId,
                      "values" to record.array
                    )
                  )
                }
                is CharArrayDump -> {
                  edgeTx.execute(
                    "match (node:Object{objectId:\$objectId})" +
                      " set node.values = \$values",
                    mapOf(
                      "objectId" to heapObject.objectId,
                      "values" to record.array
                    )
                  )
                }
                is DoubleArrayDump -> {
                  edgeTx.execute(
                    "match (node:Object{objectId:\$objectId})" +
                      " set node.values = \$values",
                    mapOf(
                      "objectId" to heapObject.objectId,
                      "values" to record.array
                    )
                  )
                }
                is FloatArrayDump -> {
                  edgeTx.execute(
                    "match (node:Object{objectId:\$objectId})" +
                      " set node.values = \$values",
                    mapOf(
                      "objectId" to heapObject.objectId,
                      "values" to record.array
                    )
                  )
                }
                is IntArrayDump -> {
                  edgeTx.execute(
                    "match (node:Object{objectId:\$objectId})" +
                      " set node.values = \$values",
                    mapOf(
                      "objectId" to heapObject.objectId,
                      "values" to record.array
                    )
                  )
                }
                is LongArrayDump -> {
                  edgeTx.execute(
                    "match (node:Object{objectId:\$objectId})" +
                      " set node.values = \$values",
                    mapOf(
                      "objectId" to heapObject.objectId,
                      "values" to record.array
                    )
                  )
                }
                is ShortArrayDump -> {
                  edgeTx.execute(
                    "match (node:Object{objectId:\$objectId})" +
                      " set node.values = \$values",
                    mapOf(
                      "objectId" to heapObject.objectId,
                      "values" to record.array
                    )
                  )
                }
              }
            }
          }
        }
        echo("Progress edges: 100%")
        edgeTx.commit()
      }
      managementService.shutdown()
    }
  }
}
