package leakcanary.internal.activity.screen

import android.app.AlertDialog
import android.text.format.DateUtils
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import com.squareup.leakcanary.core.R
import leakcanary.internal.activity.LeakActivity
import leakcanary.internal.activity.db.LeakingInstanceTable
import leakcanary.internal.activity.db.LeakingInstanceTable.GroupProjection
import leakcanary.internal.activity.db.executeOnDb
import leakcanary.internal.activity.ui.SimpleListAdapter
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.goTo
import leakcanary.internal.navigation.inflate
import leakcanary.internal.navigation.onCreateOptionsMenu

internal class GroupListScreen : Screen() {
  override fun createView(container: ViewGroup) =
    container.inflate(R.layout.leak_canary_list).apply {
      activity.title = resources.getString(R.string.leak_canary_loading_title)
      executeOnDb {
        val projections = LeakingInstanceTable.retrieveAllGroups(db)
        updateUi { onGroupsRetrieved(projections) }
      }

      onCreateOptionsMenu { menu ->
        menu.add(R.string.leak_canary_options_menu_see_analysis_list)
            .setOnMenuItemClickListener {
              goTo(HeapAnalysisListScreen())
              true
            }

        menu.add(R.string.leak_canary_about_title)
            .setOnMenuItemClickListener {
              val dialog = AlertDialog.Builder(context)
                  .setIcon(ContextCompat.getDrawable(context, R.drawable.leak_canary_icon))
                  .setTitle(R.string.leak_canary_about_title)
                  .setMessage(
                      HtmlCompat.fromHtml(
                          resources.getString(R.string.leak_canary_about_message),
                          HtmlCompat.FROM_HTML_MODE_LEGACY
                      )
                  )
                  .setPositiveButton(android.R.string.ok, null)
                  .show()
              val messageView = dialog.findViewById<TextView>(android.R.id.message)
              messageView.movementMethod = LinkMovementMethod.getInstance()
              true
            }

        menu.add(R.string.leak_canary_options_menu_import_hprof_file)
            .setOnMenuItemClickListener {
              activity<LeakActivity>().requestImportHprof()
              true
            }
      }

    }

  private fun View.onGroupsRetrieved(projections: List<GroupProjection>) {
    activity.title =
      resources.getString(R.string.leak_canary_group_list_screen_title, projections.size)

    val listView = findViewById<ListView>(R.id.leak_canary_list)

    listView.adapter =
      SimpleListAdapter(R.layout.leak_canary_leak_row, projections) { view, position ->
        val titleView = view.findViewById<TextView>(R.id.leak_canary_row_text)
        val timeView = view.findViewById<TextView>(R.id.leak_canary_row_time)

        val projection = projections[position]

        titleView.text = "(${projection.leakCount}) ${projection.description}"

        val formattedDate = DateUtils.formatDateTime(
            view.context, projection.createdAtTimeMillis,
            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE
        )
        timeView.text =
          resources.getString(R.string.leak_canary_group_list_time_label, formattedDate)
      }

    listView.setOnItemClickListener { _, _, position, _ ->
      goTo(GroupScreen(projections[position].hash))
    }
  }
}