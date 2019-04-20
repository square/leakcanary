package leakcanary.internal.activity.screen

import android.text.format.DateUtils
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import com.squareup.leakcanary.core.R
import leakcanary.internal.activity.db
import leakcanary.internal.activity.db.LeakingInstanceTable
import leakcanary.internal.activity.ui.SimpleListAdapter
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.goTo
import leakcanary.internal.navigation.inflate
import leakcanary.internal.navigation.onCreateOptionsMenu

internal class GroupListScreen : Screen() {
  override fun createView(container: ViewGroup) =
    container.inflate(R.layout.leak_canary_list).apply {
      val projections = LeakingInstanceTable.retrieveAllGroups(db)

      // TODO String res
      activity.title = "${projections.size} leak groups"

      val listView = findViewById<ListView>(R.id.leak_canary_list)

      listView.adapter =
        SimpleListAdapter(R.layout.leak_canary_leak_row, projections) { view, position ->
          val titleView = view.findViewById<TextView>(R.id.leak_canary_row_text)
          val timeView = view.findViewById<TextView>(R.id.leak_canary_row_time)

          val projection = projections[position]

          // TODO String res
          titleView.text = "(${projection.leakCount}) ${projection.description}"

          // TODO String res
          timeView.text = "Latest: " + DateUtils.formatDateTime(
              view.context, projection.createdAtTimeMillis,
              DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE
          )
        }

      listView.setOnItemClickListener { _, _, position, _ ->
        goTo(GroupScreen(projections[position].hash))
      }

      onCreateOptionsMenu { menu ->
        menu.add("See analysis list")
            .setOnMenuItemClickListener {
              goTo(HeapAnalysisListScreen())
              true
            }
      }

    }
}