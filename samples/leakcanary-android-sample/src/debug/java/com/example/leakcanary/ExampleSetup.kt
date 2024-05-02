package com.example.leakcanary

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import leakcanary.ActivityWatcher
import leakcanary.DefaultDelayDeletableObjectReporter
import leakcanary.DelayedDeletableObjectReporter
import leakcanary.FragmentAndViewModelWatcher
import leakcanary.GcTrigger
import leakcanary.LogcatSharkLog
import leakcanary.OnObjectRetainedListener
import leakcanary.ReferenceQueueRetainedObjectTracker
import leakcanary.RetainedObjectTracker
import leakcanary.RootViewWatcher
import leakcanary.ServiceWatcher
import leakcanary.inProcess

class ExampleSetup {

  /**
   * This is a playground for experimenting with what public APIs for assembling the low
   * level blocks should look like.
   */
  fun setup(application: Application) {
    checkMainThread()

    if (application.isDebuggableBuild) {
      LogcatSharkLog.install()
    }

    val objectRetainedListeners = mutableListOf<OnObjectRetainedListener>()

    val reachabilityWatcher = setupReachabilityWatcher(objectRetainedListeners, application)

    application.checkRunningInDebuggableBuild()

    val gcTrigger = GcTrigger.inProcess()

    val handlerThread = HandlerThread(LEAK_CANARY_THREAD_NAME)
    handlerThread.start()
    val backgroundHandler = Handler(handlerThread.looper)

    // TODO Wire up LeakCanary now.
    // TODO add to objectRetainedListeners
  }

  private fun setupReachabilityWatcher(
    objectRetainedListeners: List<OnObjectRetainedListener>,
    application: Application
  ): RetainedObjectTracker {
    val reachabilityWatcher = ReferenceQueueRetainedObjectTracker(
      clock = { SystemClock.uptimeMillis().milliseconds },
      onObjectRetainedListener = {
        objectRetainedListeners.forEach { it.onObjectRetained() }
      }
    )

    val deletableObjectReporter = DefaultDelayDeletableObjectReporter(
      defaultDelay = 5.seconds,
      delayedReporter = DelayedDeletableObjectReporter(
        deletableObjectReporter = reachabilityWatcher,
        delayedExecutor = { delayUptime, runnable ->
          mainHandler.postDelayed(runnable, delayUptime.inWholeMilliseconds)
        }
      ))

    val watchersToInstall = listOf(
      ActivityWatcher(application, deletableObjectReporter),
      // TODO Should configure which fragment to expect.
      // Or maybe just delete support for support lib.
      FragmentAndViewModelWatcher(application, deletableObjectReporter),
      // TODO should configure this
      RootViewWatcher(deletableObjectReporter),
      ServiceWatcher(deletableObjectReporter)
    )

    watchersToInstall.forEach {
      it.install()
    }
    return reachabilityWatcher
  }

  companion object {

    // TODO Should this be a public utility?
    val mainHandler = Handler(Looper.getMainLooper())

    // TODO Should this be a public utility?
    // TODO Make this lazy?
    val Application.isDebuggableBuild: Boolean
      get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    // TODO Should this be a public utility?
    val isMainThread: Boolean get() = Looper.getMainLooper().thread === Thread.currentThread()

    // TODO Should this be a public utility?
    fun checkMainThread() {
      check(isMainThread) {
        "Should be called from the main thread, not ${Thread.currentThread()}"
      }
    }

    private fun Application.checkRunningInDebuggableBuild() {
      if (isDebuggableBuild) {
        return
      }

      throw Error(
        """
        LeakCanary in non-debuggable build

        LeakCanary should only be used in debug builds, but this APK is not debuggable.
        Please follow the instructions on the "Getting started" page to only include LeakCanary in
        debug builds: https://square.github.io/leakcanary/getting_started/

        If you're sure you want to include LeakCanary in a non-debuggable build, follow the
        instructions here: https://square.github.io/leakcanary/recipes/#leakcanary-in-release-builds
      """.trimIndent()
      )
    }

    private const val LEAK_CANARY_THREAD_NAME = "LeakCanary-Heap-Dump"
  }
}
