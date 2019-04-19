package leakcanary.internal.activity.screen

import android.annotation.SuppressLint
import android.text.format.DateUtils
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import com.squareup.leakcanary.R
import leakcanary.HeapAnalysisSuccess
import leakcanary.LeakingInstance
import leakcanary.NoPathToInstance
import leakcanary.WeakReferenceCleared
import leakcanary.WeakReferenceMissing
import leakcanary.internal.activity.db
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.shareHeapDump
import leakcanary.internal.activity.ui.SimpleListAdapter
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
      val pair = HeapAnalysisTable.retrieve<HeapAnalysisSuccess>(db, analysisId)

      if (pair == null) {
        // TODO String res
        activity.title = "Analysis deleted"
        return this
      }

      val (heapAnalysis, leakGroupByHash) = pair

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

      var weakReferenceClearedCount = 0
      var noPathToInstanceCount = 0
      var weakReferenceMissingCount = 0
      retainedInstances.forEach { retainedInstance ->
        // if a leak, add to a map of groupSha -> (description, count, total count, time)
        // => instead of list of shas we can get the list of projections that already exists
        // TODO If the sha doesn't exist in the map then this is a removed group. Can't happen
        // right now as we don't allow removing groups
        when (retainedInstance) {
          is LeakingInstance -> {
            if (leakGroupByHash[retainedInstance.groupHash] == null) {
              TODO("Removing groups is not supported yet, this should not happen yet.")
            }
          }
          is WeakReferenceCleared -> {
            weakReferenceClearedCount++
          }
          is NoPathToInstance -> {
            noPathToInstanceCount++
          }
          is WeakReferenceMissing -> {
            weakReferenceMissingCount++
          }
        }
      }

      val rowList = mutableListOf<Pair<String, String>>()

      val leakGroups = leakGroupByHash.values.toList()

      rowList.addAll(leakGroups.map { projection ->
        val titleText =
          "(${projection.leakCount} / ${projection.totalLeakCount} total) ${projection.description}"
        val timeText = "Latest: " + DateUtils.formatDateTime(
            context, projection.createdAtTimeMillis,
            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE
        )
        titleText to timeText
      })

      if (weakReferenceClearedCount > 0) {
        rowList.add("$weakReferenceClearedCount already cleared weak references" to "")
      }

      if (noPathToInstanceCount > 0) {
        rowList.add("$noPathToInstanceCount non leaking retained instances" to "")
      }

      if (weakReferenceMissingCount > 0) {
        rowList.add("$weakReferenceMissingCount garbage collected weak references" to "")
      }


      listView.adapter =
        SimpleListAdapter(R.layout.leak_canary_leak_row, rowList) { view, position ->
          val titleView = view.findViewById<TextView>(R.id.leak_canary_row_text)
          val timeView = view.findViewById<TextView>(R.id.leak_canary_row_time)

          val (titleText, timeText) = getItem(position)

          titleView.text = titleText
          timeView.text = timeText
        }

      listView.setOnItemClickListener { _, _, position, _ ->
        if (position < leakGroupByHash.size) {
          goTo(GroupScreen(leakGroups[position].hash))
        }
      }
    }
}