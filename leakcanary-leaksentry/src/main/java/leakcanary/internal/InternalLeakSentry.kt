package leakcanary.internal

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import leakcanary.AbstractLeakSentryReceiver
import leakcanary.Clock
import leakcanary.LeakSentry
import leakcanary.RefWatcher
import java.util.concurrent.Executor

internal object InternalLeakSentry {

  lateinit var application: Application

  private val clock = object : Clock {
    override fun uptimeMillis(): Long {
      return SystemClock.uptimeMillis()
    }
  }

  private val mainHandler = Handler(Looper.getMainLooper())

  private val checkRetainedExecutor = Executor {
    mainHandler.postDelayed(it, LeakSentry.config.watchDurationMillis)
  }
  val refWatcher = RefWatcher(
      clock,
      checkRetainedExecutor
  ) {
    AbstractLeakSentryReceiver.sendReferenceRetained()
  }

  fun install(application: Application) {
    checkMainThread()
    if (this::application.isInitialized) {
      return
    }
    InternalLeakSentry.application = application

    val configProvider = { LeakSentry.config }
    ActivityDestroyWatcher.install(
        application, refWatcher, configProvider
    )
    FragmentDestroyWatcher.install(
        application, refWatcher, configProvider
    )
    AbstractLeakSentryReceiver.sendLeakSentryInstalled()
  }

  private fun checkMainThread() {
    if (Looper.getMainLooper().thread !== Thread.currentThread()) {
      throw UnsupportedOperationException(
          "Should be called from the main thread, not ${Thread.currentThread()}"
      )
    }
  }
}