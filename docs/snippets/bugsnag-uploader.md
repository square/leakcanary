```kotlin
import android.app.Application
import com.bugsnag.android.Bugsnag
import com.bugsnag.android.Configuration
import com.bugsnag.android.ErrorTypes
import com.bugsnag.android.Event
import com.bugsnag.android.ThreadSendPolicy
import shark.HeapAnalysis
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.Leak
import shark.LeakTrace
import shark.LeakTraceReference
import shark.LibraryLeak

class BugsnagLeakUploader(applicationContext: Application) {

  private val bugsnagClient = Bugsnag.start(
    applicationContext,
    Configuration("YOUR_BUGSNAG_API_KEY").apply {
      enabledErrorTypes = ErrorTypes(
        anrs = false,
        ndkCrashes = false,
        unhandledExceptions = false,
        unhandledRejections = false
      )
      sendThreads = ThreadSendPolicy.NEVER
    }
  )

  fun upload(heapAnalysis: HeapAnalysis) {
    when (heapAnalysis) {
      is HeapAnalysisSuccess -> {
        val allLeakTraces = heapAnalysis
          .allLeaks
          .toList()
          .flatMap { leak ->
            leak.leakTraces.map { leakTrace -> leak to leakTrace }
          }
        if (allLeakTraces.isEmpty()) {
          // Track how often we perform a heap analysis that yields no result.
          bugsnagClient.notify(NoLeakException()) { event ->
            event.addHeapAnalysis(heapAnalysis)
            true
          }
        } else {
          allLeakTraces.forEach { (leak, leakTrace) ->
            val message = "Memory leak: ${leak.shortDescription}. See LEAK tab."
            val exception = leakTrace.asFakeException(message)
            bugsnagClient.notify(exception) { event ->
              event.addHeapAnalysis(heapAnalysis)
              event.addLeak(leak)
              event.addLeakTrace(leakTrace)
              event.groupingHash = leak.signature
              true
            }
          }
        }
      }
      is HeapAnalysisFailure -> {
        // Please file any reported failure to
        // https://github.com/square/leakcanary/issues
        bugsnagClient.notify(heapAnalysis.exception)
      }
    }
  }

  class NoLeakException : RuntimeException()

  private fun Event.addHeapAnalysis(heapAnalysis: HeapAnalysisSuccess) {
    addMetadata("Leak", "heapDumpPath", heapAnalysis.heapDumpFile.absolutePath)
    heapAnalysis.metadata.forEach { (key, value) ->
      addMetadata("Leak", key, value)
    }
    addMetadata("Leak", "analysisDurationMs", heapAnalysis.analysisDurationMillis)
  }

  private fun Event.addLeak(leak: Leak) {
    addMetadata("Leak", "libraryLeak", leak is LibraryLeak)
    if (leak is LibraryLeak) {
      addMetadata("Leak", "libraryLeakPattern", leak.pattern.toString())
      addMetadata("Leak", "libraryLeakDescription", leak.description)
    }
  }

  private fun Event.addLeakTrace(leakTrace: LeakTrace) {
    addMetadata("Leak", "retainedHeapByteSize", leakTrace.retainedHeapByteSize)
    addMetadata("Leak", "signature", leakTrace.signature)
    addMetadata("Leak", "leakTrace", leakTrace.toString())
  }

  private fun LeakTrace.asFakeException(message: String): RuntimeException {
    val exception = RuntimeException(message)
    val stackTrace = mutableListOf<StackTraceElement>()
    stackTrace.add(StackTraceElement("GcRoot", gcRootType.name, "GcRoot.kt", 42))
    for (cause in referencePath) {
      stackTrace.add(buildStackTraceElement(cause))
    }
    exception.stackTrace = stackTrace.toTypedArray()
    return exception
  }

  private fun buildStackTraceElement(reference: LeakTraceReference): StackTraceElement {
    val file = reference.owningClassName.substringAfterLast(".") + ".kt"
    return StackTraceElement(reference.owningClassName, reference.referenceDisplayName, file, 42)
  }
}
```
