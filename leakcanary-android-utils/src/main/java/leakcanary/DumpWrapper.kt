package leakcanary

import android.os.Build
import android.os.Debug
import android.util.Log
import leakcanary.internal.checkNotMainThread
import java.io.IOException
import java.lang.Exception

object DumpWrapper {
  private const val TAG = "DumpWrapper"
  private const val ANDROID_R = Build.VERSION_CODES.Q + 1
  private val VERSION_MATCH = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && Build
    .VERSION.SDK_INT <= ANDROID_R

  @Throws(IOException::class)
  fun dumpHprofData(fileName: String) {
    if (isSupportFastDump()) {
      checkNotMainThread()
      try {
        fastDumpClass?.getDeclaredMethod("forkDumpHprof", String::class.java)?.invoke(null, fileName)
      } catch (e: Exception) {
        e.printStackTrace()
      }
    } else {
      Debug.dumpHprofData(fileName)
    }
  }

  private val fastDumpClass by lazy {
    try {
      return@lazy Class.forName("com.squareup.leakcanary.FastDump")
    } catch (e: ClassNotFoundException) {
      Log.d(TAG, "Do not support fast dump!")
      return@lazy null
    }
  }

  private fun isSupportFastDump(): Boolean {
    return VERSION_MATCH && (fastDumpClass != null)
  }
}