package leakcanary.internal.haha

sealed class GcRoot {

  abstract val id: Long

  /** TODO Ignore in shortest path */
  class Unknown(override val id: Long) : GcRoot()

  /**
   * A global variable in native code.
   * TODO Use for shortest path
   */
  class JniGlobal(
    override val id: Long,
    val jniGlobalRefId: Long
  ) : GcRoot()

  /**
   * A local variable in native code.
   * TODO Use for shortest path
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
   * TODO Use for shortest path
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
   * TODO Use for shortest path
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
   * TODO Use for shortest path
   */
  class StickyClass(override val id: Long) : GcRoot()

  /** TODO Use for shortest path */
  class ThreadBlock(
    override val id: Long,
    val threadSerialNumber: Int
  ) : GcRoot()

  /**
   * Everything that called the wait() or notify() methods, or
   * that is synchronized.
   * TODO Use for shortest path
   */
  class MonitorUsed(override val id: Long) : GcRoot()

  /**
   * TODO Ignore in shortest path
   * TODO Why ignored?
   * Added at https://android.googlesource.com/platform/tools/base/+/c0f0d528c155cab32e372dac77370569a386245c
   */
  class ThreadObject(
    override val id: Long,
    val threadSerialNumber: Int,
    val stackTraceSerialNumber: Int
  ) : GcRoot()

  /**
   * TODO Use for shortest path
   * TODO What is this and why do we care about it as a root?
   */
  class ReferenceCleanup(override val id: Long) : GcRoot()

  /** TODO Use for shortest path */
  class VmInternal(override val id: Long) : GcRoot()

  /** TODO Use for shortest path */
  class JniMonitor(
    override val id: Long,
    val stackTraceSerialNumber: Int,
    val stackDepth: Int
  ) : GcRoot()

  /** TODO Ignore in shortest path */
  class InternedString(override val id: Long) : GcRoot()

  /**
   * An object that is in a queue, waiting for a finalizer to run.
   * TODO Ignore in shortest path
   */
  class Finalizing(override val id: Long) : GcRoot()

  /** TODO Ignore in shortest path */
  class Debugger(override val id: Long) : GcRoot()

  /**
   * An object that is unreachable from any other root, but not a root itself.
   * TODO Ignore in shortest path
   */
  class Unreachable(override val id: Long) : GcRoot()

}