package shark.dive

import shark.GcRoot
import shark.GcRoot.Debugger
import shark.GcRoot.Finalizing
import shark.GcRoot.InternedString
import shark.GcRoot.JavaFrame
import shark.GcRoot.JniGlobal
import shark.GcRoot.JniLocal
import shark.GcRoot.JniMonitor
import shark.GcRoot.MonitorUsed
import shark.GcRoot.NativeStack
import shark.GcRoot.ReferenceCleanup
import shark.GcRoot.StickyClass
import shark.GcRoot.ThreadBlock
import shark.GcRoot.ThreadObject
import shark.GcRoot.Unknown
import shark.GcRoot.Unreachable
import shark.GcRoot.VmInternal
import shark.dive.ReachabilityStrength.FINALIZER
import shark.dive.ReachabilityStrength.LOCAL
import shark.dive.ReachabilityStrength.PHANTOM
import shark.dive.ReachabilityStrength.STRONG
import shark.dive.ReachabilityStrength.UNREACHABLE

/**
 * How firmly a GC root holds the object it points at, which is [STRONG] for the roots that are a program
 * keeping something and weaker for the ones that are a runtime busy with it.
 *
 * A weaker root only holds what nothing firmer holds — the same rule as for a weakening reference, see
 * [HeapReachability.isHeldThrough] — so an object a field also points at is drawn under that field rather
 * than flat under the GC roots. Which is the point: a thread being inside a method that has an object in a
 * local variable says nothing about what keeps that object in memory, and there are tens of thousands of
 * those roots in an app's heap dump.
 */
internal fun GcRoot.reachabilityStrength(): ReachabilityStrength = when (this) {
  // A thread doing something with an object right now, which is over when the method returns.
  is JavaFrame, is JniLocal, is NativeStack, is JniMonitor, is MonitorUsed -> LOCAL
  // The queues the runtime works through, which are where an object goes once nothing wants it.
  is Finalizing -> FINALIZER
  is ReferenceCleanup -> PHANTOM
  // Our own way of walking the garbage: an object no GC root reaches. See [HeapDive].
  is Unreachable -> UNREACHABLE
  // A program holding something: a loaded class, a live thread, JNI's global table, the string table.
  is StickyClass, is ThreadObject, is ThreadBlock, is JniGlobal, is InternedString, is VmInternal,
  is Debugger, is Unknown -> STRONG
}
