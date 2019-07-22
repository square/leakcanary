package leakcanary.internal.activity.screen

import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import com.squareup.leakcanary.core.R
import com.squareup.leakcanary.core.R.plurals
import leakcanary.internal.DisplayLeakAdapter
import leakcanary.internal.activity.db.LeakingInstanceTable
import leakcanary.internal.activity.db.LeakingInstanceTable.InstanceProjection
import leakcanary.internal.activity.db.executeOnDb
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.goTo
import leakcanary.internal.navigation.inflate
import shark.LeakTrace

internal class GroupScreen(private val groupHash: String) : Screen() {
  override fun createView(container: ViewGroup) =
    container.inflate(R.layout.leak_canary_list).apply {
      activity.title = resources.getString(R.string.leak_canary_loading_title)
      executeOnDb {
        val triple = LeakingInstanceTable.retrieveGroup(db, groupHash)
        updateUi {
          if (triple == null) {
            activity.title = resources.getString(R.string.leak_canary_analysis_deleted_title)
          } else {
            // TODO add projects to list.
            val (leakTrace, groupDescription, projections) = triple
            onGroupRetrieved(projections, groupDescription, leakTrace)
          }
        }
      }
    }

  private fun View.onGroupRetrieved(
    projections: List<InstanceProjection>,
    groupDescription: String,
    leakTrace: LeakTrace
  ) {
    activity.title = String.format(
        resources.getQuantityText(
            plurals.leak_canary_group_screen_title, projections.size
        ).toString(), projections.size, groupDescription
    )

    val listView = findViewById<ListView>(R.id.leak_canary_list)

    val adapter = DisplayLeakAdapter(context, leakTrace, groupDescription, projections)
    listView.adapter = adapter

    listView.setOnItemClickListener { _, _, position, _ ->
      val index = position - (adapter.count - projections.size)
      if (index >= 0) {
        goTo(LeakingInstanceScreen(projections[index].id))
      }
    }
  }
}