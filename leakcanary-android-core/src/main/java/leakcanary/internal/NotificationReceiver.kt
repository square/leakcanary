package leakcanary.internal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import leakcanary.internal.NotificationReceiver.Action.CANCEL_NOTIFICATION
import leakcanary.internal.NotificationReceiver.Action.DUMP_HEAP
import shark.SharkLog

internal class NotificationReceiver : BroadcastReceiver() {

  enum class Action {
    DUMP_HEAP,
    CANCEL_NOTIFICATION
  }

  override fun onReceive(
    context: Context,
    intent: Intent
  ) {
    when (intent.action) {
      DUMP_HEAP.name -> {
        InternalLeakCanary.onDumpHeapReceived(forceDump = false)
      }
      CANCEL_NOTIFICATION.name -> {
        // Do nothing, the notification has auto cancel true.
      }
      else -> {
        SharkLog.d { "NotificationReceiver received unknown intent action for $intent" }
      }
    }
  }

  companion object {
    fun pendingIntent(
      context: Context,
      action: Action
    ): PendingIntent {
      val broadcastIntent = Intent(context, NotificationReceiver::class.java)
      broadcastIntent.action = action.name
      return PendingIntent.getBroadcast(context, 0, broadcastIntent, 0)
    }
  }
}