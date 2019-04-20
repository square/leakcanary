package leakcanary.internal.activity.screen

import android.view.ViewGroup
import android.widget.ListView
import com.squareup.leakcanary.core.R
import leakcanary.internal.DisplayLeakAdapter
import leakcanary.internal.activity.db
import leakcanary.internal.activity.db.LeakingInstanceTable
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.goTo
import leakcanary.internal.navigation.inflate

internal class GroupScreen(private val groupHash: String) : Screen() {
  override fun createView(container: ViewGroup) =
    container.inflate(R.layout.leak_canary_list).apply {

      val triple = LeakingInstanceTable.retrieveGroup(db, groupHash)

      if (triple == null) {
        // TODO String res
        activity.title = "Analysis deleted"
        return this
      }

      // TODO add projects to list.
      val (leakTrace, groupDescription, projections) = triple

      // TODO String res
      activity.title = "${projections.size} leaks in $groupDescription"

      val listView = findViewById<ListView>(R.id.leak_canary_list)

      val adapter = DisplayLeakAdapter(resources, leakTrace, projections)
      listView.adapter = adapter

      listView.setOnItemClickListener { _, _, position, _ ->
        val index = position - (adapter.count - projections.size)
        if (index >= 0) {
          goTo(LeakingInstanceScreen(projections[index].id))
        }
      }

    }
}