package leakcanary.internal.activity.screen

import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import com.squareup.leakcanary.core.R
import leakcanary.internal.activity.db.LeakTable
import leakcanary.internal.activity.db.LeakTable.GroupProjection
import leakcanary.internal.activity.db.executeOnDb
import leakcanary.internal.activity.ui.SimpleListAdapter
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.goTo
import leakcanary.internal.navigation.inflate

internal class LeaksScreen : Screen() {
  override fun createView(container: ViewGroup) =
    container.inflate(R.layout.leak_canary_list).apply {
      executeOnDb {
        val projections = LeakTable.retrieveAllLeaks(db)
        updateUi { onGroupsRetrieved(projections) }
      }
    }

  private fun View.onGroupsRetrieved(projections: List<GroupProjection>) {
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

        val projection = projections[position]

        countView.text = projection.leakCount.toString()
        descriptionView.text = projection.description

        val formattedDate = DateUtils.formatDateTime(
            view.context, projection.createdAtTimeMillis,
            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE
        )
        timeView.text =
          resources.getString(R.string.leak_canary_group_list_time_label, formattedDate)
      }

    listView.setOnItemClickListener { _, _, position, _ ->
      goTo(LeakScreen(projections[position].hash))
    }
  }
}