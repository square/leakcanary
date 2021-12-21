package leakcanary.internal

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Notification.Builder
import android.app.NotificationManager
import android.content.Context
import android.content.res.Resources.NotFoundException
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import com.squareup.leakcanary.core.R
import leakcanary.HeapDumper
import leakcanary.HeapDumper.DumpLocation
import leakcanary.HeapDumper.DumpLocation.FileLocation
import leakcanary.HeapDumper.Result.Failure
import leakcanary.internal.NotificationType.LEAKCANARY_LOW
import leakcanary.internal.friendly.mainHandler
import shark.AndroidResourceIdNames
import java.util.concurrent.TimeUnit.SECONDS

/**
 * Wraps [HeapDumper.dumpHeap] and swaps a passed in [DumpLocation.Unspecified] with a
 * [FileLocation] created in a managed heap dump directory, ensure we never store more than
 * [LeakCanary.Config.maxStoredHeapDumps] heap dumps, managing permissions and access to external
 * or app store, and creating a heap dump file that follows the "yyyy-MM-dd_HH-mm-ss_SSS'.hprof'"
 * pattern.
 */
internal fun HeapDumper.withManagedHeapDumpDirectory(): HeapDumper {
  val directoryProvider =
    InternalLeakCanary.createLeakDirectoryProvider(InternalLeakCanary.application)
  return HeapDumper { dumpLocation ->
    if (dumpLocation is FileLocation) {
      Failure(RuntimeException("HeapDumper.DumpLocation is already a FileLocation, pointing to ${dumpLocation.file.absolutePath}"))
    } else {
      val heapDumpFile = directoryProvider.newHeapDumpFile()
      if (heapDumpFile == null) {
        Failure(RuntimeException("Could not create heap dump file"))
      } else {
        dumpHeap(FileLocation(heapDumpFile))
      }
    }
  }
}

internal fun HeapDumper.withNotification(): HeapDumper = HeapDumper { dumpLocation ->
  if (Notifications.canShowNotification) {
    val context = InternalLeakCanary.application
    val notificationManager =
      context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val dumpingHeap = context.getString(R.string.leak_canary_notification_dumping)
    val builder = Builder(context)
      .setContentTitle(dumpingHeap)
    val notification = Notifications.buildNotification(context, builder, LEAKCANARY_LOW)
    notificationManager.notify(R.id.leak_canary_notification_dumping_heap, notification)
    dumpHeap(dumpLocation).also {
      notificationManager.cancel(R.id.leak_canary_notification_dumping_heap)
    }
  } else {
    dumpHeap(dumpLocation)
  }
}

/**
 * Stores in memory the mapping of resource id ints to their corresponding name, so that the heap
 * analysis can label views with their resource id names.
 */
internal fun HeapDumper.withResourceIdNames(): HeapDumper = HeapDumper { dumpLocation ->
  val resources = InternalLeakCanary.application.resources
  AndroidResourceIdNames.saveToMemory(
    getResourceTypeName = { id ->
      try {
        resources.getResourceTypeName(id)
      } catch (e: NotFoundException) {
        null
      }
    },
    getResourceEntryName = { id ->
      try {
        resources.getResourceEntryName(id)
      } catch (e: NotFoundException) {
        null
      }
    })
  dumpHeap(dumpLocation)
}

internal fun HeapDumper.withToast(): HeapDumper = HeapDumper { dumpLocation ->
  val toast = showToastBlocking(InternalLeakCanary.application)
  if (toast == null) {
    Failure(RuntimeException("Too much time waiting for Toast to show"))
  } else {
    dumpHeap(dumpLocation).also {
      mainHandler.post { toast.cancel() }
    }
  }
}

private fun showToastBlocking(context: Context): Toast? {
  val waitingForToast = FutureResult<Toast?>()
  mainHandler.post(Runnable {
    val resumedActivity = InternalLeakCanary.resumedActivity
    if (resumedActivity == null) {
      waitingForToast.set(null)
      return@Runnable
    }
    val toast = Toast(resumedActivity)
    // Resources from application context: https://github.com/square/leakcanary/issues/2023
    val iconSize = context.resources.getDimensionPixelSize(
      R.dimen.leak_canary_toast_icon_size
    )
    toast.setGravity(Gravity.CENTER_VERTICAL, 0, -iconSize)
    toast.duration = Toast.LENGTH_LONG
    // Inflating with application context: https://github.com/square/leakcanary/issues/1385
    val inflater = LayoutInflater.from(context)
    toast.view = inflater.inflate(R.layout.leak_canary_heap_dump_toast, null)
    toast.show()

    val toastIcon = toast.view!!.findViewById<View>(R.id.leak_canary_toast_icon)
    toastIcon.translationY = -iconSize.toFloat()
    toastIcon
      .animate()
      .translationY(0f)
      .setListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
          waitingForToast.set(toast)
        }
      })
  })

  return if (!waitingForToast.wait(5, SECONDS)) {
    null
  } else {
    waitingForToast.get()
  }
}
