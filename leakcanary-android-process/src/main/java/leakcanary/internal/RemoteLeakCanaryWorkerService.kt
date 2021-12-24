package leakcanary.internal

import androidx.work.Configuration.Builder
import androidx.work.WorkManager
import androidx.work.multiprocess.RemoteWorkerService

/**
 * Using a custom class name instead of RemoteWorkerService so that
 * hosting apps can use RemoteWorkerService without a naming conflict.
 */
class RemoteLeakCanaryWorkerService : RemoteWorkerService() {
  override fun onCreate() {
    ensureWorkManagerInit()
    super.onCreate()
  }

  /**
   * This is running in the :leakcanary process, and androidx startup only initializes WorkManager
   * in the main process. At this point, WorkManager has not been init, so super.onCreate() would
   * crash. We can't blindly call init() as developers might be calling WorkManager.initialize()
   * from Application.onCreate() for all processes, in which case a 2nd init would fail. So we want
   * to init if nothing has init WorkManager before. But there's no isInit() API. So we just try
   * to get an instance, and if that throws we know we need to init.
   */
  private fun ensureWorkManagerInit() {
    try {
      WorkManager.getInstance(applicationContext)
    } catch (ignored: Throwable) {
      WorkManager.initialize(applicationContext, Builder().build())
    }
  }
}
