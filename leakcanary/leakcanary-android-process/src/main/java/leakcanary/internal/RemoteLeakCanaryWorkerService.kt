package leakcanary.internal

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.work.Configuration
import androidx.work.multiprocess.RemoteWorkerService
import leakcanary.AppWatcher

/**
 * Using a custom class name instead of RemoteWorkerService so that
 * hosting apps can use RemoteWorkerService without a naming conflict.
 */
class RemoteLeakCanaryWorkerService : RemoteWorkerService() {

  /**
   * RemoteLeakCanaryWorkerService is running in the :leakcanary process, and androidx startup only
   * initializes WorkManager in the main process. In RemoteWorkerService.onCreate(),
   * WorkManager has not been init, so it would crash. We can't blindly call init() as developers
   * might be calling WorkManager.initialize() from Application.onCreate() for all processes, in
   * which case a 2nd init would fail. So we want to init if nothing has init WorkManager before.
   * But there's no isInit() API. However, WorkManager will automatically pull in the application
   * context and if that context implements Configuration.Provider then it'll pull the
   * configuration from it. So we cheat WorkManager by returning a fake app context that provides
   * our own custom configuration.
   */
  class FakeAppContextConfigurationProvider(base: Context) : ContextWrapper(base),
    Configuration.Provider {

    // No real app context for you, sorry!
    override fun getApplicationContext() = this

    override val workManagerConfiguration: Configuration
      get() = Configuration.Builder()
        // If the default package name is not set, WorkManager will cancel all runnables
        // when initialized as it can't tell that it's not running in the main process.
        // This would lead to an extra round trip where the canceling reaches the main process
        // which then cancels the remote job and reschedules it and then only the work gets done.
        .setDefaultProcessName(packageName)
        .build()
  }

  private val fakeAppContext by lazy {
    // We set the base context to the real app context so that getting resources etc still works.
    FakeAppContextConfigurationProvider(super.getApplicationContext())
  }

  override fun getApplicationContext(): Context {
    return fakeAppContext
  }

  override fun onCreate() {
    // Ideally we wouldn't need to install AppWatcher at all here, however
    // the installation triggers InternalsLeakCanary to store the application instance
    // which is then used by the event listeners that respond to analysis progress.
    if (!AppWatcher.isInstalled) {
      val application = super.getApplicationContext() as Application
      AppWatcher.manualInstall(
        application,
        // Nothing to watch in the :leakcanary process.
        watchersToInstall = emptyList()
      )
    }
    super.onCreate()
  }
}
