package leakcanary.internal.activity

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import com.squareup.leakcanary.R
import leakcanary.internal.activity.HeapAnalysisTable.Summary
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.goTo
import leakcanary.internal.navigation.inflate
import leakcanary.internal.navigation.onCreateOptionsMenu

// TODO Fix
@SuppressLint("SetTextI18n")
internal class HeapAnalysisListScreen : Screen() {
  override fun createView(container: ViewGroup) =
    container.inflate(R.layout.leak_canary_list).apply {
      // TODO String res
      activity.title = "All analyses"

      val listView = findViewById<ListView>(R.id.leak_canary_list)

      onCreateOptionsMenu { menu ->
        menu.add(R.string.leak_canary_delete_all)
            .setOnMenuItemClickListener {
              AlertDialog.Builder(context)
                  .setIcon(android.R.drawable.ic_dialog_alert)
                  .setTitle(R.string.leak_canary_delete_all)
                  .setMessage(R.string.leak_canary_delete_all_leaks_title)
                  .setPositiveButton(android.R.string.ok) { _, _ ->
                    HeapAnalysisTable.deleteAll(db, context)
                    listView.adapter = AnalysisListAdapter(emptyList())
                  }
                  .setNegativeButton(android.R.string.cancel, null)
                  .show()
              true
            }
      }

      val analyses = HeapAnalysisTable.retrieveAll(db)


      listView.setOnItemClickListener { _, _, position, _ ->
        val summary = analyses[position]
        val analysisScreen = if (summary.exceptionSummary != null)
          HeapAnalysisFailureScreen(summary.id)
        else
          HeapAnalysisSuccessScreen(summary.id)
        goTo(analysisScreen)
      }

      listView.adapter = AnalysisListAdapter(analyses)
    }

  class AnalysisListAdapter(private val analyses: List<Summary>) : BaseAdapter() {
    override fun getView(
      position: Int,
      convertView: View?,
      parent: ViewGroup
    ): View {
      val view = convertView ?: parent.inflate(R.layout.leak_canary_leak_row)
      val titleView = view.findViewById<TextView>(R.id.leak_canary_row_text)
      val timeView = view.findViewById<TextView>(R.id.leak_canary_row_time)
      val index = analyses.size - position

      val summary = analyses[position]

      timeView.text = DateUtils.formatDateTime(
          parent.context, summary.createdAtTimeMillis,
          DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE
      )

      titleView.text = "$index. " + (summary.exceptionSummary
      // TODO Handle singular
          ?: "${summary.retainedInstanceCount} retained instances")
      return view
    }

    override fun getItem(position: Int) = null

    override fun getItemId(position: Int) = position.toLong()

    override fun getCount() = analyses.size
  }
}