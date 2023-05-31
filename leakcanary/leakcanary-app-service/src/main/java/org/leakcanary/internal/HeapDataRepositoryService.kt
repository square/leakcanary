package org.leakcanary.internal

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.File
import java.util.EnumSet
import org.leakcanary.internal.ParcelableDominators.Companion.asParcelable
import shark.AndroidReferenceMatchers
import shark.Dominators
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.IgnoredReferenceMatcher
import shark.ObjectDominators

internal class HeapDataRepositoryService : Service() {

  // TODO Stubs can be longer lived than the outer service, handle
  // manually clearing out the stub reference to the service.
  private val binder = object : HeapDataRepository.Stub() {
    override fun sayHi(heapDumpFilePath: String): ParcelableDominators {
      try {
        return File(heapDumpFilePath).openHeapGraph().use { heapGraph ->
          val weakAndFinalizerRefs = EnumSet.of(
            AndroidReferenceMatchers.REFERENCES, AndroidReferenceMatchers.FINALIZER_WATCHDOG_DAEMON
          )
          val ignoredRefs =
            AndroidReferenceMatchers.buildKnownReferences(weakAndFinalizerRefs).map { matcher ->
              matcher as IgnoredReferenceMatcher
            }

          // TODO Move offline part here.
          //  Also trying out without the names to see how big this is.
          //  Didn't work => need to expose an object that can be queried through IPC?
          //  That means keeping this structure in memory probably?
          val result = Dominators(
            ObjectDominators().buildDominatorTree(heapGraph, ignoredRefs)
          ).asParcelable()
          result
        }
      } catch (throwable: Throwable) {
        // TODO cleanup. But we do need the right stacktrace here.
        throwable.printStackTrace()
        throw throwable
      }
    }
  }

  override fun onBind(intent: Intent): IBinder {
    // TODO Check signature of the calling app.
    // TODO Return null if we can't handle the caller's version
    return binder
  }
}
