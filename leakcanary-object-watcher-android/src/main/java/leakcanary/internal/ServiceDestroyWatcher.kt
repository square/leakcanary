package leakcanary.internal

import android.annotation.SuppressLint
import android.app.Service
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.util.ArrayMap
import leakcanary.AppWatcher
import leakcanary.ObjectWatcher
import shark.SharkLog
import java.lang.reflect.Proxy

internal class ServiceDestroyWatcher private constructor(
  private val objectWatcher: ObjectWatcher,
  private val configProvider: () -> AppWatcher.Config
) {

  private val mTmpServices = mutableMapOf<IBinder, Service>()

  private fun onServicePreDestroy(
    token: IBinder,
    service: Service
  ) {
    if (configProvider().watchServices) {
      mTmpServices[token] = service
    }
  }

  private fun onServiceDestroyed(token: IBinder) {
    if (configProvider().watchServices) {
      mTmpServices.remove(token)?.also {
        objectWatcher.watch(
          it, "${it::class.java.name} received Service#onDestroy callback"
        )
      }
    }
  }

  companion object {

    private const val STOP_SERVICE = 116

    private const val METHOD_SERVICE_DONE_EXECUTING = "serviceDoneExecuting"

    @SuppressLint("PrivateApi")
    fun install(
      objectWatcher: ObjectWatcher,
      configProvider: () -> AppWatcher.Config
    ) {
      val serviceDestroyWatcher =
        ServiceDestroyWatcher(objectWatcher, configProvider)

      try {
        installPreDestroyedWatcher(serviceDestroyWatcher)
        installDestroyedWatcher(serviceDestroyWatcher)
      } catch (ignored: Throwable) {
        SharkLog.d(ignored) { "Could not watch destroyed services" }
      }
    }

    @SuppressLint("PrivateApi")
    private fun installPreDestroyedWatcher(serviceDestroyWatcher: ServiceDestroyWatcher) {
      val activityThreadClass = Class.forName("android.app.ActivityThread")
      val activityThreadInstance =
        activityThreadClass.getDeclaredMethod("currentActivityThread").invoke(null)

      val mServicesField =
        activityThreadClass.getDeclaredField("mServices").apply { isAccessible = true }

      @Suppress("UNCHECKED_CAST")
      val mServices = mServicesField[activityThreadInstance] as Map<IBinder, Service>

      val mHField =
        activityThreadClass.getDeclaredField("mH").apply { isAccessible = true }
      val mH = mHField[activityThreadInstance] as Handler

      val mCallbackField =
        Handler::class.java.getDeclaredField("mCallback").apply { isAccessible = true }
      val mCallback = mCallbackField[mH] as Handler.Callback?

      mCallbackField[mH] = Handler.Callback { msg ->
        if (msg.what == STOP_SERVICE) {
          val key = msg.obj as IBinder
          mServices[key]?.let {
            serviceDestroyWatcher.onServicePreDestroy(key, it)
          }
        }
        mCallback?.handleMessage(msg) ?: false
      }
    }

    @SuppressLint("PrivateApi")
    private fun installDestroyedWatcher(serviceDestroyWatcher: ServiceDestroyWatcher) {
      val singletonClass = Class.forName("android.util.Singleton")
      val mInstanceField =
        singletonClass.getDeclaredField("mInstance").apply { isAccessible = true }

      val (className, fieldName) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        "android.app.ActivityManager" to "IActivityManagerSingleton"
      } else {
        "android.app.ActivityManagerNative" to "gDefault"
      }

      val activityManagerClass = Class.forName(className)
      val activityManagerSingletonField =
        activityManagerClass.getDeclaredField(fieldName).apply { isAccessible = true }
      val activityManagerSingletonInstance = activityManagerSingletonField[activityManagerClass]
      val activityManagerInstance = mInstanceField[activityManagerSingletonInstance]

      val iActivityManagerInterface = Class.forName("android.app.IActivityManager")
      mInstanceField[activityManagerSingletonInstance] = Proxy.newProxyInstance(
        iActivityManagerInterface.classLoader, arrayOf(iActivityManagerInterface)
      ) { _, method, args ->
        if (METHOD_SERVICE_DONE_EXECUTING == method.name) {
          val token = args!![0] as IBinder
          if (serviceDestroyWatcher.mTmpServices.containsKey(token)) {
            serviceDestroyWatcher.onServiceDestroyed(token)
          }
        }
        method.invoke(activityManagerInstance, *args)
      }
    }
  }
}