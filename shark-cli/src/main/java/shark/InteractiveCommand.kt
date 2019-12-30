package shark

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.jakewharton.picnic.renderText
import com.jakewharton.picnic.table
import jline.console.ConsoleReader
import jline.console.UserInterruptException
import jline.console.completer.CandidateListCompletionHandler
import jline.console.completer.StringsCompleter
import org.apache.calcite.adapter.java.AbstractQueryableTable
import org.apache.calcite.jdbc.CalciteConnection
import org.apache.calcite.linq4j.Enumerator
import org.apache.calcite.linq4j.Linq4j
import org.apache.calcite.linq4j.QueryProvider
import org.apache.calcite.linq4j.Queryable
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.schema.SchemaPlus
import org.apache.calcite.schema.Table
import org.apache.calcite.schema.impl.AbstractSchema
import org.apache.calcite.schema.impl.AbstractTableQueryable
import org.apache.calcite.sql.parser.SqlParseException
import org.apache.calcite.sql.type.SqlTypeName
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.DoubleArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.FloatArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ShortArrayDump
import shark.InteractiveCommand.COMMAND.ANALYZE
import shark.InteractiveCommand.COMMAND.ARRAY
import shark.InteractiveCommand.COMMAND.CLASS
import shark.InteractiveCommand.COMMAND.Companion.matchesCommand
import shark.InteractiveCommand.COMMAND.DETAILED_PATH_TO_INSTANCE
import shark.InteractiveCommand.COMMAND.EXIT
import shark.InteractiveCommand.COMMAND.HELP
import shark.InteractiveCommand.COMMAND.INSTANCE
import shark.InteractiveCommand.COMMAND.PATH_TO_INSTANCE
import shark.InteractiveCommand.COMMAND.SQL
import shark.PrimitiveType.BOOLEAN
import shark.PrimitiveType.BYTE
import shark.PrimitiveType.CHAR
import shark.PrimitiveType.DOUBLE
import shark.PrimitiveType.FLOAT
import shark.PrimitiveType.INT
import shark.PrimitiveType.LONG
import shark.PrimitiveType.SHORT
import shark.SharkCliCommand.Companion.echoNewline
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
import java.io.File
import java.sql.DriverManager
import java.util.Locale
import java.util.Properties

@Suppress("TooManyFunctions")
class InteractiveCommand : CliktCommand(
    name = "interactive",
    help = "Explore a heap dump."
) {

  enum class COMMAND(
    val commandName: String,
    val suffix: String = "",
    val help: String
  ) {
    ANALYZE(
        commandName = "analyze",
        help = "Analyze the heap dump."
    ),
    CLASS(
        commandName = "class",
        suffix = "NAME@ID",
        help = "Show class with a matching NAME and Object ID."
    ),
    INSTANCE(
        commandName = "instance",
        suffix = "CLASS_NAME@ID",
        help = "Show instance with a matching CLASS_NAME and Object ID."
    ),
    ARRAY(
        commandName = "array",
        suffix = "CLASS_NAME@ID",
        help = "Show array instance with a matching CLASS_NAME and Object ID."
    ),
    PATH_TO_INSTANCE(
        commandName = "->instance",
        suffix = "CLASS_NAME@ID",
        help = "Show path from GC Roots to instance."
    ),
    DETAILED_PATH_TO_INSTANCE(
        commandName = "~>instance",
        suffix = "CLASS_NAME@ID",
        help = "Show path from GC Roots to instance, highlighting suspect references."
    ),
    SQL(
        commandName = "sql",
        suffix = "SQL_STATEMENT",
        help = "Explore the heap dump via SQL"
    ),
    HELP(
        commandName = "help",
        help = "Show this message."
    ),
    EXIT(
        commandName = "exit",
        help = "Exit this interactive prompt."
    ),
    ;

    val pattern: String
      get() = if (suffix.isEmpty()) commandName else "$commandName "

    val patternHelp: String
      get() = pattern + suffix

    override fun toString() = commandName

    companion object {
      infix fun String.matchesCommand(command: COMMAND): Boolean {
        return if (command.suffix.isEmpty()) {
          this == command.commandName
        } else {
          startsWith(command.pattern)
        }
      }
    }
  }

  override fun run() {
    openHprof { graph, heapDumpFile ->
      val console = setupConsole(graph)
      var exit = false
      while (!exit) {
        val input = console.readCommand()
        exit = handleCommand(input, heapDumpFile, graph)
        echoNewline()
      }
    }
  }

  private fun openHprof(block: (HeapGraph, File) -> Unit) {
    val params = context.sharkCliParams
    val heapDumpFile = retrieveHeapDumpFile(params)
    val obfuscationMappingPath = params.obfuscationMappingPath

    val proguardMapping = obfuscationMappingPath?.let {
      ProguardMappingReader(it.inputStream()).readProguardMapping()
    }

    Hprof.open(heapDumpFile)
        .use { hprof ->
          val graph = HprofHeapGraph.indexHprof(hprof, proguardMapping)
          block(graph, heapDumpFile)
        }
  }

  private fun setupConsole(graph: HeapGraph): ConsoleReader {
    val console = ConsoleReader()
    console.handleUserInterrupt = true

    console.addCompleter(StringsCompleter(COMMAND.values().map { it.pattern }))
    console.addCompleter { buffer, _, candidates ->
      if (buffer != null) {
        when {
          buffer matchesCommand CLASS -> {
            val matchingObjects = findMatchingObjects(buffer, graph.classes) {
              it.name
            }
            candidates.addAll(matchingObjects.map { renderHeapObject(it) })
          }
          buffer matchesCommand INSTANCE -> {
            val matchingObjects = findMatchingObjects(buffer, graph.instances) {
              it.instanceClassSimpleName
            }
            candidates.addAll(matchingObjects.map { renderHeapObject(it) })
          }
          buffer matchesCommand PATH_TO_INSTANCE -> {
            val matchingObjects = findMatchingObjects(buffer, graph.instances) {
              it.instanceClassSimpleName
            }
            candidates.addAll(matchingObjects.map { "->${renderHeapObject(it)}" })
          }
          buffer matchesCommand DETAILED_PATH_TO_INSTANCE -> {
            val matchingObjects = findMatchingObjects(buffer, graph.instances) {
              it.instanceClassSimpleName
            }
            candidates.addAll(matchingObjects.map { "~>${renderHeapObject(it)}" })
          }
          buffer matchesCommand ARRAY -> {
            val matchingObjects =
              findMatchingObjects(buffer, graph.primitiveArrays + graph.objectArrays) {
                if (it is HeapPrimitiveArray) {
                  it.arrayClassName
                } else {
                  (it as HeapObjectArray).arrayClassSimpleName
                }
              }
            candidates.addAll(matchingObjects.map { renderHeapObject(it) })
          }
        }
      }
      if (candidates.isEmpty()) -1 else 0
    }
    val completionHandler = CandidateListCompletionHandler()
    completionHandler.printSpaceAfterFullCompletion = false
    console.completionHandler = completionHandler
    console.prompt = "Enter command [help]:\n"
    return console
  }

  private fun ConsoleReader.readCommand(): String? {
    val input = try {
      readLine()
    } catch (ignored: UserInterruptException) {
      throw PrintMessage("Program interrupted by user")
    }
    echoNewline()
    return input
  }

  private fun handleCommand(
    input: String?,
    heapDumpFile: File,
    graph: HeapGraph
  ): Boolean {
    when {
      input == null -> throw PrintMessage("End Of File was encountered")
      input.isBlank() || input matchesCommand HELP -> echoHelp()
      input matchesCommand EXIT -> return true
      input matchesCommand ANALYZE -> analyze(heapDumpFile, graph)
      input matchesCommand PATH_TO_INSTANCE -> {
        analyzeMatchingObjects(heapDumpFile, input, graph.instances, false) {
          it.instanceClassSimpleName
        }
      }
      input matchesCommand DETAILED_PATH_TO_INSTANCE -> {
        analyzeMatchingObjects(heapDumpFile, input, graph.instances, true) {
          it.instanceClassSimpleName
        }
      }
      input matchesCommand CLASS -> {
        renderMatchingObjects(input, graph.classes) {
          it.name
        }
      }
      input matchesCommand INSTANCE -> {
        renderMatchingObjects(input, graph.instances) {
          it.instanceClassSimpleName
        }
      }
      input matchesCommand ARRAY -> {
        renderMatchingObjects(input, graph.primitiveArrays + graph.objectArrays) {
          if (it is HeapPrimitiveArray) {
            it.arrayClassName
          } else {
            (it as HeapObjectArray).arrayClassSimpleName
          }
        }
      }
      input matchesCommand SQL -> {
        val firstSpaceIndex = input.indexOf(' ')
        val contentStartIndex = firstSpaceIndex + 1
        val query = input.substring(contentStartIndex)
        Class.forName("org.apache.calcite.jdbc.Driver")
        val info = Properties()
        info.setProperty("lex", "JAVA")
        info.put("quoting", "DOUBLE_QUOTE");

        DriverManager.getConnection("jdbc:calcite:", info)
            .unwrap(CalciteConnection::class.java)
            .use { connection ->

              // TODO How should we query static fields?

              val tables = graph.classes.map { heapClass ->
                // TODO not sure what elementType is about
                val elementType = Array<Any>::class.java
                heapClass.name to object : AbstractQueryableTable(elementType) {
                  override fun <T : Any?> asQueryable(
                    queryProvider: QueryProvider,
                    schema: SchemaPlus,
                    tableName: String
                  ): Queryable<T> {
                    @Suppress("UNCHECKED_CAST")
                    return object : AbstractTableQueryable<Array<Any?>>(
                        queryProvider, schema,
                        this, tableName
                    ) {
                      override fun enumerator(): Enumerator<Array<Any?>> {
                        return Linq4j.iterableEnumerator(object : Iterable<Array<Any?>> {
                          override fun iterator(): Iterator<Array<Any?>> {
                            return heapClass.instances.map { instance ->
                              (listOf(
                                  instance
                              ) + instance.readFields().filter {
                                !it.name.startsWith(
                                    "shadow\$_"
                                )
                              }.toList().reversed().map { field ->
                                when (val holder = field.value.holder) {
                                  is ReferenceHolder -> field.value.asObject
                                  is BooleanHolder -> holder.value
                                  is CharHolder -> holder.value
                                  is FloatHolder -> holder.value
                                  is DoubleHolder -> holder.value
                                  is ByteHolder -> holder.value
                                  is ShortHolder -> holder.value
                                  is IntHolder -> holder.value
                                  is LongHolder -> holder.value
                                }
                              }).toTypedArray()

                            }
                                .iterator()
                          }
                        })
                      }

                    } as Queryable<T>
                  }

                  override fun getRowType(typeFactory: RelDataTypeFactory): RelDataType {
                    // TODO Cache per typeFactory?
                    val columns = mutableMapOf<String, RelDataType>()

                    val any = typeFactory.createSqlType(SqlTypeName.ANY)
                    columns["this"] = any

                    val allFields = heapClass.classHierarchy.flatMap { it.readFields() }
                        .filter { !it.name.startsWith("shadow\$_") }
                        .toList()
                        .reversed()

                    // TODO Can we make fields from parent classes available?
                    // Right now we just erase them. Which creates conflicts with columns
                    allFields.forEach { field ->
                      // TODO Prebuild that map
                      columns[field.name] =
                        if (field.isReference) any else when (field.primitiveType) {
                          BOOLEAN -> typeFactory.createJavaType(Boolean::class.javaPrimitiveType)
                          CHAR -> typeFactory.createJavaType(Char::class.javaPrimitiveType)
                          FLOAT -> typeFactory.createJavaType(Float::class.javaPrimitiveType)
                          DOUBLE -> typeFactory.createJavaType(Double::class.javaPrimitiveType)
                          BYTE -> typeFactory.createJavaType(Byte::class.javaPrimitiveType)
                          SHORT -> typeFactory.createJavaType(Short::class.javaPrimitiveType)
                          INT -> typeFactory.createJavaType(Int::class.javaPrimitiveType)
                          LONG -> typeFactory.createJavaType(Long::class.javaPrimitiveType)
                        }
                    }

                    val columnList = columns.toList()
                    return typeFactory.createStructType(
                        columnList.map { it.second }, columnList.map { it.first })
                  }
                }
              }
                  .toMap()

              val heapSchema = object : AbstractSchema() {
                override fun getTableMap(): Map<String, Table> {
                  return tables
                }

                override fun isMutable() = false
              }

              connection.rootSchema.add("HEAP", heapSchema)
              connection.schema = "HEAP"
              connection.createStatement()
                  .use { statement ->
                    try {
                      statement.executeQuery(query)
                          .use { resultSet ->
                            val columnCount = resultSet.metaData.columnCount

                            val table = table {
                              cellStyle {
                                border = true
                                paddingLeft = 1
                                paddingRight = 1
                              }
                              header {
                                row {
                                  for (columnIndex in 1..columnCount) {
                                    cell(resultSet.metaData.getColumnName(columnIndex))
                                  }
                                }
                              }
                              body {
                                while (resultSet.next()) {
                                  row {
                                    for (columnIndex in 1..columnCount) {
                                      val columnValue = resultSet.getObject(columnIndex)
                                      if (columnValue is HeapObject) {
                                        cell(renderHeapObject(columnValue))
                                      } else {
                                        cell(columnValue)
                                      }
                                    }
                                  }
                                }
                              }
                            }

                            echo(table.renderText())

                          }
                    } catch (exception: Exception) {
                      // TODO handle this.
                      exception.printStackTrace()
                    }
                  }
            }
      }

      else -> {
        echo("Unknown command [$input].\n")
        echoHelp()
      }
    }
    return false
  }

  private fun echoHelp() {
    echo("Available commands:")
    val longestPatternHelp = COMMAND.values()
        .map { it.patternHelp }.maxBy { it.length }!!.length
    COMMAND.values()
        .forEach { command ->
          val patternHelp = command.patternHelp
          val extraSpaceCount = (longestPatternHelp - patternHelp.length)
          val extraSpaces = " ".repeat(extraSpaceCount)
          println("  $patternHelp$extraSpaces  ${command.help}")
        }
  }

  private fun <T : HeapObject> renderMatchingObjects(
    pattern: String,
    objects: Sequence<T>,
    namer: (T) -> String
  ) {
    val matchingObjects = findMatchingObjects(pattern, objects, namer)
    when {
      matchingObjects.size == 1 -> {
        matchingObjects.first()
            .show()
      }
      matchingObjects.isNotEmpty() -> {
        matchingObjects.forEach { heapObject ->
          echo(renderHeapObject(heapObject))
        }
      }
      else -> {
        echo("No object found matching [$pattern]")
      }
    }
  }

  private fun <T : HeapObject> analyzeMatchingObjects(
    heapDumpFile: File,
    pattern: String,
    objects: Sequence<T>,
    showDetails: Boolean,
    namer: (T) -> String
  ) {
    val matchingObjects = findMatchingObjects(pattern, objects, namer)
    when {
      matchingObjects.size == 1 -> {
        val heapObject = matchingObjects.first()
        analyze(heapDumpFile, heapObject.graph, showDetails, heapObject.objectId)
      }
      matchingObjects.isNotEmpty() -> {
        matchingObjects.forEach { heapObject ->
          echo(if (showDetails) "~>" else "->" + renderHeapObject(heapObject))
        }
      }
      else -> {
        echo("No object found matching [$pattern]")
      }
    }
  }

  private fun <T : HeapObject> findMatchingObjects(
    pattern: String,
    objects: Sequence<T>,
    namer: (T) -> String
  ): List<T> {
    val firstSpaceIndex = pattern.indexOf(' ')
    val contentStartIndex = firstSpaceIndex + 1
    val nextSpaceIndex = pattern.indexOf(' ', contentStartIndex)
    val endIndex = if (nextSpaceIndex != -1) nextSpaceIndex else pattern.length
    val content = pattern.substring(contentStartIndex, endIndex)
    val identifierIndex = content.indexOf('@')
    val (classNamePart, objectIdStart) = if (identifierIndex == -1) {
      content to null
    } else {
      content.substring(0, identifierIndex) to
          content.substring(identifierIndex + 1)
    }

    val objectId = objectIdStart?.toLongOrNull()
    val checkObjectId = objectId != null
    val matchingObjects = objects
        .filter {
          classNamePart in namer(it) &&
              (!checkObjectId ||
                  it.objectId.toString().startsWith(objectIdStart!!))
        }
        .toList()

    if (objectIdStart != null) {
      val exactMatchingByObjectId = matchingObjects.firstOrNull { objectId == it.objectId }
      if (exactMatchingByObjectId != null) {
        return listOf(exactMatchingByObjectId)
      }
    }

    val exactMatchingByName = matchingObjects.filter { classNamePart == namer(it) }

    return if (exactMatchingByName.isNotEmpty()) {
      exactMatchingByName
    } else {
      matchingObjects
    }
  }

  private fun HeapObject.show() {
    when (this) {
      is HeapInstance -> showInstance()
      is HeapClass -> showClass()
      is HeapObjectArray -> showObjectArray()
      is HeapPrimitiveArray -> showPrimitiveArray()
    }
  }

  private fun HeapInstance.showInstance() {
    echo(renderHeapObject(this))
    echo("  Instance of ${renderHeapObject(instanceClass)}")

    val fieldsPerClass = readFields()
        .toList()
        .groupBy { it.declaringClass }
        .toList()
        .filter { it.first.name != "java.lang.Object" }
        .reversed()

    fieldsPerClass.forEach { (heapClass, fields) ->
      echo("  Fields from ${renderHeapObject(heapClass)}")
      fields.forEach { field ->
        echo("    ${field.name} = ${renderHeapValue(field.value)}")
      }
    }
  }

  private fun HeapClass.showClass() {
    echo(this@InteractiveCommand.renderHeapObject(this))
    val superclass = superclass
    if (superclass != null) {
      echo("  Extends ${renderHeapObject(superclass)}")
    }

    val staticFields = readStaticFields()
        .filter { field ->
          !field.name.startsWith(
              "\$class\$"
          ) && field.name != "\$classOverhead"
        }
        .toList()
    if (staticFields.isNotEmpty()) {
      echo("  Static fields")
      staticFields
          .forEach { field ->
            echo("    static ${field.name} = ${renderHeapValue(field.value)}")
          }
    }

    val instances = when {
      isPrimitiveArrayClass -> primitiveArrayInstances
      isObjectArrayClass -> objectArrayInstances
      else -> instances
    }.toList()
    if (instances.isNotEmpty()) {
      echo("  ${instances.size} instance" + if (instances.size != 1) "s" else "")
      instances.forEach { arrayOrInstance ->
        echo("    ${renderHeapObject(arrayOrInstance)}")
      }
    }
  }

  private fun HeapObjectArray.showObjectArray() {
    val elements = readElements()
    echo(renderHeapObject(this))
    echo("  Instance of ${renderHeapObject(arrayClass)}")
    var repeatedValue: HeapValue? = null
    var repeatStartIndex = 0
    var lastIndex = 0
    elements.forEachIndexed { index, element ->
      lastIndex = index
      if (repeatedValue == null) {
        repeatedValue = element
        repeatStartIndex = index
      } else if (repeatedValue != element) {
        val repeatEndIndex = index - 1
        if (repeatStartIndex == repeatEndIndex) {
          echo("  $repeatStartIndex = ${renderHeapValue(repeatedValue!!)}")
        } else {
          echo("  $repeatStartIndex..$repeatEndIndex = ${renderHeapValue(repeatedValue!!)}")
        }
        repeatedValue = element
        repeatStartIndex = index
      }
    }
    if (repeatedValue != null) {
      if (repeatStartIndex == lastIndex) {
        echo("  $repeatStartIndex = ${renderHeapValue(repeatedValue!!)}")
      } else {
        echo("  $repeatStartIndex..$lastIndex = ${renderHeapValue(repeatedValue!!)}")
      }
    }
  }

  private fun HeapPrimitiveArray.showPrimitiveArray() {
    val record = readRecord()
    echo(renderHeapObject(this))
    echo("  Instance of ${renderHeapObject(arrayClass)}")

    var repeatedValue: Any? = null
    var repeatStartIndex = 0
    var lastIndex = 0
    val action: (Int, Any) -> Unit = { index, value ->
      lastIndex = index
      if (repeatedValue == null) {
        repeatedValue = value
        repeatStartIndex = index
      } else if (repeatedValue != value) {
        val repeatEndIndex = index - 1
        if (repeatStartIndex == repeatEndIndex) {
          echo("  $repeatStartIndex = $repeatedValue")
        } else {
          echo("  $repeatStartIndex..$repeatEndIndex = $repeatedValue")
        }
        repeatedValue = value
        repeatStartIndex = index
      }
    }

    when (record) {
      is BooleanArrayDump -> record.array.forEachIndexed(action)
      is CharArrayDump -> record.array.forEachIndexed(action)
      is FloatArrayDump -> record.array.forEachIndexed(action)
      is DoubleArrayDump -> record.array.forEachIndexed(action)
      is ByteArrayDump -> record.array.forEachIndexed(action)
      is ShortArrayDump -> record.array.forEachIndexed(action)
      is IntArrayDump -> record.array.forEachIndexed(action)
      is LongArrayDump -> record.array.forEachIndexed(action)
    }
    if (repeatedValue != null) {
      if (repeatStartIndex == lastIndex) {
        echo("  $repeatStartIndex = $repeatedValue")
      } else {
        echo("  $repeatStartIndex..$lastIndex = $repeatedValue")
      }
    }
  }

  private fun renderHeapValue(heapValue: HeapValue): String {
    return when (val holder = heapValue.holder) {
      is ReferenceHolder -> {
        when {
          holder.isNull -> "null"
          !heapValue.graph.objectExists(holder.value) -> "@${holder.value} object not found"
          else -> {
            val heapObject = heapValue.asObject!!
            renderHeapObject(heapObject)
          }
        }
      }
      is BooleanHolder -> holder.value.toString()
      is CharHolder -> holder.value.toString()
      is FloatHolder -> holder.value.toString()
      is DoubleHolder -> holder.value.toString()
      is ByteHolder -> holder.value.toString()
      is ShortHolder -> holder.value.toString()
      is IntHolder -> holder.value.toString()
      is LongHolder -> holder.value.toString()
    }
  }

  private fun renderHeapObject(heapObject: HeapObject): String {
    return when (heapObject) {
      is HeapClass -> {
        val instanceCount = when {
          heapObject.isPrimitiveArrayClass -> heapObject.primitiveArrayInstances
          heapObject.isObjectArrayClass -> heapObject.objectArrayInstances
          else -> heapObject.instances
        }.count()
        val plural = if (instanceCount != 1) "s" else ""
        "$CLASS ${heapObject.name}@${heapObject.objectId} (${instanceCount} instance$plural)"
      }
      is HeapInstance -> {
        val asJavaString = heapObject.readAsJavaString()

        val value =
          if (asJavaString != null) {
            " \"${asJavaString}\""
          } else ""

        "$INSTANCE ${heapObject.instanceClassSimpleName}@${heapObject.objectId}$value"
      }
      is HeapObjectArray -> {
        val className = heapObject.arrayClassSimpleName.removeSuffix("[]")
        "$ARRAY $className[${heapObject.readElements().count()}]@${heapObject.objectId}"
      }
      is HeapPrimitiveArray -> {
        val record = heapObject.readRecord()
        val primitiveName = heapObject.primitiveType.name.toLowerCase(Locale.US)
        "$ARRAY $primitiveName[${record.size}]@${heapObject.objectId}"
      }
    }
  }

  private fun analyze(
    heapDumpFile: File,
    graph: HeapGraph,
    showDetails: Boolean = true,
    leakingObjectId: Long? = null
  ) {
    if (leakingObjectId != null) {
      if (!graph.objectExists(leakingObjectId)) {
        echo("@$leakingObjectId not found")
        return
      } else {
        val heapObject = graph.findObjectById(leakingObjectId)
        if (heapObject !is HeapInstance) {
          echo("${renderHeapObject(heapObject)} is not an instance")
          return
        }
      }
    }

    val objectInspectors =
      if (showDetails) AndroidObjectInspectors.appDefaults.toMutableList() else mutableListOf()

    objectInspectors += ObjectInspector {
      it.labels += renderHeapObject(it.heapObject)
    }

    val leakingObjectFinder = if (leakingObjectId == null) {
      FilteringLeakingObjectFinder(
          AndroidObjectInspectors.appLeakingObjectFilters
      )
    } else {
      LeakingObjectFinder {
        setOf(leakingObjectId)
      }
    }

    val listener = OnAnalysisProgressListener { step ->
      SharkLog.d { "Analysis in progress, working on: ${step.name}" }
    }

    val heapAnalyzer = HeapAnalyzer(listener)
    SharkLog.d { "Analyzing heap dump $heapDumpFile" }

    val heapAnalysis = heapAnalyzer.analyze(
        heapDumpFile = heapDumpFile,
        graph = graph,
        leakingObjectFinder = leakingObjectFinder,
        referenceMatchers = AndroidReferenceMatchers.appDefaults,
        computeRetainedHeapSize = true,
        objectInspectors = objectInspectors
    )

    if (leakingObjectId == null || heapAnalysis is HeapAnalysisFailure) {
      echo(heapAnalysis)
    } else {
      val leakTrace = (heapAnalysis as HeapAnalysisSuccess).allLeaks.first()
          .leakTraces.first()
      echo(if (showDetails) leakTrace else leakTrace.toSimplePathString())
    }
  }

}