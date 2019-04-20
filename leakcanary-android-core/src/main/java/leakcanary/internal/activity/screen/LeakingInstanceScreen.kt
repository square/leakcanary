package leakcanary.internal.activity.screen

import android.view.ViewGroup
import android.widget.ListView
import com.squareup.leakcanary.core.R
import com.squareup.leakcanary.core.R.string
import leakcanary.LeakingInstance
import leakcanary.internal.DisplayLeakAdapter
import leakcanary.internal.activity.db
import leakcanary.internal.activity.screen.LeakingInstanceScreen.InstanceOrId.Id
import leakcanary.internal.activity.screen.LeakingInstanceScreen.InstanceOrId.Instance
import leakcanary.internal.activity.db.LeakingInstanceTable
import leakcanary.internal.activity.share
import leakcanary.internal.activity.shareToStackOverflow
import leakcanary.internal.lastSegment
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.goTo
import leakcanary.internal.navigation.inflate
import leakcanary.internal.navigation.onCreateOptionsMenu
import java.io.Serializable

// TODO Store leaktraces in db and pass an id here (needed for grouping)
internal class LeakingInstanceScreen private constructor(
  private val instanceOrId: InstanceOrId
) : Screen() {

  constructor(id: Long) : this(Id(id))

  constructor(
    heapAnalysisId: Long,
    instance: LeakingInstance
  ) : this(Instance(heapAnalysisId, instance))

  sealed class InstanceOrId : Serializable {
    class Instance(
      val heapAnalysisId: Long,
      val instance: LeakingInstance
    ) : InstanceOrId()

    class Id(val id: Long) : InstanceOrId()
  }

  override fun createView(container: ViewGroup) =
    container.inflate(R.layout.leak_canary_list).apply {

      val pair = when (instanceOrId) {
        is Instance -> instanceOrId.heapAnalysisId to instanceOrId.instance
        is Id -> LeakingInstanceTable.retrieve(db, instanceOrId.id)
      }

      if (pair == null) {
        // TODO String res
        activity.title = "Analysis deleted"
        return this
      }

      val (heapAnalysisId, leakingInstance) = pair

      val classSimpleName = leakingInstance.instanceClassName.lastSegment('.')
      activity.title = resources.getString(string.leak_canary_class_has_leaked, classSimpleName)

      val listView = findViewById<ListView>(R.id.leak_canary_list)

      val adapter =
        DisplayLeakAdapter(resources, leakingInstance.leakTrace, leakingInstance.referenceName)
      listView.adapter = adapter

      listView.setOnItemClickListener { _, _, position, _ ->
        adapter.toggleRow(position)
      }

      onCreateOptionsMenu { menu ->
        // TODO String res
        menu.add("Go to heap analysis")
            .setOnMenuItemClickListener {
              goTo(HeapAnalysisSuccessScreen(heapAnalysisId))
              true
            }
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