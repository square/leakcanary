package shark

/**
 * A GcRoot as identified by [HprofRecord.HeapDumpRecord.GcRootRecord] in the heap dump.
 */
sealed class GcRoot {

  /**
   * The object id of the object that this gc root references.
   */
  abstract val id: Long

  /**
   * An unknown gc root.
   */
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
    /** Corresponds to [ThreadObject.threadSerialNumber] */
    val threadSerialNumber: Int,
    /**
     * frame number in stack trace (-1 for empty)
     */
    val frameNumber: Int
  ) : GcRoot()

  /**
   * A java local variable
   */
  class JavaFrame(
    override val id: Long,
    /** Corresponds to [ThreadObject.threadSerialNumber] */
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
     * Corresponds to [ThreadObject.threadSerialNumber]
     * Note: the corresponding thread is sometimes not found, see:
     * https://issuetracker.google.com/issues/122713143
     */
    val threadSerialNumber: Int
  ) : GcRoot()

  /**
   * A system class
   */
  class StickyClass(override val id: Long) : GcRoot()

  class ThreadBlock(
    override val id: Long,
    /** Corresponds to [ThreadObject.threadSerialNumber] */
    val threadSerialNumber: Int
  ) : GcRoot()

  /**
   * Everything that called the wait() or notify() methods, or
   * that is synchronized.
   */
  class MonitorUsed(override val id: Long) : GcRoot()

  /**
   * A thread.
   *
   * Added at https://android.googlesource.com/platform/tools/base/+/c0f0d528c155cab32e372dac77370569a386245c
   */
  class ThreadObject(
    override val id: Long,
    val threadSerialNumber: Int,
    val stackTraceSerialNumber: Int
  ) : GcRoot()

  /**
   * It's unclear what this is, documentation welcome.
   */
  class ReferenceCleanup(override val id: Long) : GcRoot()

  /**
   * It's unclear what this is, documentation welcome.
   */
  class VmInternal(override val id: Long) : GcRoot()

  /**
   * It's unclear what this is, documentation welcome.
   */
  class JniMonitor(
    override val id: Long,
    val stackTraceSerialNumber: Int,
    val stackDepth: Int
  ) : GcRoot()

  /**
   * An interned string, see [java.lang.String.intern].
   */
  class InternedString(override val id: Long) : GcRoot()

  /**
   * An object that is in a queue, waiting for a finalizer to run.
   */
  class Finalizing(override val id: Long) : GcRoot()

  /**
   * An object held by a connected debugger
   */
  class Debugger(override val id: Long) : GcRoot()

  /**
   * An object that is unreachable from any other root, but not a root itself.
   */
  class Unreachable(override val id: Long) : GcRoot()
}