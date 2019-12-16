package leakcanary.internal.activity.ui

import android.content.Context
import android.text.format.DateUtils

internal object TimeFormatter {

  private const val MINUTE_MILLIS = 60 * 1000
  private const val TWO_MINUTES_MILLIS = 2 * MINUTE_MILLIS
  private const val FIFTY_MINUTES_MILLIS = 50 * MINUTE_MILLIS
  private const val NINETY_MINUTES_MILLIS = 90 * MINUTE_MILLIS
  private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
  private const val DAY_MILLIS = 24 * HOUR_MILLIS
  private const val TWO_DAYS_MILLIS = 48 * HOUR_MILLIS

  fun formatTimestamp(
    context: Context,
    timestampMillis: Long
  ): String {
    // Based on https://stackoverflow.com/a/13018647
    val nowMillis = System.currentTimeMillis()
    return when (val diff = nowMillis - timestampMillis) {
      in 0..MINUTE_MILLIS -> {
        "just now"
      }
      in MINUTE_MILLIS..TWO_MINUTES_MILLIS -> {
        "a minute ago"
      }
      in TWO_MINUTES_MILLIS..FIFTY_MINUTES_MILLIS -> {
        "${diff / MINUTE_MILLIS} minutes ago"
      }
      in FIFTY_MINUTES_MILLIS..NINETY_MINUTES_MILLIS -> {
        "an hour ago"
      }
      in NINETY_MINUTES_MILLIS..DAY_MILLIS -> {
        "${diff / HOUR_MILLIS} hours ago"
      }
      in DAY_MILLIS..TWO_DAYS_MILLIS -> {
        "yesterday"
      }
      else -> {
        DateUtils.formatDateTime(
            context, timestampMillis,
            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE
        )
      }
    }
  }

}