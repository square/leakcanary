package leakcanary.internal.activity.screen

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Environment
import android.os.Environment.DIRECTORY_DOWNLOADS
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.widget.ImageView
import android.widget.Toast
import com.squareup.leakcanary.core.R
import leakcanary.internal.InternalLeakCanary
import leakcanary.internal.LeakCanaryFileProvider
import leakcanary.internal.activity.db.executeOnIo
import leakcanary.internal.navigation.Screen
import leakcanary.internal.navigation.activity
import leakcanary.internal.navigation.inflate
import leakcanary.internal.navigation.onCreateOptionsMenu
import leakcanary.internal.utils.humanReadableByteCount
import shark.SharkLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal class RenderHeapDumpScreen(
  private val heapDumpFile: File
) : Screen() {

  override fun createView(container: ViewGroup) =
    container.inflate(R.layout.leak_canary_heap_render).apply {
      container.activity.title = resources.getString(R.string.leak_canary_loading_title)

      executeOnIo {
        val byteCount = humanReadableByteCount(heapDumpFile.length(), si = true)
        updateUi {
          container.activity.title =
            resources.getString(R.string.leak_canary_heap_dump_screen_title, byteCount)
        }
      }

      val loadingView = findViewById<View>(R.id.leak_canary_loading)
      val imageView = findViewById<ImageView>(R.id.leak_canary_heap_rendering)

      viewTreeObserver.addOnGlobalLayoutListener(object : OnGlobalLayoutListener {
        override fun onGlobalLayout() {

          executeOnIo {
            val bitmap = HeapDumpRenderer.render(
                context, heapDumpFile, measuredWidth, measuredHeight, 0
            )
            updateUi {
              imageView.setImageBitmap(bitmap)
              loadingView.visibility = View.GONE
              imageView.visibility = View.VISIBLE
            }
          }
          if (VERSION.SDK_INT >= VERSION_CODES.JELLY_BEAN) {
            viewTreeObserver.removeOnGlobalLayoutListener(this)
          } else {
            viewTreeObserver.removeGlobalOnLayoutListener(this)
          }
        }
      })

      onCreateOptionsMenu { menu ->
        menu.add(R.string.leak_canary_options_menu_generate_hq_bitmap)
            .setOnMenuItemClickListener {
              val leakDirectoryProvider = InternalLeakCanary.createLeakDirectoryProvider(context)
              if (!leakDirectoryProvider.hasStoragePermission()) {
                Toast.makeText(
                    context,
                    R.string.leak_canary_options_menu_permission_toast,
                    Toast.LENGTH_LONG
                )
                    .show()
                leakDirectoryProvider.requestWritePermissionNotification()
              } else {
                Toast.makeText(
                    context,
                    R.string.leak_canary_generating_hq_bitmap_toast_notice,
                    Toast.LENGTH_LONG
                )
                    .show()
                executeOnIo {
                  val bitmap = HeapDumpRenderer.render(context, heapDumpFile, 2048, 0, 4)
                  @Suppress("DEPRECATION") val storageDir =
                    Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)

                  val imageFile = File(storageDir, "${heapDumpFile.name}.png")
                  val saved = savePng(imageFile, bitmap)
                  if (saved) {
                    SharkLog.d { "Png saved at $imageFile" }
                    imageFile.setReadable(true, false)
                    val imageUri = LeakCanaryFileProvider.getUriForFile(
                        activity,
                        "com.squareup.leakcanary.fileprovider." + activity.packageName,
                        imageFile
                    )

                    updateUi {
                      val intent = Intent(Intent.ACTION_SEND)
                      intent.type = "image/png"
                      intent.putExtra(Intent.EXTRA_STREAM, imageUri)
                      activity.startActivity(
                          Intent.createChooser(
                              intent,
                              resources.getString(
                                  R.string.leak_canary_share_heap_dump_bitmap_screen_title
                              )
                          )
                      )
                    }
                  } else {
                    updateUi {
                      Toast.makeText(
                          context,
                          R.string.leak_canary_generating_hq_bitmap_toast_failure_notice,
                          Toast.LENGTH_LONG
                      )
                          .show()
                    }
                  }
                }
              }
              true
            }
      }
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

