package leakcanary.internal.activity

import android.view.ViewGroup
import android.widget.ListView
import com.squareup.leakcanary.R
import com.squareup.leakcanary.R.string
import leakcanary.LeakingInstance
import leakcanary.internal.DisplayLeakAdapter
import leakcanary.internal.lastSegment
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.inflate
import leakcanary.internal.navigation.onCreateOptionsMenu

// TODO Store leaktraces in db and pass an id here (needed for grouping)
internal class LeakingInstanceScreen(private val leakingInstance: LeakingInstance) : Screen() {
  override fun createView(container: ViewGroup) =
    container.inflate(R.layout.leak_canary_list).apply {
      val classSimpleName = leakingInstance.instanceClassName.lastSegment('.')
      activity.title = resources.getString(string.leak_canary_class_has_leaked, classSimpleName)

      val listView = findViewById<ListView>(R.id.leak_canary_list)

      val adapter = DisplayLeakAdapter(resources)
      adapter.update(
          leakingInstance.leakTrace,
          leakingInstance.referenceKey,
          leakingInstance.referenceName
      )
      listView.adapter = adapter

      listView.setOnItemClickListener { _, _, position, _ ->
        adapter.toggleRow(position)
      }

      onCreateOptionsMenu { menu ->
        menu.add(R.string.leak_canary_share_leak)
            .setOnMenuItemClickListener {
              // TODO Add version information
              share(leakingInstance.toString())
              true
            }
        menu.add(R.string.leak_canary_stackoverflow_share)
            .setOnMenuItemClickListener {
              // TODO Add version information
              shareToStackOverflow(leakingInstance.toString())
              true
            }
      }
    }
}