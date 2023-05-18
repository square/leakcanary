package leakcanary.internal

import android.app.Application
import android.content.pm.ApplicationInfo

internal val Application.isDebuggableBuild: Boolean
  get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0