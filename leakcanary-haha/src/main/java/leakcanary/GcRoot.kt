package leakcanary

sealed class GcRoot {

  abstract val id: Long

  class Unknown(override val id: Long) : GcRoot()

  /**
   * A global variable in native code.
   */
  class JniGlobal(
    override val id: Long,
    val jniGlobalRefId: Long
  ) : GcRoot()

  /**
   * A local variable in native code.
   */
  class JniLocal(
    override val id: Long,
    val threadSerialNumber: Int,
    /**
     * frame number in stack trace (-1 for empty)
     */
    val frameNumber: Int
  ) : GcRoot()

  /**
   * Java local variable
   */
  class JavaFrame(
    override val id: Long,
    val threadSerialNumber: Int,
    /**
     * frame number in stack trace (-1 for empty)
     */
    val frameNumber: Int
  ) : GcRoot()

  /**
   * Input or output parameters in native code
   */
  class NativeStack(
    override val id: Long,
    /**
     * Thread is sometimes not found, see:
     * https://issuetracker.google.com/issues/122713143
     */
    val threadSerialNumber: Int
  ) : GcRoot()

  /**
   * System class
   */
  class StickyClass(override val id: Long) : GcRoot()

  class ThreadBlock(
    override val id: Long,
    val threadSerialNumber: Int
  ) : GcRoot()

  /**
   * Everything that called the wait() or notify() methods, or
   * that is synchronized.
   */
  class MonitorUsed(override val id: Long) : GcRoot()

  /**
   * Added at https://android.googlesource.com/platform/tools/base/+/c0f0d528c155cab32e372dac77370569a386245c
   */
  class ThreadObject(
    override val id: Long,
    val threadSerialNumber: Int,
    val stackTraceSerialNumber: Int
  ) : GcRoot()

  class ReferenceCleanup(override val id: Long) : GcRoot()

  class VmInternal(override val id: Long) : GcRoot()

  class JniMonitor(
    override val id: Long,
    val stackTraceSerialNumber: Int,
    val stackDepth: Int
  ) : GcRoot()

  class InternedString(override val id: Long) : GcRoot()

  /**
   * An object that is in a queue, waiting for a finalizer to run.
   */
  class Finalizing(override val id: Long) : GcRoot()

  class Debugger(override val id: Long) : GcRoot()

  /**
   * An object that is unreachable from any other root, but not a root itself.
   */
  class Unreachable(override val id: Long) : GcRoot()

}