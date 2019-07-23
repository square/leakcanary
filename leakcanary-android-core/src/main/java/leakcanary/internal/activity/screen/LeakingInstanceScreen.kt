package leakcanary.internal.activity.screen

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import com.squareup.leakcanary.core.R
import leakcanary.internal.DisplayLeakAdapter
import leakcanary.internal.activity.db.LeakingInstanceTable
import leakcanary.internal.activity.db.executeOnDb
import leakcanary.internal.activity.screen.LeakingInstanceScreen.InstanceOrId.Id
import leakcanary.internal.activity.screen.LeakingInstanceScreen.InstanceOrId.Instance
import leakcanary.internal.activity.share
import leakcanary.internal.activity.shareToStackOverflow
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.goTo
import leakcanary.internal.navigation.inflate
import leakcanary.internal.navigation.onCreateOptionsMenu
import shark.Leak
import shark.LibraryLeak
import java.io.Serializable

internal class LeakingInstanceScreen private constructor(
  private val instanceOrId: InstanceOrId
) : Screen() {

  constructor(id: Long) : this(Id(id))

  sealed class InstanceOrId : Serializable {
    class Instance(
      val heapAnalysisId: Long,
      val instance: Leak
    ) : InstanceOrId()

    class Id(val id: Long) : InstanceOrId()
  }

  override fun createView(container: ViewGroup) =
    container.inflate(R.layout.leak_canary_list).apply {

      when (instanceOrId) {
        is Instance -> {
          onInstanceRetrieved(instanceOrId.heapAnalysisId, instanceOrId.instance)
        }
        is Id -> {
          activity.title = resources.getString(R.string.leak_canary_loading_title)
          executeOnDb {
            val pair = LeakingInstanceTable.retrieve(db, instanceOrId.id)
            updateUi {
              if (pair == null) {
                activity.title = resources.getString(R.string.leak_canary_analysis_deleted_title)
              } else {
                val (heapAnalysisId, leakingInstance) = pair
                onInstanceRetrieved(heapAnalysisId, leakingInstance)
              }
            }
          }
        }
      }
    }

  private fun View.onInstanceRetrieved(
    heapAnalysisId: Long,
    leak: Leak
  ) {
    val classSimpleName = leak.classSimpleName
    activity.title =
      resources.getString(R.string.leak_canary_class_has_leaked, classSimpleName)

    val listView = findViewById<ListView>(R.id.leak_canary_list)

    val groupDescription = if (leak is LibraryLeak) "Library Leak: " +leak.pattern.toString() else ""
    val adapter = DisplayLeakAdapter(context, leak.leakTrace, groupDescription)
    listView.adapter = adapter

    listView.setOnItemClickListener { _, _, position, _ ->
      if (adapter.isLearnMoreRow(position)) {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(LEARN_MORE_URL))
        activity.startActivity(browserIntent)
      }
    }

    onCreateOptionsMenu { menu ->
      menu.add(R.string.leak_canary_go_to_heap_analysis)
          .setOnMenuItemClickListener {
            goTo(HeapAnalysisSuccessScreen(heapAnalysisId))
            true
          }
      menu.add(R.string.leak_canary_share_leak)
          .setOnMenuItemClickListener {
            // TODO Add version information
            share(leak.toString())
            true
          }
      menu.add(R.string.leak_canary_stackoverflow_share)
          .setOnMenuItemClickListener {
            // TODO Add version information
            shareToStackOverflow(leak.toString())
            true
          }
    }
  }
  companion object {
    private const val LEARN_MORE_URL =
      "https://square.github.io/leakcanary/fundamentals/"
  }
}