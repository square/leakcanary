package leakcanary.internal

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import leakcanary.CanaryLog
import leakcanary.Clock
import leakcanary.LeakSentry
import leakcanary.OnObjectRetainedListener
import leakcanary.ObjectWatcher
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

internal object InternalLeakSentry {

  val isInstalled
    get() = ::application.isInitialized

  private val onLeakSentryInstalled: (Application) -> Unit
  private val ON_OBJECT_RETAINED_LISTENER: OnObjectRetainedListener

  val isDebuggableBuild by lazy {
    (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
  }

  lateinit var application: Application

  private val clock = object : Clock {
    override fun uptimeMillis(): Long {
      return SystemClock.uptimeMillis()
    }
  }

  private val mainHandler = Handler(Looper.getMainLooper())

  init {
    val internalLeakCanary = try {
      val leakCanaryListener = Class.forName("leakcanary.internal.InternalLeakCanary")
      leakCanaryListener.getDeclaredField("INSTANCE")
          .get(null)
    } catch (ignored: Throwable) {
      NoLeakCanary
    }
    @kotlin.Suppress("UNCHECKED_CAST")
    onLeakSentryInstalled = internalLeakCanary as (Application) -> Unit
    ON_OBJECT_RETAINED_LISTENER = internalLeakCanary as OnObjectRetainedListener
  }

  private val checkRetainedExecutor = Executor {
    mainHandler.postDelayed(it, LeakSentry.config.watchDurationMillis)
  }
  val objectWatcher = ObjectWatcher(
      clock = clock,
      checkRetainedExecutor = checkRetainedExecutor,
      onObjectRetainedListener = ON_OBJECT_RETAINED_LISTENER,
      isEnabled = { LeakSentry.config.enabled }
  )

  fun install(application: Application) {
    CanaryLog.d("Installing LeakSentry")
    checkMainThread()
    if (this::application.isInitialized) {
      return
    }
    InternalLeakSentry.application = application

    val configProvider = { LeakSentry.config }
    ActivityDestroyWatcher.install(application, objectWatcher, configProvider)
    FragmentDestroyWatcher.install(application, objectWatcher, configProvider)
    onLeakSentryInstalled(application)
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