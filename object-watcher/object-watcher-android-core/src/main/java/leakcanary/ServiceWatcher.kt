package leakcanary

import android.annotation.SuppressLint
import android.app.Service
import android.os.Handler
import android.os.IBinder
import leakcanary.internal.friendly.checkMainThread
import shark.SharkLog

/**
 * Expects services to become weakly reachable soon after they receive the [Service.onDestroy]
 * callback.
 */
@SuppressLint("PrivateApi")
class ServiceWatcher(private val deletableObjectReporter: DeletableObjectReporter) :
  InstallableWatcher {

  // Kept for backward compatibility.
  constructor(reachabilityWatcher: ReachabilityWatcher) : this(
    reachabilityWatcher.asDeletableObjectReporter()
  )

  private val activityThreadClass by lazy { Class.forName("android.app.ActivityThread") }

  private val activityThreadInstance by lazy {
    activityThreadClass.getDeclaredMethod("currentActivityThread").invoke(null)!!
  }

  private val activityThreadHandler by lazy {
    val mHField = activityThreadClass.getDeclaredField("mH").apply { isAccessible = true }
    mHField[activityThreadInstance] as Handler
  }

  private val activityThreadServices by lazy {
    val mServicesField =
      activityThreadClass.getDeclaredField("mServices").apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    mServicesField[activityThreadInstance] as Map<IBinder, Service>
  }

  private var uninstallActivityThreadHandlerCallback: (() -> Unit)? = null

  override fun install() {
    checkMainThread()
    check(uninstallActivityThreadHandlerCallback == null) {
      "ServiceWatcher already installed"
    }
    try {
      swapActivityThreadHandlerCallback { mCallback ->
        uninstallActivityThreadHandlerCallback = {
          swapActivityThreadHandlerCallback {
            mCallback
          }
        }
        Handler.Callback { msg ->
          // https://github.com/square/leakcanary/issues/2114
          // On some Motorola devices (Moto E5 and G6), msg.obj is an ActivityClientRecord instead
          // of the IBinder token that AOSP sends, so this cast can't be assumed to be safe.
          val token = if (msg.what == STOP_SERVICE) msg.obj as? IBinder else null
          // ActivityThread stops holding on to the service as part of handling STOP_SERVICE, so
          // the instance has to be read before that happens.
          val service = token?.let { activityThreadServices[it] }
          val handled = mCallback?.handleMessage(msg) ?: false
          if (!handled && token != null && service != null) {
            // Handler.dispatchMessage() calls this callback before handleMessage(), which is where
            // ActivityThread destroys the service: it calls Service.onDestroy() and everything
            // that follows it synchronously. A message posted from here is queued behind that
            // work, so it runs once the service is destroyed.
            activityThreadHandler.post {
              onServiceDestroyed(token, service)
            }
          }
          handled
        }
      }
    } catch (ignored: Throwable) {
      SharkLog.d(ignored) { "Could not watch destroyed services" }
    }
  }

  override fun uninstall() {
    checkMainThread()
    uninstallActivityThreadHandlerCallback?.invoke()
    uninstallActivityThreadHandlerCallback = null
  }

  private fun onServiceDestroyed(
    token: IBinder,
    service: Service
  ) {
    if (token in activityThreadServices) {
      // ActivityThread is still holding on to the service, so handling STOP_SERVICE did not
      // destroy it. That shouldn't happen, and if a device ever does this then watching the
      // service here would report it as a leak while it's still in use.
      SharkLog.d { "STOP_SERVICE did not destroy ${service::class.java.name}, not watching it" }
      return
    }
    deletableObjectReporter.expectDeletionFor(
      service, "${service::class.java.name} received Service#onDestroy() callback"
    )
  }

  private fun swapActivityThreadHandlerCallback(swap: (Handler.Callback?) -> Handler.Callback?) {
    val mCallbackField =
      Handler::class.java.getDeclaredField("mCallback").apply { isAccessible = true }
    val mCallback = mCallbackField[activityThreadHandler] as Handler.Callback?
    mCallbackField[activityThreadHandler] = swap(mCallback)
  }

  companion object {
    private const val STOP_SERVICE = 116
  }
}
