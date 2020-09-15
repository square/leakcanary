package leakcanary.internal.activity

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.view.View
import android.widget.Toast
import com.squareup.leakcanary.core.BuildConfig
import com.squareup.leakcanary.core.R
import leakcanary.internal.LeakCanaryFileProvider
import leakcanary.internal.navigation.activity
import shark.HeapAnalysisFailure
import java.io.File

internal fun View.share(content: String) {
  val intent = Intent(Intent.ACTION_SEND)
  intent.type = "text/plain"
  intent.putExtra(Intent.EXTRA_TEXT, content)
  activity.startActivity(
      Intent.createChooser(intent, resources.getString(R.string.leak_canary_share_with))
  )
}

@SuppressLint("SetWorldReadable")
internal fun View.shareHeapDump(heapDumpFile: File) {
  AsyncTask.SERIAL_EXECUTOR.execute {
    heapDumpFile.setReadable(true, false)
    val heapDumpUri = LeakCanaryFileProvider.getUriForFile(
        activity,
        "com.squareup.leakcanary.fileprovider." + activity.packageName,
        heapDumpFile
    )
    activity.runOnUiThread { startShareIntentChooser(heapDumpUri) }
  }
}

private fun View.startShareIntentChooser(uri: Uri) {
  val intent = Intent(Intent.ACTION_SEND)
  intent.type = "application/octet-stream"
  intent.putExtra(Intent.EXTRA_STREAM, uri)
  activity.startActivity(
      Intent.createChooser(intent, resources.getString(R.string.leak_canary_share_with))
  )
}

internal fun View.shareToStackOverflow(content: String) {
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  // AsyncTask was needed here due to setPrimaryClip making a disk write which
  // violated StrictMode if on the main thread
  AsyncTask.execute {
    clipboard.setPrimaryClip(
        ClipData.newPlainText(
            context.getString(R.string.leak_canary_leak_clipdata_label),
            "```\n$content```"
        )
    )
  }
  Toast.makeText(context, R.string.leak_canary_leak_copied, Toast.LENGTH_LONG)
      .show()
  val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(STACKOVERFLOW_QUESTION_URL))
  try {
    activity.startActivity(browserIntent)
  } catch (e: ActivityNotFoundException) {
    Toast.makeText(context, R.string.leak_canary_leak_missing_browser_error, Toast.LENGTH_LONG)
            .show();
  }
}

internal fun View.shareToGitHubIssue(failure: HeapAnalysisFailure) {
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
  // AsyncTask was needed here due to setPrimaryClip making a disk write which
  // violated StrictMode if on the main thread
  AsyncTask.execute {
    clipboard.setPrimaryClip(
        ClipData.newPlainText(
            context.getString(R.string.leak_canary_failure_clipdata_label),
            """```
          |${failure.exception}
          |Build.VERSION.SDK_INT: ${Build.VERSION.SDK_INT}
          |Build.MANUFACTURER: ${Build.MANUFACTURER}
          |LeakCanary version: ${BuildConfig.LIBRARY_VERSION}
          |Analysis duration: ${failure.analysisDurationMillis} ms
          |Heap dump file path: ${failure.heapDumpFile.absolutePath}
          |Heap dump timestamp: ${failure.createdAtTimeMillis}
          |```
        """.trimMargin()
        )
    )
  }
  Toast.makeText(context, R.string.leak_canary_failure_copied, Toast.LENGTH_LONG)
      .show()
  val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(NEW_ISSUE_URL))
  try {
    activity.startActivity(browserIntent)
  } catch (e: ActivityNotFoundException) {
    Toast.makeText(context, R.string.leak_canary_leak_missing_browser_error, Toast.LENGTH_LONG)
            .show();
  }
}

private const val STACKOVERFLOW_QUESTION_URL =
  "http://stackoverflow.com/questions/ask?guided=false&tags=leakcanary"

private const val NEW_ISSUE_URL =
  "https://github.com/square/leakcanary/issues/new?labels=type%3A+bug&template=2-bug.md"