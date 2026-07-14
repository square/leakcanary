package shark

import kotlin.LazyThreadSafetyMode.NONE
import shark.HeapObject.HeapInstance

/**
 * A thread that was running when the heap dump was captured, reconstructed from the thread object
 * and stack trace records present in JVM HPROF heap dumps.
 *
 * Android heap dumps do not contain stack trace records, so [HeapGraph.threads] is empty for them
 * and [stackTrace] is empty here.
 */
class HeapThread internal constructor(
  /**
   * The [GcRoot.ThreadObject] this thread was reconstructed from.
   */
  val gcRoot: GcRoot.ThreadObject,
  private val hprofGraph: HprofHeapGraph
) {

  /**
   * The [HeapGraph] this thread belongs to.
   */
  val graph: HeapGraph
    get() = hprofGraph

  /**
   * The [HeapInstance] (a `java.lang.Thread` or subclass) backing this thread. Never null:
   * [HeapGraph.threads] only yields threads whose object exists in the heap dump.
   */
  val threadInstance: HeapInstance by lazy(NONE) {
    hprofGraph.findObjectById(gcRoot.id) as HeapInstance
  }

  /**
   * The thread name, read from the backing `java.lang.Thread` instance. A live JVM thread always
   * has a non-null name, so this falls back to [UNKNOWN_THREAD_NAME] only when the name can't be
   * resolved from the heap object (e.g. a malformed dump or a non-standard Thread subtype).
   */
  val name: String by lazy(NONE) {
    threadInstance["java.lang.Thread", "name"]?.value?.readAsJavaString() ?: UNKNOWN_THREAD_NAME
  }

  /**
   * This thread's stack trace, deepest call last (same order as the HPROF stack trace record).
   * Empty when the heap dump has no stack trace for this thread.
   */
  val stackTrace: List<HeapStackFrame> by lazy(NONE) {
    hprofGraph.readThreadStackTrace(gcRoot)
  }

  /**
   * Converts [stackTrace] into a list of [StackTraceElement], so the stack can be handed to
   * tooling that consumes JVM stack traces (loggers, crash reporters, IDE stack parsers, etc).
   */
  fun toStackTrace(): List<StackTraceElement> {
    return stackTrace.map { it.toStackTraceElement() }
  }

  /**
   * Renders this thread as a JVM-thread-dump-style block: the thread name followed by one
   * `\tat <frame>` line per stack frame.
   */
  fun stackTraceAsString(): String {
    return buildString {
      append('"').append(name).append('"')
      stackTrace.forEach { frame ->
        append("\n\tat ").append(frame)
      }
    }
  }

  override fun toString(): String = stackTraceAsString()

  companion object {
    const val UNKNOWN_THREAD_NAME = "UNKNOWN"
  }
}

/**
 * A single frame of a [HeapThread]'s [HeapThread.stackTrace].
 */
class HeapStackFrame internal constructor(
  /**
   * The name of the class that declared the method for this frame, or null when the frame's class
   * serial number doesn't resolve to a known class (e.g. synthetic or native frames).
   */
  val className: String?,
  /**
   * The name of the method for this frame.
   */
  val methodName: String,
  /**
   * The source file name for this frame, or null when not available.
   */
  val sourceFileName: String?,
  /**
   * The raw HPROF line number. This encodes more than a line number: positive values are an
   * actual line number, while `0`, `-1`, `-2` and `-3` are sentinels. Use [lineNumberKind] to
   * interpret it.
   *
   * See the HPROF binary format manual
   * (https://hg.openjdk.org/jdk6/jdk6/jdk/raw-file/tip/src/share/demo/jvmti/hprof/manual.html) and
   * OpenJDK `heapDumper.cpp`.
   */
  val lineNumber: Int,
  /**
   * The objects held as local variables in this frame, reconstructed from the
   * [GcRoot.JavaFrame] / [GcRoot.JniLocal] roots that point into this frame.
   */
  val locals: List<HeapObject>
) {

  /**
   * Interprets the [lineNumber] sentinel encoding. When this is [LineNumberKind.HAS_LINE_NUMBER],
   * [lineNumber] holds the actual line number; otherwise [lineNumber] is a sentinel.
   */
  val lineNumberKind: LineNumberKind
    get() = when (lineNumber) {
      NO_LINE_INFO -> LineNumberKind.NO_LINE_INFO
      UNKNOWN_LOCATION -> LineNumberKind.UNKNOWN_LOCATION
      COMPILED_METHOD -> LineNumberKind.COMPILED_METHOD
      NATIVE_METHOD -> LineNumberKind.NATIVE_METHOD
      else -> if (lineNumber > 0) {
        LineNumberKind.HAS_LINE_NUMBER
      } else {
        // Any other negative value isn't part of the documented encoding.
        LineNumberKind.UNKNOWN_LOCATION
      }
    }

  /**
   * Converts this frame into a [StackTraceElement]. [className] falls back to an empty string when
   * unresolved, since [StackTraceElement] requires a non-null declaring class.
   */
  fun toStackTraceElement(): StackTraceElement {
    return StackTraceElement(
      className ?: "",
      methodName,
      sourceFileName,
      lineNumber
    )
  }

  override fun toString(): String = toStackTraceElement().toString()

  /**
   * The kind of location information carried by a stack frame's [lineNumber], per the HPROF
   * `HPROF_FRAME` line number encoding.
   */
  enum class LineNumberKind {
    /** [lineNumber] holds an actual, positive line number. */
    HAS_LINE_NUMBER,

    /** No line information is available (raw line number `0`). */
    NO_LINE_INFO,

    /** Unknown location (raw line number `-1`). */
    UNKNOWN_LOCATION,

    /** Compiled method (raw line number `-2`). */
    COMPILED_METHOD,

    /** Native method (raw line number `-3`). */
    NATIVE_METHOD,
  }

  companion object {
    private const val NO_LINE_INFO = 0
    private const val UNKNOWN_LOCATION = -1
    private const val COMPILED_METHOD = -2
    private const val NATIVE_METHOD = -3
  }
}
