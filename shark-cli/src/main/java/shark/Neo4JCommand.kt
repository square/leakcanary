package shark

import com.github.ajalt.clikt.core.Abort
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.output.TermUi
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.types.file
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.ref.PhantomReference
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference
import java.util.regex.Pattern
import jline.console.ConsoleReader
import org.neo4j.configuration.GraphDatabaseSettings
import org.neo4j.configuration.connectors.BoltConnector
import org.neo4j.configuration.helpers.SocketAddress
import org.neo4j.dbms.api.DatabaseManagementServiceBuilder
import org.neo4j.graphdb.Entity
import org.neo4j.graphdb.Path
import org.neo4j.graphdb.Relationship
import org.neo4j.graphdb.RelationshipType
import org.neo4j.graphdb.Transaction
import org.neo4j.kernel.api.procedure.GlobalProcedures
import org.neo4j.kernel.internal.GraphDatabaseAPI
import org.neo4j.logging.Level
import org.neo4j.procedure.UserFunction
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
import shark.LeakTraceObject.LeakingStatus.LEAKING
import shark.LeakTraceObject.LeakingStatus.NOT_LEAKING
import shark.LeakTraceObject.LeakingStatus.UNKNOWN
import shark.SharkCliCommand.Companion.echo
import shark.SharkCliCommand.Companion.retrieveHeapDumpFile
import shark.SharkCliCommand.Companion.sharkCliParams
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.ByteHolder
import shark.ValueHolder.CharHolder
import shark.ValueHolder.DoubleHolder
import shark.ValueHolder.FloatHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.LongHolder
import shark.ValueHolder.ReferenceHolder
import shark.ValueHolder.ShortHolder

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
    val REFERENCE = "REF"
    val WEAK_REFERENCE = "WEAK_REF"
    val SOFT_REFERENCE = "SOFT_REF"
    val PHANTOM_REFERENCE = "PHANTOM_REF"

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

      // Get a random, free port. We could do this by using new SocketAddress("localhost", 0)
      // but we wouldn't be able to retrieve the port via Neo4j means afterwards (it would indicate 0 as ephemeral port)

      // TODO Find an available TCP port instead?
      val boltListenPort = 2929

      echo("Creating db in $dbFolder")
      val managementService =
        DatabaseManagementServiceBuilder(dbFolder.toPath().normalize())
          .setConfig(BoltConnector.enabled, true)
          .setConfig(BoltConnector.listen_address, SocketAddress("localhost", boltListenPort))
          .setConfig(GraphDatabaseSettings.store_internal_log_level, Level.DEBUG)
          .build()
      val dbService = managementService.database("neo4j")
      val api = dbService as GraphDatabaseAPI
      val registry = api.dependencyResolver.resolveDependency(GlobalProcedures::class.java)
      registry.registerFunction(FindLeakPaths::class.java)

      // TODO Add test.
      // TODO merge this

      echo("Done with creating empty db, now importing heap dump")
      heapDumpFile.openHeapGraph(proguardMapping).use { graph ->

        val total = graph.objectCount
        var lastPct = 0

        dbService.executeTransactionally("create constraint for (object:Object) require object.objectId is unique")
        dbService.executeTransactionally("create constraint for (class:Class) require class.objectId is unique")
        dbService.executeTransactionally("create constraint for (instance:Instance) require instance.objectId is unique")
        dbService.executeTransactionally("create constraint for (array:ObjectArray) require array.objectId is unique")
        dbService.executeTransactionally("create constraint for (array:PrimitiveArray) require array.objectId is unique")
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
                "create (:Object :Class {objectType: 'Class', name:\$name, className:\$className, objectId:\$objectId})",
                mapOf(
                  "name" to heapObject.simpleName,
                  "className" to heapObject.name,
                  "objectId" to heapObject.objectId
                )
              )
            }
            is HeapInstance -> {
              nodeTx.execute(
                "create (:Object :Instance {objectType: 'Instance', name:\$name, className:\$className, objectId:\$objectId})",
                mapOf(
                  "name" to "${heapObject.instanceClassSimpleName}@" + (heapObject.hexIdentityHashCode
                    ?: heapObject.positiveObjectId),
                  "className" to heapObject.instanceClassName,
                  "objectId" to heapObject.objectId,
                )
              )
            }
            is HeapObjectArray -> {
              nodeTx.execute(
                "create (:Object :ObjectArray {objectType: 'ObjectArray', name:\$name, className:\$className, objectId:\$objectId})",
                mapOf(
                  "name" to "${heapObject.arrayClassSimpleName}[]@${heapObject.positiveObjectId}",
                  "className" to heapObject.arrayClassName,
                  "objectId" to heapObject.objectId,
                )
              )
            }
            is HeapPrimitiveArray -> {
              nodeTx.execute(
                "create (:Object :PrimitiveArray {objectType: 'PrimitiveArray', name:\$name, className:\$className, objectId:\$objectId})",
                mapOf(
                  "name" to "${heapObject.arrayClassName}@${heapObject.positiveObjectId}",
                  "className" to heapObject.arrayClassName,
                  "objectId" to heapObject.objectId
                )
              )
            }
          }
        }
        echo("Progress nodes: 100%, committing transaction")
        nodeTx.commit()

        val classTx = dbService.beginTx()
        echo("Progress class hierarchy: 0%")
        lastPct = 0
        graph.objects.forEachIndexed { index, heapObject ->
          val pct = ((index * 10f) / total).toInt()
          if (pct != lastPct) {
            lastPct = pct
            echo("Progress class hierarchy: ${pct * 10}%")
          }
          when (heapObject) {
            is HeapClass -> {
              heapObject.superclass?.let { superclass ->
                classTx.execute(
                  "match (superclass:Class{objectId:\$superclassObjectId}) , (class:Class {objectId:\$objectId}) create (class) -[:SUPER]->(superclass)",
                  mapOf(
                    "objectId" to heapObject.objectId,
                    "superclassObjectId" to superclass.objectId
                  )
                )
              }
            }
            is HeapInstance -> {
              classTx.execute(
                "match (class:Class{objectId:\$classObjectId}) , (instance:Instance {objectId:\$objectId}) create (instance) -[:CLASS]->(class)",
                mapOf(
                  "objectId" to heapObject.objectId,
                  "classObjectId" to heapObject.instanceClassId
                )
              )
            }
            is HeapObjectArray -> {
              classTx.execute(
                "match (class:Class{objectId:\$classObjectId}) , (array:ObjectArray {objectId:\$objectId}) create (array) -[:CLASS]->(class)",
                mapOf(
                  "objectId" to heapObject.objectId,
                  "classObjectId" to heapObject.arrayClassId
                )
              )
            }
          }
        }
        echo("Progress class hierarchy: 100%, committing transaction")
        classTx.commit()

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
                  " create (source)-[:$REFERENCE {name:field.name}]->(target)", mapOf(
                  "sourceObjectId" to heapObject.objectId,
                  "fields" to fields
                )
              )

              val primitiveAndNullFields = heapObject.readStaticFields().mapNotNull { field ->
                if (!field.value.isNonNullReference) {
                  "${field.name}: ${field.value.heapValueAsString()}"
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
                    "name" to "${field.declaringClass.name}.${field.name}"
                  )
                } else {
                  null
                }
              }.toList()

              val (updatedFields, referentField, refType) = when {
                heapObject instanceOf WeakReference::class -> {
                  val referentField = heapObject["java.lang.ref.Reference", "referent"]
                  Triple(
                    fields.filter { it["name"] != "java.lang.ref.Reference.referent" },
                    referentField,
                    WEAK_REFERENCE
                  )
                }
                heapObject instanceOf SoftReference::class -> {
                  val referentField = heapObject["java.lang.ref.Reference", "referent"]
                  Triple(
                    fields.filter { it["name"] != "java.lang.ref.Reference.referent" },
                    referentField,
                    SOFT_REFERENCE
                  )
                }
                heapObject instanceOf PhantomReference::class -> {
                  val referentField = heapObject["java.lang.ref.Reference", "referent"]
                  Triple(
                    fields.filter { it["name"] != "java.lang.ref.Reference.referent" },
                    referentField,
                    PHANTOM_REFERENCE
                  )
                }
                else -> Triple(fields, null, null)
              }

              edgeTx.execute(
                "unwind \$fields as field" +
                  " match (source:Object{objectId:\$sourceObjectId}), (target:Object{objectId:field.targetObjectId})" +
                  " create (source)-[:$REFERENCE {name:field.name}]->(target)", mapOf(
                  "sourceObjectId" to heapObject.objectId,
                  "fields" to updatedFields
                )
              )

              if (referentField != null) {
                edgeTx.execute(
                  "match (source:Object{objectId:\$sourceObjectId}), (target:Object{objectId:\$targetObjectId})" +
                    " create (source)-[:$refType {name:\"java.lang.ref.Reference.referent\"}]->(target)",
                  mapOf(
                    "sourceObjectId" to heapObject.objectId,
                    "targetObjectId" to referentField.value.asObjectId!!,
                  )
                )
              }

              val primitiveAndNullFields = heapObject.readFields().mapNotNull { field ->
                if (!field.value.isNonNullReference) {
                  "${field.declaringClass.name}.${field.name} = ${field.value.heapValueAsString()}"
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
                  " create (source)-[:$REFERENCE {name:element.name}]->(target)", mapOf(
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
                      "values" to record.array.joinToString()
                    )
                  )
                }
                is ByteArrayDump -> {
                  edgeTx.execute(
                    "match (node:Object{objectId:\$objectId})" +
                      " set node.values = \$values",
                    mapOf(
                      "objectId" to heapObject.objectId,
                      "values" to record.array.joinToString()
                    )
                  )
                }
                is CharArrayDump -> {
                  edgeTx.execute(
                    "match (node:Object{objectId:\$objectId})" +
                      " set node.values = \$values",
                    mapOf(
                      "objectId" to heapObject.objectId,
                      "values" to record.array.joinToString()
                    )
                  )
                }
                is DoubleArrayDump -> {
                  edgeTx.execute(
                    "match (node:Object{objectId:\$objectId})" +
                      " set node.values = \$values",
                    mapOf(
                      "objectId" to heapObject.objectId,
                      "values" to record.array.joinToString()
                    )
                  )
                }
                is FloatArrayDump -> {
                  edgeTx.execute(
                    "match (node:Object{objectId:\$objectId})" +
                      " set node.values = \$values",
                    mapOf(
                      "objectId" to heapObject.objectId,
                      "values" to record.array.joinToString()
                    )
                  )
                }
                is IntArrayDump -> {
                  edgeTx.execute(
                    "match (node:Object{objectId:\$objectId})" +
                      " set node.values = \$values",
                    mapOf(
                      "objectId" to heapObject.objectId,
                      "values" to record.array.joinToString()
                    )
                  )
                }
                is LongArrayDump -> {
                  edgeTx.execute(
                    "match (node:Object{objectId:\$objectId})" +
                      " set node.values = \$values",
                    mapOf(
                      "objectId" to heapObject.objectId,
                      "values" to record.array.joinToString()
                    )
                  )
                }
                is ShortArrayDump -> {
                  edgeTx.execute(
                    "match (node:Object{objectId:\$objectId})" +
                      " set node.values = \$values",
                    mapOf(
                      "objectId" to heapObject.objectId,
                      "values" to record.array.joinToString()
                    )
                  )
                }
              }
            }
          }
        }
        echo("Progress edges: 100%, committing transaction")
        edgeTx.commit()

        val labelsTx = dbService.beginTx()
        echo("Progress labels: 0%")
        lastPct = 0
        val inspectors = AndroidObjectInspectors.appDefaults
        val leakFilters = ObjectInspectors.jdkLeakingObjectFilters

        graph.objects.forEachIndexed { index, heapObject ->
          val pct = ((index * 10f) / total).toInt()
          if (pct != lastPct) {
            lastPct = pct
            echo("Progress labels: ${pct * 10}%")
          }

          val leaked = leakFilters.any { filter ->
            filter.isLeakingObject(heapObject)
          }

          val reporter = ObjectReporter(heapObject)
          inspectors.forEach { inspector ->
            inspector.inspect(reporter)
          }

          // Cribbed from shark.HeapAnalyzer.resolveStatus
          var status = UNKNOWN
          var reason = ""
          if (reporter.notLeakingReasons.isNotEmpty()) {
            status = NOT_LEAKING
            reason = reporter.notLeakingReasons.joinToString(" and ")
          }
          val leakingReasons = reporter.leakingReasons
          if (leakingReasons.isNotEmpty()) {
            val winReasons = leakingReasons.joinToString(" and ")
            // Conflict
            if (status == NOT_LEAKING) {
              reason += ". Conflicts with $winReasons"
            } else {
              status = LEAKING
              reason = winReasons
            }
          }

          labelsTx.execute(
            "match (node:Object{objectId:\$objectId})" +
              " set node.leakingStatus = \$leakingStatus, node.leakingStatusReason = \$leakingStatusReason",
            mapOf(
              "objectId" to heapObject.objectId,
              "leakingStatus" to status.name,
              "leakingStatusReason" to reason
            )
          )

          if (reporter.labels.isNotEmpty()) {
            labelsTx.execute(
              "match (node:Object{objectId:\$objectId})" +
                " set node.labels = \$labels",
              mapOf(
                "objectId" to heapObject.objectId,
                "labels" to reporter.labels,
              )
            )
          }

          if (leaked) {
            labelsTx.execute(
              "match (node:Object{objectId:\$objectId})" +
                " set node.leaked = true",
              mapOf(
                "objectId" to heapObject.objectId,
              )
            )
          }
        }
        echo("Progress labels: 100%, committing transaction")
        labelsTx.commit()

        val gcRootsTx = dbService.beginTx()
        echo("Progress gc roots: 0%")
        lastPct = 0
        val gcRootTotal = graph.gcRoots.size

        // A root for all gc roots that makes it easy to query starting from that single root.
        gcRootsTx.execute("create (:GcRoots {name:\"GC roots\", leakingStatus:\"${NOT_LEAKING.name}\"})")

        graph.gcRoots.forEachIndexed { index, gcRoot ->
          val pct = ((index * 10f) / gcRootTotal).toInt()
          if (pct != lastPct) {
            lastPct = pct
            echo("Progress gc roots: ${pct * 10}%")
          }
          gcRootsTx.execute(
            "match (roots:GcRoots), (object:Object{objectId:\$objectId}) create (roots)-[:ROOT]->(:GcRoot {type:\$type})-[:ROOT]->(object)",
            mapOf(
              "objectId" to gcRoot.id,
              "type" to gcRoot::class.java.simpleName
            )
          )
        }
        gcRootsTx.commit()
      }

      echo("Retrieving server bolt port...")

      // TODO Unclear why we need to query the port again?
      val boltPort = dbService.executeTransactionally(
        "CALL dbms.listConfig() yield name, value " +
          "WHERE name = 'dbms.connector.bolt.listen_address' " +
          "RETURN value", mapOf()
      ) { result ->
        val listenAddress = result.next()["value"] as String
        val pattern = Pattern.compile("(?:\\w+:)?(\\d+)")
        val matcher = pattern.matcher(listenAddress)
        if (!matcher.matches()) {
          error("Could not extract bolt port from [$listenAddress]");
        }
        matcher.toMatchResult().group(1)
      }

      val browserUrl = "http://browser.graphapp.io/?dbms=bolt://localhost:$boltPort"
      echo("Opening: $browserUrl")
      Runtime.getRuntime().exec("open $browserUrl")
      ConsoleReader().readLine("Press ENTER to shut down Neo4j server")
      echo("Shutting down...")
      managementService.shutdown()
    }

    fun HeapValue.heapValueAsString(): String {
      return when (val heapValue = holder) {
        is ReferenceHolder -> {
          if (isNullReference) {
            "null"
          } else {
            error("should not happen")
          }
        }
        is BooleanHolder -> heapValue.value.toString()
        is CharHolder -> heapValue.value.toString()
        is FloatHolder -> heapValue.value.toString()
        is DoubleHolder -> heapValue.value.toString()
        is ByteHolder -> heapValue.value.toString()
        is ShortHolder -> heapValue.value.toString()
        is IntHolder -> heapValue.value.toString()
        is LongHolder -> heapValue.value.toString()
      }
    }
  }
}

class FindLeakPaths {

  @org.neo4j.procedure.Context
  lateinit var transaction: Transaction

  @UserFunction("shark.leakPaths")
  fun leakPaths(): List<Path> {
    try {
      val result = transaction.execute(
        """
      match (roots:GcRoots)
      match (object:Object {leaked: true})
        with shortestPath((roots)-[:ROOT|REF*]->(object)) as path
        where reduce(
            leakCount=0, n in nodes(path) | leakCount + case n.leaked when true then 1 else 0 end
          ) = 1
      return path
      """.trimIndent()
      )

      return result.asSequence().map { row ->
        val realPath = row["path"] as Path
        DecoratedPath(realPath)
      }.toList()
    } catch (e: Throwable) {
      TermUi.echo("failed to findLeakPaths: " + getStackTraceString(e))
      throw e
    }
  }

  private fun getStackTraceString(throwable: Throwable): String {
    val stringWriter = StringWriter()
    val printWriter = PrintWriter(stringWriter, false)
    throwable.printStackTrace(printWriter)
    printWriter.flush()
    return stringWriter.toString()
  }
}

class DecoratedPath(private val delegate: Path) : Path by delegate {

  private val relationships by lazy {
    // TODO Here we'll map a subset of relationships as one of not leaking, leak suspect, leaking.
    // We can then add the "leaking reason" as an attribute of the relationship.
    // Then we should remove these 2 from the full dump. We can find the leaking nodes early
    // and set those attribute as part of node creation instead of a separate transaction.
    // The mapping of relationships here can be down dy duplicating the logic in
    // shark.HeapAnalyzer.computeLeakStatuses which goes through relationships and splits
    // the path in 3 areas (not leaking, leak suspect, leaking).
    delegate.relationships().toList()
  }

  override fun relationships(): Iterable<Relationship> {
    return relationships
  }

  override fun reverseRelationships(): Iterable<Relationship> {
    return relationships.asReversed()
  }

  override fun iterator(): MutableIterator<Entity> {
    val nodeList = nodes().toList()
    val relationshipsList = relationships
    return (listOf(nodeList[0]) + relationshipsList.indices.flatMap { index ->
      listOf(relationshipsList[index], nodeList[index])
    }).toMutableList().iterator()
  }
}
