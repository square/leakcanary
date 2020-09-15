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
import leakcanary.internal.activity.db.Io
import leakcanary.internal.activity.db.executeOnIo
import leakcanary.internal.activity.ui.SimpleListAdapter
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.inflate
import shark.HeapField
import shark.HeapObject.HeapClass
import shark.HeapObject.HeapInstance
import shark.HeapObject.HeapObjectArray
import shark.HeapObject.HeapPrimitiveArray
import shark.HeapValue
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.DoubleArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.FloatArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ShortArrayDump
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.ByteHolder
import shark.ValueHolder.CharHolder
import shark.ValueHolder.DoubleHolder
import shark.ValueHolder.FloatHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.LongHolder
import shark.ValueHolder.ReferenceHolder
import shark.ValueHolder.ShortHolder
import java.io.Closeable
import java.io.File

internal class HprofExplorerScreen(
  private val heapDumpFile: File
) : Screen() {
  override fun createView(container: ViewGroup) =
    container.inflate(R.layout.leak_canary_hprof_explorer).apply {
      container.activity.title = resources.getString(R.string.leak_canary_loading_title)

      var closeable: Closeable? = null

      addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
        }

        override fun onViewDetachedFromWindow(view: View) {
          Io.execute {
            closeable?.close()
          }
        }
      })

      executeOnIo {
        val graph = heapDumpFile.openHeapGraph()
        closeable = graph
        updateUi {
          container.activity.title =
            resources.getString(R.string.leak_canary_explore_heap_dump)
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
                    val matchingClasses = graph.classes
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
                            R.layout.leak_canary_simple_row, matchingClasses
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
    selectedClass: HeapClass
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
            R.layout.leak_canary_simple_row, staticFields + instances
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
    instance: HeapInstance
  ) {
    executeOnIo {
      val fields = instance.readFields()
          .fieldsAsString()
      val className = instance.instanceClassName
      updateUi {
        titleView.text = "Instance @${instance.objectId} of class $className"
        listView.adapter = SimpleListAdapter(
            R.layout.leak_canary_simple_row, fields
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
    instance: HeapObjectArray
  ) {
    executeOnIo {
      val elements = instance.readElements()
          .mapIndexed { index: Int, element: HeapValue ->
            element to "[$index] = ${element.heapValueAsString()}"
          }
          .toList()
      val arrayClassName = instance.arrayClassName
      val className = arrayClassName.substring(0, arrayClassName.length - 2)
      updateUi {
        titleView.text = "Array $className[${elements.size}]"
        listView.adapter = SimpleListAdapter(
            R.layout.leak_canary_simple_row, elements
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
    instance: HeapPrimitiveArray
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
            R.layout.leak_canary_simple_row, values
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
    heapValue: HeapValue
  ) {
    if (heapValue.isNonNullReference) {
      when (val objectRecord = heapValue.asObject!!) {
        is HeapInstance -> {
          showInstance(titleView, listView, objectRecord)
        }
        is HeapClass -> {
          showClass(titleView, listView, objectRecord)
        }
        is HeapObjectArray -> {
          showObjectArray(titleView, listView, objectRecord)
        }
        is HeapPrimitiveArray -> {
          showPrimitiveArray(titleView, listView, objectRecord)
        }
      }
    }
  }

  private fun Sequence<HeapField>.fieldsAsString(): List<Pair<HeapField, String>> {
    return map { field ->
      field to "${field.declaringClass.simpleName}.${field.name} = ${field.value.heapValueAsString()}"
    }
        .toList()
  }

  private fun HeapValue.heapValueAsString(): String {
    return when (val heapValue = holder) {
      is ReferenceHolder -> {
        if (isNullReference) {
          "null"
        } else {
          when (val objectRecord = asObject!!) {
            is HeapInstance -> {
              if (objectRecord instanceOf "java.lang.String") {
                "${objectRecord.instanceClassName}@${heapValue.value} \"${objectRecord.readAsJavaString()!!}\""
              } else {
                "${objectRecord.instanceClassName}@${heapValue.value}"
              }
            }
            is HeapClass -> {
              "Class ${objectRecord.name}"
            }
            is HeapObjectArray -> {
              objectRecord.arrayClassName
            }
            is HeapPrimitiveArray -> objectRecord.arrayClassName
          }
        }
      }
      is BooleanHolder -> "boolean ${heapValue.value}"
      is CharHolder -> "char ${heapValue.value}"
      is FloatHolder -> "float ${heapValue.value}"
      is DoubleHolder -> "double ${heapValue.value}"
      is ByteHolder -> "byte ${heapValue.value}"
      is ShortHolder -> "short ${heapValue.value}"
      is IntHolder -> "int ${heapValue.value}"
      is LongHolder -> "long ${heapValue.value}"
    }

  }
}
