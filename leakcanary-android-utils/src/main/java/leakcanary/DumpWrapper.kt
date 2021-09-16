package leakcanary

import android.os.Build
import android.os.Debug
import android.os.Looper
import shark.SharkLog
import java.io.IOException
import java.lang.reflect.InvocationTargetException

/**
 * Delegates to
 */
object DumpWrapper {

  private val fastDumpSupported by lazy {
    Build.VERSION.SDK_INT in 21..30 && forkAndDumpHprofDataMethod != null
  }

  private val forkAndDumpHprofDataMethod by lazy {
    return@lazy try {
       Class.forName("leakcanary.FastDump")
        .getDeclaredMethod("forkAndDumpHprofData", String::class.java)
    } catch (expected: ClassNotFoundException) {
      // This is expected (FastDump not on the classpath)
      SharkLog.d { "FastDump not in classpath, falling back to Debug.dumpHprofData()" }
      null
    } catch (e: NoSuchMethodException) {
      SharkLog.d(e) { "Unexpected missing method leakcanary.FastDump#forkAndDumpHprofData" }
      null
    }
  }

  @Throws(IOException::class)
  fun dumpHprofData(fileName: String) {
    var fastDumpSuccess = false
    if (fastDumpSupported && !isOnMainThread()) {
      try {
        fastDumpSuccess = forkAndDumpHprofDataMethod!!.invoke(null, fileName) as Boolean
      } catch (e: InvocationTargetException) {
        SharkLog.d(e) { "Fast dump LoadLibrary failed" }
      }
    }
    if (!fastDumpSuccess) {
      Debug.dumpHprofData(fileName)
    }
  }

  private fun isOnMainThread(): Boolean {
    return Looper.getMainLooper().thread === Thread.currentThread()
  }
}