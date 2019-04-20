package leakcanary.internal.activity.screen

import android.content.Intent
import android.graphics.Bitmap
import android.os.AsyncTask
import android.os.Environment
import android.os.Environment.DIRECTORY_DOWNLOADS
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.squareup.leakcanary.core.R
import leakcanary.CanaryLog
import leakcanary.internal.LeakCanaryUtils
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.inflate
import leakcanary.internal.navigation.onCreateOptionsMenu
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal class RenderHeapDumpScreen(
  private val heapDumpFile: File
) : Screen() {

  override fun createView(container: ViewGroup) =
    container.inflate(R.layout.leak_canary_heap_render).apply {
      // TODO String res
      container.activity.title =
        "Heap Dump (${humanReadableByteCount(heapDumpFile.length(), false)})"

      val loadingView = findViewById<View>(R.id.leak_canary_loading)
      val imageView = findViewById<ImageView>(R.id.leak_canary_heap_rendering)

      viewTreeObserver.addOnGlobalLayoutListener(object : OnGlobalLayoutListener {
        override fun onGlobalLayout() {
          RenderHeapDumpTask.renderAsync(
              resources,
              heapDumpFile, measuredWidth, measuredHeight, 0
          ) { bitmap ->
            imageView.setImageBitmap(bitmap)
            loadingView.visibility = View.GONE
            imageView.visibility = View.VISIBLE
          }
          viewTreeObserver.removeGlobalOnLayoutListener(this)
        }

      })

      onCreateOptionsMenu { menu ->
        menu.add("Generate HQ Bitmap")
            .setOnMenuItemClickListener {

              val leakDirectoryProvider = LeakCanaryUtils.getLeakDirectoryProvider(activity)
              if (!leakDirectoryProvider.hasStoragePermission()) {
                // TODO String res
                Toast.makeText(
                    context,
                    "Please grant the external storage permission first, see notification.",
                    Toast.LENGTH_LONG
                )
                    .show()
                leakDirectoryProvider.requestWritePermissionNotification()
              } else {
                // TODO String res
                Toast.makeText(
                    context, "Rendering HQ bitmap, this may take a while", Toast.LENGTH_LONG
                )
                    .show()
                RenderHeapDumpTask.renderAsync(
                    resources,
                    heapDumpFile, 2048, 0, 4
                ) { bitmap ->

                  AsyncTask.THREAD_POOL_EXECUTOR.execute {

                    val storageDir =
                      Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)

                    val imageFile = File(storageDir, "${heapDumpFile.name}.png")
                    val saved = savePng(imageFile, bitmap)
                    if (saved) {
                      CanaryLog.d("Png saved at $imageFile")
                      imageFile.setReadable(true, false)
                      val imageUri = FileProvider.getUriForFile(
                          activity,
                          "com.squareup.leakcanary.fileprovider." + activity.packageName,
                          imageFile
                      )
                      activity.runOnUiThread {
                        val intent = Intent(Intent.ACTION_SEND)
                        intent.type = "image/png"
                        intent.putExtra(Intent.EXTRA_STREAM, imageUri)
                        activity.startActivity(
                            Intent.createChooser(intent, "Share heap dump bitmap")
                        )
                      }
                    } else {
                      activity.runOnUiThread {
                        // TODO String res
                        Toast.makeText(
                            context, "Could not save HQ bitmap", Toast.LENGTH_LONG
                        )
                            .show()
                      }

                    }
                  }
                }
              }
              true
            }

      }
    }

  // https://stackoverflow.com/a/3758880
  fun humanReadableByteCount(
    bytes: Long,
    si: Boolean
  ): String {
    val unit = if (si) 1000 else 1024
    if (bytes < unit) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(unit.toDouble())).toInt()
    val pre = (if (si) "kMGTPE" else "KMGTPE")[exp - 1] + if (si) "" else "i"
    return String.format("%.1f %sB", bytes / Math.pow(unit.toDouble(), exp.toDouble()), pre)
  }

  fun savePng(
    imageFile: File,
    source: Bitmap
  ): Boolean {
    var outStream: FileOutputStream? = null
    return try {
      outStream = FileOutputStream(imageFile)
      source.compress(Bitmap.CompressFormat.PNG, 100, outStream)
      true
    } catch (e: IOException) {
      false
    } finally {
      outStream?.close()
    }
  }
}

