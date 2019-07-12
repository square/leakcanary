package leakcanary.internal.activity.screen

import android.app.AlertDialog
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.squareup.leakcanary.core.R
import leakcanary.GraphField
import leakcanary.GraphHeapValue
import leakcanary.GraphObjectRecord.GraphClassRecord
import leakcanary.GraphObjectRecord.GraphInstanceRecord
import leakcanary.GraphObjectRecord.GraphObjectArrayRecord
import leakcanary.GraphObjectRecord.GraphPrimitiveArrayRecord
import leakcanary.HeapValue.BooleanValue
import leakcanary.HeapValue.ByteValue
import leakcanary.HeapValue.CharValue
import leakcanary.HeapValue.DoubleValue
import leakcanary.HeapValue.FloatValue
import leakcanary.HeapValue.IntValue
import leakcanary.HeapValue.LongValue
import leakcanary.HeapValue.ObjectReference
import leakcanary.HeapValue.ShortValue
import leakcanary.HprofGraph
import leakcanary.PrimitiveType.BOOLEAN
import leakcanary.PrimitiveType.BYTE
import leakcanary.PrimitiveType.CHAR
import leakcanary.PrimitiveType.DOUBLE
import leakcanary.PrimitiveType.FLOAT
import leakcanary.PrimitiveType.INT
import leakcanary.PrimitiveType.LONG
import leakcanary.PrimitiveType.SHORT
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.DoubleArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.FloatArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ShortArrayDump
import leakcanary.internal.activity.db.Io
import leakcanary.internal.activity.db.executeOnIo
import leakcanary.internal.activity.ui.SimpleListAdapter
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.inflate
import java.io.Closeable
import java.io.File

internal class HprofExplorerScreen(
  private val heapDumpFile: File
) : Screen() {
  override fun createView(container: ViewGroup) =
    container.inflate(R.layout.leak_canary_hprof_explorer).apply {
      container.activity.title = resources.getString(R.string.leak_canary_loading_title)

      lateinit var closeable: Closeable

      addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
        }

        override fun onViewDetachedFromWindow(view: View) {
          Io.execute {
            closeable.close()
          }
        }
      })

      executeOnIo {
        val pair = HprofGraph.readHprof(heapDumpFile)
        val graph = pair.first
        closeable = pair.second
        updateUi {
          container.activity.title =
            resources.getString(R.string.leak_canary_options_menu_explore_heap_dump)
          val titleView = findViewById<TextView>(R.id.leak_canary_explorer_title)
          val searchView = findViewById<View>(R.id.leak_canary_search_button)
          val listView = findViewById<ListView>(R.id.leak_canary_explorer_list)
          titleView.visibility = VISIBLE
          searchView.visibility = VISIBLE
          listView.visibility = VISIBLE
          searchView.setOnClickListener {
            val input = EditText(context)
            AlertDialog.Builder(context)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle("Type a fully qualified class name")
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                  executeOnIo {
                    val partialClassName = input.text.toString()
                    val matchingClasses = graph.classSequence()
                        .filter { partialClassName in it.name }
                        .toList()

                    if (matchingClasses.isEmpty()) {
                      updateUi {
                        Toast.makeText(
                            context, "No class matching [$partialClassName]", Toast.LENGTH_LONG
                        )
                            .show()
                      }
                    } else {
                      updateUi {
                        titleView.text =
                          "${matchingClasses.size} classes matching [$partialClassName]"
                        listView.adapter = SimpleListAdapter(
                            R.layout.leak_canary_leak_row, matchingClasses
                        ) { view, position ->
                          val itemTitleView = view.findViewById<TextView>(R.id.leak_canary_row_text)
                          itemTitleView.text = matchingClasses[position].name
                        }
                        listView.setOnItemClickListener { _, _, position, _ ->
                          val selectedClass = matchingClasses[position]
                          showClass(titleView, listView, selectedClass)
                        }
                      }
                    }
                  }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
          }
        }
      }
    }

  private fun View.showClass(
    titleView: TextView,
    listView: ListView,
    selectedClass: GraphClassRecord
  ) {
    executeOnIo {
      val className = selectedClass.name
      val instances = selectedClass.directInstances.toList()
      val staticFields = selectedClass.readStaticFields()
          .fieldsAsString()
      updateUi {
        titleView.text =
          "Class $className (${instances.size} instances)"
        listView.adapter = SimpleListAdapter(
            R.layout.leak_canary_leak_row, staticFields + instances
        ) { view, position ->
          val itemTitleView =
            view.findViewById<TextView>(R.id.leak_canary_row_text)
          if (position < staticFields.size) {
            itemTitleView.text = staticFields[position].second
          } else {
            itemTitleView.text = "@${instances[position - staticFields.size].objectId}"
          }
        }
        listView.setOnItemClickListener { _, _, position, _ ->
          if (position < staticFields.size) {
            val staticField = staticFields[position].first
            onHeapValueClicked(titleView, listView, staticField.value)
          } else {
            val instance = instances[position - staticFields.size]
            showInstance(titleView, listView, instance)
          }
        }
      }
    }
  }

  private fun View.showInstance(
    titleView: TextView,
    listView: ListView,
    instance: GraphInstanceRecord
  ) {
    executeOnIo {
      val fields = instance.readFields()
          .fieldsAsString()
      val className = instance.className
      updateUi {
        titleView.text = "Instance @${instance.objectId} of class $className"
        listView.adapter = SimpleListAdapter(
            R.layout.leak_canary_leak_row, fields
        ) { view, position ->
          val itemTitleView =
            view.findViewById<TextView>(R.id.leak_canary_row_text)
          itemTitleView.text = fields[position].second
        }
        listView.setOnItemClickListener { _, _, position, _ ->
          val field = fields[position].first
          onHeapValueClicked(titleView, listView, field.value)
        }
      }
    }
  }

  private fun View.showObjectArray(
    titleView: TextView,
    listView: ListView,
    instance: GraphObjectArrayRecord
  ) {
    executeOnIo {
      val elements = instance.readElements()
          .mapIndexed { index: Int, element: GraphHeapValue ->
            element to "[$index] = ${element.heapValueAsString()}"
          }
          .toList()
      val arrayClassName = instance.arrayClassName
      val className = arrayClassName.substring(0, arrayClassName.length - 2)
      updateUi {
        titleView.text = "Array $className[${elements.size}]"
        listView.adapter = SimpleListAdapter(
            R.layout.leak_canary_leak_row, elements
        ) { view, position ->
          val itemTitleView =
            view.findViewById<TextView>(R.id.leak_canary_row_text)
          itemTitleView.text = elements[position].second
        }
        listView.setOnItemClickListener { _, _, position, _ ->
          val element = elements[position].first
          onHeapValueClicked(titleView, listView, element)
        }
      }
    }
  }

  private fun View.showPrimitiveArray(
    titleView: TextView,
    listView: ListView,
    instance: GraphPrimitiveArrayRecord
  ) {
    executeOnIo {
      val (type, values) = when (val record = instance.readRecord()) {
        is BooleanArrayDump -> "boolean" to record.array.map { it.toString() }
        is CharArrayDump -> "char" to record.array.map { "'$it'" }
        is FloatArrayDump -> "float" to record.array.map { it.toString() }
        is DoubleArrayDump -> "double" to record.array.map { it.toString() }
        is ByteArrayDump -> "byte" to record.array.map { it.toString() }
        is ShortArrayDump -> "short" to record.array.map { it.toString() }
        is IntArrayDump -> "int" to record.array.map { it.toString() }
        is LongArrayDump -> "long" to record.array.map { it.toString() }
      }
      updateUi {
        titleView.text = "Array $type[${values.size}]"
        listView.adapter = SimpleListAdapter(
            R.layout.leak_canary_leak_row, values
        ) { view, position ->
          val itemTitleView =
            view.findViewById<TextView>(R.id.leak_canary_row_text)
          itemTitleView.text = "$type ${values[position]}"
        }
        listView.setOnItemClickListener { _, _, _, _ ->
        }
      }
    }
  }

  private fun View.onHeapValueClicked(
    titleView: TextView,
    listView: ListView,
    graphHeapValue: GraphHeapValue
  ) {
    if (graphHeapValue.isNonNullReference) {
      when (val objectRecord = graphHeapValue.asObject!!) {
        is GraphInstanceRecord -> {
          showInstance(titleView, listView, objectRecord)
        }
        is GraphClassRecord -> {
          showClass(titleView, listView, objectRecord)
        }
        is GraphObjectArrayRecord -> {
          showObjectArray(titleView, listView, objectRecord)
        }
        is GraphPrimitiveArrayRecord -> {
          showPrimitiveArray(titleView, listView, objectRecord)
        }
      }
    }
  }

  private fun Sequence<GraphField>.fieldsAsString(): List<Pair<GraphField, String>> {
    return map { field ->
      field to "${field.classRecord.simpleName}.${field.name} = ${field.value.heapValueAsString()}"
    }
        .toList()
  }

  private fun GraphHeapValue.heapValueAsString(): String {
    return when (val heapValue = actual) {
      is ObjectReference -> {
        if (isNullReference) {
          "null"
        } else {
          when (val objectRecord = asObject!!) {
            is GraphInstanceRecord -> {
              if (objectRecord instanceOf "java.lang.String") {
                "${objectRecord.className}@${heapValue.value} \"${objectRecord.readAsJavaString()!!}\""
              } else {
                "${objectRecord.className}@${heapValue.value}"
              }
            }
            is GraphClassRecord -> {
              "Class ${objectRecord.name}"
            }
            is GraphObjectArrayRecord -> {
              objectRecord.arrayClassName
            }
            is GraphPrimitiveArrayRecord -> objectRecord.arrayClassName
          }
        }
      }
      is BooleanValue -> "boolean ${heapValue.value}"
      is CharValue -> "char ${heapValue.value}"
      is FloatValue -> "float ${heapValue.value}"
      is DoubleValue -> "double ${heapValue.value}"
      is ByteValue -> "byte ${heapValue.value}"
      is ShortValue -> "short ${heapValue.value}"
      is IntValue -> "int ${heapValue.value}"
      is LongValue -> "long ${heapValue.value}"
    }

  }
}
