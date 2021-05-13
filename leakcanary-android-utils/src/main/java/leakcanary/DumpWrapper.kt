package leakcanary

import android.os.Build
import android.os.Debug
import android.os.Looper
import shark.SharkLog
import java.io.IOException
import java.lang.reflect.InvocationTargetException

object DumpWrapper {
  private const val ANDROID_R = Build.VERSION_CODES.Q + 1
  private val VERSION_MATCH = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
      Build.VERSION.SDK_INT <= ANDROID_R

  @Throws(IOException::class)
  fun dumpHprofData(fileName: String) {
    var fastDumpSuccess = false
    if (isSupportFastDump()) {
      try {
        fastDumpSuccess = forkDumpHprofMethod!!.invoke(null, fileName) as Boolean
      } catch (e: InvocationTargetException) {
        SharkLog.d { "Fast dump LoadLibrary failed, error: $e" }
      }
    }
    if (!fastDumpSuccess) {
      SharkLog.d { "Fast dump is not supported, fallback to normal dump." }
      Debug.dumpHprofData(fileName)
    }
  }

  private val forkDumpHprofMethod by lazy {
    try {
      return@lazy Class.forName("com.squareup.leakcanary.FastDump")
        .getDeclaredMethod("forkDumpHprof", String::class.java)
    } catch (e: ClassNotFoundException) {
      SharkLog.d { "Fast dump is not supported: $e" }
    } catch (e: NoSuchMethodException) {
      SharkLog.d { "Fast dump is not supported: $e" }
    }
    return@lazy null
  }

  private fun isSupportFastDump(): Boolean {
    return VERSION_MATCH && (forkDumpHprofMethod != null) &&
        Looper.getMainLooper().thread !== Thread.currentThread()
  }
}