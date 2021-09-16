package leakcanary

import androidx.annotation.Keep
import shark.SharkLog

@Keep
object FastDump {
  init {
    try {
      System.loadLibrary("fast-dump")
    } catch (e: UnsatisfiedLinkError) {
      SharkLog.d(e) { "Fast dump LoadLibrary failed" }
    }
  }

  /**
   * A fast native alternative to [android.os.Debug.dumpHprofData].
   *
   * The native code calls native non public Android framework APIs, by first looking up
   * the symbol table.
   *
   * From Lollipop to Q, this forks the current process. The parent process can then immediately
   * keep running, the child process starts dumping its own heap copy, and the calling thread in the
   * parent process blocks waiting for the child process to terminate.
   *
   * On Android R, the approach is similar but dumping is performed by calling a native function.
   *
   * On releases newer than Android R, this is a no op and returns false.
   *
   * Returns true if the heap was successfully dumped, false otherwise.
   */
  @JvmStatic
  external fun forkAndDumpHprofData(path: String) : Boolean
}