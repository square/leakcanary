package leakcanary.internal.activity

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.AsyncTask
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import com.squareup.leakcanary.core.R
import leakcanary.internal.navigation.activity
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
    val heapDumpUri = FileProvider.getUriForFile(
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
    clipboard.primaryClip = ClipData.newPlainText(
        context.getString(R.string.leak_canary_leak_clipdata_label),
        "```\n$content```"
    )
  }
  Toast.makeText(context, R.string.leak_canary_leak_copied, Toast.LENGTH_LONG)
      .show()
  val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(STACKOVERFLOW_QUESTION_URL))
  activity.startActivity(browserIntent)
}

private const val STACKOVERFLOW_QUESTION_URL =
  "http://stackoverflow.com/questions/ask?guided=false&tags=leakcanary"
