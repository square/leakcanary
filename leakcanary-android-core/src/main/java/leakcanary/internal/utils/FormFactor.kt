package leakcanary.internal.utils

import android.content.Context
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.O
import leakcanary.internal.utils.FormFactor.*

enum class FormFactor {
  MOBILE,
  TV,
  INSTANT_APP,
}

val Context.formFactor get() =
  when {
    SDK_INT >= O && packageManager.isInstantApp -> INSTANT_APP
    true -> TV
    else -> MOBILE
  }