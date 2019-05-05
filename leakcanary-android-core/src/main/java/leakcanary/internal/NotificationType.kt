package leakcanary.internal

import androidx.annotation.StringRes
import com.squareup.leakcanary.core.R

enum class NotificationType(@StringRes val nameResId: Int, val importance: Int) {
  LEAKCANARY_LOW(
      R.string.leak_canary_notification_channel_low, IMPORTANCE_LOW
  ),
  LEAKCANARY_RESULT(
      R.string.leak_canary_notification_channel_result, IMPORTANCE_DEFAULT
  );
}

private const val IMPORTANCE_LOW = 2
private const val IMPORTANCE_DEFAULT = 3