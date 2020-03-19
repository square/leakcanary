package leakcanary.internal.activity.screen

import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import com.squareup.leakcanary.core.R
import leakcanary.internal.activity.db.HeapAnalysisTable
import leakcanary.internal.activity.db.LeakTable
import leakcanary.internal.activity.db.LeakTable.AllLeaksProjection
import leakcanary.internal.activity.db.executeOnDb
import leakcanary.internal.activity.ui.SimpleListAdapter
import leakcanary.internal.activity.ui.TimeFormatter
import leakcanary.internal.navigation.NavigatingActivity
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.goTo
import leakcanary.internal.navigation.inflate
import leakcanary.internal.navigation.onScreenExiting

internal class LeaksScreen : Screen() {
  override fun createView(container: ViewGroup) =
    container.inflate(R.layout.leak_canary_list).apply {

      val unsubscribeRefresh = HeapAnalysisTable.onUpdate {
        activity<NavigatingActivity>().refreshCurrentScreen()
      }

      onScreenExiting { unsubscribeRefresh() }

      executeOnDb {
        val projections = LeakTable.retrieveAllLeaks(db)
        updateUi { onGroupsRetrieved(projections) }
      }
    }

  private fun View.onGroupsRetrieved(projections: List<AllLeaksProjection>) {
    activity.title = resources.getQuantityString(
        R.plurals.leak_canary_distinct_leaks,
        projections.size, projections.size
    )

    val listView = findViewById<ListView>(R.id.leak_canary_list)

    listView.adapter =
      SimpleListAdapter(R.layout.leak_canary_leak_row, projections) { view, position ->
        val countView = view.findViewById<TextView>(R.id.leak_canary_count_text)
        val descriptionView = view.findViewById<TextView>(R.id.leak_canary_leak_text)
        val timeView = view.findViewById<TextView>(R.id.leak_canary_time_text)
        val newChipView = view.findViewById<TextView>(R.id.leak_canary_chip_new)
        val libraryLeakChipView = view.findViewById<TextView>(R.id.leak_canary_chip_library_leak)

        val projection = projections[position]
        countView.isEnabled = projection.isNew

        newChipView.visibility = if (projection.isNew) VISIBLE else GONE
        libraryLeakChipView.visibility = if (projection.isLibraryLeak) VISIBLE else GONE

        countView.text = projection.leakTraceCount.toString()
        descriptionView.text = projection.shortDescription

        val formattedDate =
          TimeFormatter.formatTimestamp(view.context, projection.createdAtTimeMillis)
        timeView.text =
          resources.getString(R.string.leak_canary_group_list_time_label, formattedDate)
      }

    listView.setOnItemClickListener { _, _, position, _ ->
      goTo(LeakScreen(projections[position].signature))
    }
  }
}