/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.leakcanary.internal

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.text.format.DateUtils.FORMAT_SHOW_DATE
import android.text.format.DateUtils.FORMAT_SHOW_TIME
import android.text.format.Formatter.formatShortFileSize
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider.getUriForFile
import com.squareup.leakcanary.AnalysisResult
import com.squareup.leakcanary.AnalyzedHeap
import com.squareup.leakcanary.BuildConfig.GIT_SHA
import com.squareup.leakcanary.BuildConfig.LIBRARY_VERSION
import com.squareup.leakcanary.CanaryLog
import com.squareup.leakcanary.LeakCanary
import com.squareup.leakcanary.LeakDirectoryProvider
import com.squareup.leakcanary.R
import com.squareup.leakcanary.internal.LeakCanaryInternals.Companion.getLeakDirectoryProvider
import com.squareup.leakcanary.internal.LeakCanaryInternals.Companion.newSingleThreadExecutor
import com.squareup.leakcanary.internal.LeakCanaryInternals.Companion.setEnabledBlocking
import java.io.File
import java.io.FilenameFilter
import java.util.ArrayList
import java.util.Comparator

class DisplayLeakActivity : Activity() {

  // null until it's been first loaded.
  private var leaks: MutableList<AnalyzedHeap>? = null
  private var visibleLeakRefKey: String? = null

  private lateinit var listView: ListView
  private lateinit var failureView: TextView
  private lateinit var actionButton: Button
  private lateinit var shareButton: Button

  internal val visibleLeak: AnalyzedHeap?
    get() {
      if (leaks == null) {
        return null
      }
      for (leak in leaks!!) {
        if (leak.result.referenceKey == visibleLeakRefKey) {
          return leak
        }
      }
      return null
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    if (savedInstanceState != null) {
      visibleLeakRefKey = savedInstanceState.getString("visibleLeakRefKey")
    } else {
      val intent = intent
      if (intent.hasExtra(SHOW_LEAK_EXTRA)) {
        visibleLeakRefKey = intent.getStringExtra(SHOW_LEAK_EXTRA)
      }
    }

    @Suppress("UNCHECKED_CAST")
    leaks = lastNonConfigurationInstance as MutableList<AnalyzedHeap>?

    setContentView(R.layout.leak_canary_display_leak)

    listView = findViewById(R.id.leak_canary_display_leak_list)
    failureView = findViewById(R.id.leak_canary_display_leak_failure)
    actionButton = findViewById(R.id.leak_canary_action)
    shareButton = findViewById(R.id.leak_canary_share)

    updateUi()
  }

  override fun onRetainNonConfigurationInstance(): Any? {
    return leaks
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putString("visibleLeakRefKey", visibleLeakRefKey)
  }

  override fun onResume() {
    super.onResume()
    LoadLeaks.load(this, getLeakDirectoryProvider(this))
  }

  override fun setTheme(resid: Int) {
    // We don't want this to be called with an incompatible theme.
    // This could happen if you implement runtime switching of themes
    // using ActivityLifecycleCallbacks.
    if (resid != R.style.leak_canary_LeakCanary_Base) {
      return
    }
    super.setTheme(resid)
  }

  override fun onDestroy() {
    super.onDestroy()
    LoadLeaks.forgetActivity()
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    val visibleLeak = visibleLeak
    if (visibleLeak != null) {
      menu.add(R.string.leak_canary_share_leak)
          .setOnMenuItemClickListener {
            shareLeak()
            true
          }
      if (visibleLeak.heapDumpFileExists) {
        menu.add(R.string.leak_canary_share_heap_dump)
            .setOnMenuItemClickListener {
              shareHeapDump()
              true
            }
      }
      return true
    }
    return false
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    if (item.itemId == android.R.id.home) {
      visibleLeakRefKey = null
      updateUi()
    }
    return true
  }

  override fun onBackPressed() {
    if (visibleLeakRefKey != null) {
      visibleLeakRefKey = null
      updateUi()
    } else {
      super.onBackPressed()
    }
  }

  private fun shareLeak() {
    val visibleLeak = visibleLeak
    val leakInfo = LeakCanary.leakInfo(this, visibleLeak!!.heapDump, visibleLeak.result, true)
    val intent = Intent(Intent.ACTION_SEND)
    intent.type = "text/plain"
    intent.putExtra(Intent.EXTRA_TEXT, leakInfo)
    startActivity(Intent.createChooser(intent, getString(R.string.leak_canary_share_with)))
  }

  @SuppressLint("SetWorldReadable")
  internal fun shareHeapDump() {
    val visibleLeak = visibleLeak
    val heapDumpFile = visibleLeak!!.heapDump.heapDumpFile
    AsyncTask.SERIAL_EXECUTOR.execute {
      heapDumpFile.setReadable(true, false)
      val heapDumpUri = getUriForFile(
          baseContext,
          "com.squareup.leakcanary.fileprovider." + application.packageName,
          heapDumpFile
      )
      runOnUiThread { startShareIntentChooser(heapDumpUri) }
    }
  }

  private fun startShareIntentChooser(heapDumpUri: Uri) {
    val intent = Intent(Intent.ACTION_SEND)
    intent.type = "application/octet-stream"
    intent.putExtra(Intent.EXTRA_STREAM, heapDumpUri)
    startActivity(Intent.createChooser(intent, getString(R.string.leak_canary_share_with)))
  }

  private fun deleteVisibleLeak() {
    val visibleLeak = visibleLeak
    AsyncTask.SERIAL_EXECUTOR.execute {
      val heapDumpFile = visibleLeak!!.heapDump.heapDumpFile
      val resultFile = visibleLeak.selfFile
      val resultDeleted = resultFile.delete()
      if (!resultDeleted) {
        CanaryLog.d("Could not delete result file %s", resultFile.path)
      }
      val heapDumpDeleted = heapDumpFile.delete()
      if (!heapDumpDeleted) {
        CanaryLog.d("Could not delete heap dump file %s", heapDumpFile.path)
      }
    }
    visibleLeakRefKey = null
    leaks!!.remove(visibleLeak)
    updateUi()
  }

  private fun deleteAllLeaks() {
    val leakDirectoryProvider = getLeakDirectoryProvider(this)
    AsyncTask.SERIAL_EXECUTOR.execute { leakDirectoryProvider.clearLeakDirectory() }
    leaks = mutableListOf()
    updateUi()
  }

  internal fun updateUi() {
    if (leaks == null) {
      title = "Loading leaks..."
      return
    }
    if (leaks!!.isEmpty()) {
      visibleLeakRefKey = null
    }

    val visibleLeak = visibleLeak
    if (visibleLeak == null) {
      visibleLeakRefKey = null
    }

    val listAdapter = listView.adapter
    // Reset to defaults
    listView.visibility = VISIBLE
    failureView.visibility = GONE

    if (visibleLeak != null) {
      val result = visibleLeak.result
      actionButton.visibility = VISIBLE
      actionButton.setText(R.string.leak_canary_delete)
      actionButton.setOnClickListener { deleteVisibleLeak() }
      shareButton.visibility = VISIBLE
      shareButton.text = getString(R.string.leak_canary_stackoverflow_share)
      shareButton.setOnClickListener { shareLeakToStackOverflow() }
      invalidateOptionsMenu()
      setDisplayHomeAsUpEnabled(true)

      if (result.leakFound) {
        val adapter = DisplayLeakAdapter(resources)
        listView.adapter = adapter
        listView.onItemClickListener =
          AdapterView.OnItemClickListener { parent, view, position, id ->
            adapter.toggleRow(
                position
            )
          }
        adapter.update(result.leakTrace!!, result.referenceKey, result.referenceName)
        if (result.retainedHeapSize == AnalysisResult.RETAINED_HEAP_SKIPPED) {
          val className = classSimpleName(result.className!!)
          title = getString(R.string.leak_canary_class_has_leaked, className)
        } else {
          val size = formatShortFileSize(this, result.retainedHeapSize)
          val className = classSimpleName(result.className!!)
          title = getString(R.string.leak_canary_class_has_leaked_retaining, className, size)
        }
      } else {
        listView.visibility = GONE
        failureView.visibility = VISIBLE
        listView.adapter = null

        var failureMessage: String
        if (result.failure != null) {
          setTitle(R.string.leak_canary_analysis_failed)
          failureMessage = (getString(R.string.leak_canary_failure_report)
              + LIBRARY_VERSION
              + " "
              + GIT_SHA
              + "\n"
              + Log.getStackTraceString(result.failure))
        } else {
          val className = classSimpleName(result.className!!)
          title = getString(R.string.leak_canary_class_no_leak, className)
          failureMessage = getString(R.string.leak_canary_no_leak_details)
        }
        val path = visibleLeak.heapDump.heapDumpFile.absolutePath
        failureMessage += "\n\n" + getString(R.string.leak_canary_download_dump, path)
        failureView.text = failureMessage
      }
    } else {
      if (listAdapter is LeakListAdapter) {
        listAdapter.notifyDataSetChanged()
      } else {
        val adapter = LeakListAdapter()
        listView.adapter = adapter
        listView.onItemClickListener =
          AdapterView.OnItemClickListener { parent, view, position, id ->
            visibleLeakRefKey = leaks!![position]
                .result.referenceKey
            updateUi()
          }
        invalidateOptionsMenu()
        title = getString(R.string.leak_canary_leak_list_title, packageName)
        setDisplayHomeAsUpEnabled(false)
        actionButton.setText(R.string.leak_canary_delete_all)
        actionButton.setOnClickListener {
          AlertDialog.Builder(this@DisplayLeakActivity)
              .setIcon(
                  android.R.drawable.ic_dialog_alert
              )
              .setTitle(R.string.leak_canary_delete_all)
              .setMessage(R.string.leak_canary_delete_all_leaks_title)
              .setPositiveButton(android.R.string.ok) { dialog, which -> deleteAllLeaks() }
              .setNegativeButton(android.R.string.cancel, null)
              .show()
        }
      }
      actionButton.visibility = if (leaks!!.size == 0) GONE else VISIBLE
      shareButton.visibility = GONE
    }
  }

  private fun shareLeakToStackOverflow() {
    val visibleLeak = visibleLeak
    val leakInfo = LeakCanary.leakInfo(this, visibleLeak!!.heapDump, visibleLeak.result, false)
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    // AsyncTask was needed here due to setPrimaryClip making a disk write which
    // violated StrictMode if on the main thread
    AsyncTask.execute {
      clipboard.primaryClip = ClipData.newPlainText(
          getString(R.string.leak_canary_leak_clipdata_label),
          "```\n$leakInfo```"
      )
    }
    Toast.makeText(this, R.string.leak_canary_leak_copied, Toast.LENGTH_LONG)
        .show()
    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(STACKOVERFLOW_QUESTION_URL))
    startActivity(browserIntent)
  }

  private fun setDisplayHomeAsUpEnabled(enabled: Boolean) {
    val actionBar = actionBar
        ?: // https://github.com/square/leakcanary/issues/967
        return
    actionBar.setDisplayHomeAsUpEnabled(enabled)
  }

  internal inner class LeakListAdapter : BaseAdapter() {

    override fun getCount(): Int {
      return leaks!!.size
    }

    override fun getItem(position: Int): AnalyzedHeap {
      return leaks!![position]
    }

    override fun getItemId(position: Int): Long {
      return position.toLong()
    }

    override fun getView(
      position: Int,
      convertView: View?,
      parent: ViewGroup
    ): View {
      var convertView = convertView
      if (convertView == null) {
        convertView = LayoutInflater.from(this@DisplayLeakActivity)
            .inflate(R.layout.leak_canary_leak_row, parent, false)
      }
      val titleView = convertView!!.findViewById<TextView>(R.id.leak_canary_row_text)
      val timeView = convertView.findViewById<TextView>(R.id.leak_canary_row_time)
      val leak = getItem(position)

      val index = (leaks!!.size - position).toString() + ". "

      var title: String
      if (leak.result.failure != null) {
        title = (index
            + leak.result.failure!!.javaClass.simpleName
            + " "
            + leak.result.failure!!.message)
      } else {
        val className = classSimpleName(leak.result.className!!)
        if (leak.result.leakFound) {
          title = if (leak.result.retainedHeapSize == AnalysisResult.RETAINED_HEAP_SKIPPED) {
            getString(R.string.leak_canary_class_has_leaked, className)
          } else {
            val size = formatShortFileSize(
                this@DisplayLeakActivity,
                leak.result.retainedHeapSize
            )
            getString(R.string.leak_canary_class_has_leaked_retaining, className, size)
          }
          if (leak.result.excludedLeak) {
            title = getString(R.string.leak_canary_excluded_row, title)
          }
          title = index + title
        } else {
          title = index + getString(R.string.leak_canary_class_no_leak, className)
        }
      }
      titleView.text = title
      val time = DateUtils.formatDateTime(
          this@DisplayLeakActivity, leak.selfLastModified,
          FORMAT_SHOW_TIME or FORMAT_SHOW_DATE
      )
      timeView.text = time
      return convertView
    }
  }

  internal class LoadLeaks(
    var activityOrNull: DisplayLeakActivity?,
    private val leakDirectoryProvider: LeakDirectoryProvider
  ) : Runnable {
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    override fun run() {
      val leaks = ArrayList<AnalyzedHeap>()
      val files = leakDirectoryProvider.listFiles(object : FilenameFilter {
        override fun accept(
          dir: File,
          name: String
        ): Boolean = name.endsWith(".result")

      })
      for (resultFile in files) {
        val leak = AnalyzedHeap.load(resultFile)
        if (leak != null) {
          leaks.add(leak)
        }
      }
      leaks.sortWith(Comparator { lhs, rhs ->
        java.lang.Long.valueOf(rhs.selfFile.lastModified())
            .compareTo(lhs.selfFile.lastModified())
      })
      mainHandler.post {
        inFlight.remove(this@LoadLeaks)
        if (activityOrNull != null) {
          activityOrNull!!.leaks = leaks
          activityOrNull!!.updateUi()
        }
      }
    }

    companion object {

      val inFlight: MutableList<LoadLeaks> = ArrayList()

      private val backgroundExecutor = newSingleThreadExecutor("LoadLeaks")

      fun load(
        activity: DisplayLeakActivity,
        leakDirectoryProvider: LeakDirectoryProvider
      ) {
        val loadLeaks = LoadLeaks(activity, leakDirectoryProvider)
        inFlight.add(loadLeaks)
        backgroundExecutor.execute(loadLeaks)
      }

      fun forgetActivity() {
        for (loadLeaks in inFlight) {
          loadLeaks.activityOrNull = null
        }
        inFlight.clear()
      }
    }
  }

  companion object {

    private const val SHOW_LEAK_EXTRA = "show_latest"
    private const val STACKOVERFLOW_QUESTION_URL =
      "http://stackoverflow.com/questions/ask?guided=false&tags=leakcanary"

    // Public API.
    fun createPendingIntent(context: Context): PendingIntent {
      return createPendingIntent(context, null)
    }

    fun createPendingIntent(
      context: Context,
      referenceKey: String?
    ): PendingIntent {
      setEnabledBlocking(context, DisplayLeakActivity::class.java, true)
      val intent = Intent(context, DisplayLeakActivity::class.java)
      intent.putExtra(SHOW_LEAK_EXTRA, referenceKey)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
      return PendingIntent.getActivity(context, 1, intent, FLAG_UPDATE_CURRENT)
    }

    internal fun classSimpleName(className: String): String {
      val separator = className.lastIndexOf('.')
      return if (separator == -1) {
        className
      } else {
        className.substring(separator + 1)
      }
    }
  }
}
