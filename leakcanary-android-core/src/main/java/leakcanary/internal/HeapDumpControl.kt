package leakcanary.internal

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.squareup.leakcanary.core.R
import leakcanary.AppWatcher
import leakcanary.LeakCanary
import leakcanary.internal.HeapDumpControl.ICanHazHeap.Nope
import leakcanary.internal.HeapDumpControl.ICanHazHeap.NotifyingNope
import leakcanary.internal.HeapDumpControl.ICanHazHeap.SilentNope
import leakcanary.internal.HeapDumpControl.ICanHazHeap.Yup
import leakcanary.internal.activity.screen.dumpEnabledInAboutScreen

internal object HeapDumpControl {

  sealed class ICanHazHeap {
    object Yup : ICanHazHeap()
    abstract class Nope(val reason: () -> String) : ICanHazHeap()
    class SilentNope(reason: () -> String) : Nope(reason)

    /**
     * Allows manual dumping via a notification
     */
    class NotifyingNope(reason: () -> String) : Nope(reason)
  }

  @Volatile
  private lateinit var latest: ICanHazHeap

  private val app: Application
    get() = InternalLeakCanary.application

  private val handler = Handler(Looper.getMainLooper())

  private val testClassName by lazy {
    InternalLeakCanary.application.getString(R.string.leak_canary_test_class_name)
  }

  private val hasTestClass by lazy {
    try {
      Class.forName(testClassName)
      true
    } catch (e: Exception) {
      false
    }
  }

  fun updateICanHasHeap() {
    iCanHasHeap()
  }

  fun iCanHasHeap(): ICanHazHeap {
    val config = LeakCanary.config
    val dumpHeap = if (!AppWatcher.isInstalled) {
      // Can't use a resource, we don't have an Application instance when not installed
      SilentNope { "AppWatcher is not installed." }
    } else if (!app.dumpEnabledInAboutScreen) {
      NotifyingNope {
        app.getString(R.string.leak_canary_heap_dump_disabled_from_ui)
      }
    } else if (!config.dumpHeap) {
      SilentNope { app.getString(R.string.leak_canary_heap_dump_disabled_by_app) }
    } else if (hasTestClass) {
      SilentNope {
        app.getString(R.string.leak_canary_heap_dump_disabled_running_tests, testClassName)
      }
    } else if (!config.dumpHeapWhenDebugging && DebuggerControl.isDebuggerAttached) {
      handler.postDelayed({
        updateICanHasHeap()
      }, 20_000L)
      NotifyingNope { app.getString(R.string.leak_canary_notification_retained_debugger_attached) }
    } else Yup

    synchronized(this) {
      if (::latest.isInitialized && dumpHeap is Yup && latest is Nope) {
        InternalLeakCanary.scheduleRetainedObjectCheck()
      }
      latest = dumpHeap
    }

    return dumpHeap
  }
}