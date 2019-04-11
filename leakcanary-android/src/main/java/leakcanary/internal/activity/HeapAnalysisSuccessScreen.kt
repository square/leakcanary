package leakcanary.internal.activity

import android.annotation.SuppressLint
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import com.squareup.leakcanary.R
import com.squareup.leakcanary.R.string
import leakcanary.HeapAnalysisSuccess
import leakcanary.LeakingInstance
import leakcanary.NoPathToInstance
import leakcanary.WeakReferenceCleared
import leakcanary.WeakReferenceFound
import leakcanary.internal.lastSegment
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.goBack
import leakcanary.internal.navigation.goTo
import leakcanary.internal.navigation.inflate
import leakcanary.internal.navigation.onCreateOptionsMenu

// TODO Remove
@SuppressLint("SetTextI18n")
internal class HeapAnalysisSuccessScreen(
  private val analysisId: Long
) : Screen() {

  override fun createView(container: ViewGroup) =
    container.inflate(R.layout.leak_canary_list).apply {
      val heapAnalysis = HeapAnalysisTable.retrieve<HeapAnalysisSuccess>(db, analysisId)

      if (heapAnalysis == null) {
        // TODO String res
        activity.title = "Analysis deleted"
        return this
      }

      // TODO String res
      activity.title = "Heap Analysis (${heapAnalysis.retainedInstances.size} retained instances)"

      onCreateOptionsMenu { menu ->
        menu.add(R.string.leak_canary_delete)
            .setOnMenuItemClickListener {
              HeapAnalysisTable.delete(db, analysisId, heapAnalysis.heapDump)
              goBack()
              true
            }
        if (heapAnalysis.heapDump.heapDumpFile.exists()) {
          menu.add(R.string.leak_canary_share_heap_dump)
              .setOnMenuItemClickListener {
                shareHeapDump(heapAnalysis.heapDump)
                true
              }
        }
      }

      val listView = findViewById<ListView>(R.id.leak_canary_list)

      val retainedInstances = heapAnalysis.retainedInstances

      listView.setOnItemClickListener { _, _, position, _ ->
        val retainedInstance = retainedInstances[position]
        if (retainedInstance is LeakingInstance) {
          goTo(LeakingInstanceScreen(retainedInstance))
        }
      }

      listView.adapter = object : BaseAdapter() {

        override fun getCount() = retainedInstances.size

        override fun getItem(position: Int) = null

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(
          position: Int,
          convertView: View?,
          parent: ViewGroup
        ): View {

          val view = convertView ?: parent.inflate(R.layout.leak_canary_leak_row)

          val titleView = view.findViewById<TextView>(R.id.leak_canary_row_text)
          val timeView = view.findViewById<TextView>(R.id.leak_canary_row_time)

          val retainedInstance = retainedInstances[position]

          val index = position + 1

          if (retainedInstance is WeakReferenceFound) {
            val classSimpleName = retainedInstance.instanceClassName.lastSegment('.')
            titleView.text = "$index. " + when (retainedInstance) {
              is LeakingInstance -> {
                val title = if (retainedInstance.retainedHeapSize != null) {
                  val size =
                    Formatter.formatShortFileSize(
                        view.context, retainedInstance.retainedHeapSize!!
                    )
                  view.resources.getString(
                      string.leak_canary_class_has_leaked_retaining, classSimpleName, size
                  )
                } else {
                  view.resources.getString(string.leak_canary_class_has_leaked, classSimpleName)
                }
                if (retainedInstance.excludedLeak) {
                  view.resources.getString(R.string.leak_canary_excluded_row, title)
                } else title
              }
              // TODO Distinguish these two no leak cases
              is WeakReferenceCleared -> view.resources.getString(
                  R.string.leak_canary_class_no_leak, classSimpleName
              )
              is NoPathToInstance -> view.resources.getString(
                  R.string.leak_canary_class_no_leak, classSimpleName
              )
            }
            // TODO string res
            timeView.text = "Watched for ${retainedInstance.watchDurationMillis / 1000}s"
          } else {
            // TODO string res
            titleView.text = "$index. Weak reference missing"
            timeView.text = ""
          }
          return view
        }
      }
    }
}