package leakcanary.internal

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import leakcanary.AppWatcher
import leakcanary.Clock
import leakcanary.ObjectWatcher
import leakcanary.OnObjectRetainedListener
import shark.SharkLog
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Note: this object must load fine in a JUnit environment
 */
internal object InternalAppWatcher {

  val isInstalled
    get() = ::application.isInitialized

  private val onAppWatcherInstalled: (Application) -> Unit

  lateinit var application: Application

  private val clock = object : Clock {
    override fun uptimeMillis(): Long {
      return SystemClock.uptimeMillis()
    }
  }

  private val mainHandler by lazy {
    Handler(Looper.getMainLooper())
  }

  private val isDebuggableBuild by lazy {
    (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
  }

  init {
    val internalLeakCanary = try {
      val leakCanaryListener = Class.forName("leakcanary.internal.InternalLeakCanary")
      leakCanaryListener.getDeclaredField("INSTANCE")
          .get(null)
    } catch (ignored: Throwable) {
      NoLeakCanary
    }
    @kotlin.Suppress("UNCHECKED_CAST")
    onAppWatcherInstalled = internalLeakCanary as (Application) -> Unit
  }

  private val checkRetainedExecutor = Executor {
    mainHandler.postDelayed(it, AppWatcher.config.watchDurationMillis)
  }
  val objectWatcher = ObjectWatcher(
      clock = clock,
      checkRetainedExecutor = checkRetainedExecutor,
      isEnabled = { true }
  )

  fun install(application: Application) {
    checkMainThread()
    if (this::application.isInitialized) {
      return
    }
    InternalAppWatcher.application = application
    if (isDebuggableBuild) {
      SharkLog.logger = DefaultCanaryLog()
    }

    val configProvider = { AppWatcher.config }
    ActivityDestroyWatcher.install(application, objectWatcher, configProvider)
    FragmentDestroyWatcher.install(application, objectWatcher, configProvider)
    onAppWatcherInstalled(application)
  }

  inline fun <reified T : Any> noOpDelegate(): T {
    val javaClass = T::class.java
    val noOpHandler = InvocationHandler { _, _, _ ->
      // no op
    }
    return Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(javaClass), noOpHandler
    ) as T
  }

  private fun checkMainThread() {
    if (Looper.getMainLooper().thread !== Thread.currentThread()) {
      throw UnsupportedOperationException(
          "Should be called from the main thread, not ${Thread.currentThread()}"
      )
    }
  }

  object NoLeakCanary : (Application) -> Unit, OnObjectRetainedListener {
    override fun invoke(application: Application) {
    }

    override fun onObjectRetained() {
    }
  }
}