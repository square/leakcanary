package leakcanary

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

class ServiceWatcherTest {

  /**
   * Records what [ServiceWatcher] reports for [TestService], and whether the service had already
   * been through [android.app.Service.onDestroy] by then.
   */
  private class Recorder : DeletableObjectReporter {

    private val reported = CountDownLatch(1)

    val wasReported: Boolean
      get() = reported.count == 0L

    @Volatile
    var reason: String? = null

    @Volatile
    var destroyedWhenReported = false

    override fun expectDeletionFor(
      target: Any,
      reason: String
    ): TrackedObjectReachability {
      if (target is TestService) {
        this.reason = reason
        destroyedWhenReported = TestService.isDestroyed
        reported.countDown()
      }
      return StronglyReachable
    }

    fun awaitReport() {
      assertThat(reported.await(10, SECONDS))
        .describedAs("ServiceWatcher reported a destroyed TestService")
        .isTrue()
    }
  }

  private object StronglyReachable : TrackedObjectReachability {
    override val isStronglyReachable = true
    override val isRetained = false
  }

  private val application = ApplicationProvider.getApplicationContext<Application>()

  private val serviceIntent by lazy { Intent(application, TestService::class.java) }

  private val installedWatchers = mutableListOf<ServiceWatcher>()

  @Before fun setUp() {
    TestService.reset()
    AppWatcher.objectWatcher.clearAllObjectsTracked()
  }

  @After fun tearDown() {
    application.stopService(serviceIntent)
    uninstallWatchers()
    AppWatcher.objectWatcher.clearAllObjectsTracked()
  }

  @Test fun destroyedServiceIsWatchedAfterOnDestroy() {
    val recorder = installWatcher()

    startTestService()
    stopTestService()

    recorder.awaitReport()
    assertThat(recorder.destroyedWhenReported)
      .describedAs("Service#onDestroy() had already run when the service was watched")
      .isTrue()
    assertThat(recorder.reason)
      .isEqualTo("${TestService::class.java.name} received Service#onDestroy() callback")
  }

  /**
   * An app can have more than one [ServiceWatcher] installed, and each one replaces the
   * [android.os.Handler.Callback] the previous one installed, so a watcher that stops delegating
   * silently breaks every watcher installed before it.
   */
  @Test fun watcherInstalledFirstStillWatches() {
    val firstRecorder = installWatcher()
    val secondRecorder = installWatcher()

    startTestService()
    stopTestService()

    secondRecorder.awaitReport()
    firstRecorder.awaitReport()
  }

  @Test fun uninstalledWatcherStopsWatching() {
    val recorder = installWatcher()
    uninstallWatchers()

    startTestService()
    stopTestService()
    // ServiceWatcher watches the service from a message posted while the service is destroyed, so
    // by the time the main thread runs anything posted after that the watching would have
    // happened.
    runOnMainSync { }

    assertThat(recorder.wasReported)
      .describedAs("An uninstalled ServiceWatcher watched a destroyed service")
      .isFalse()
  }

  private fun installWatcher(): Recorder {
    val recorder = Recorder()
    val watcher = ServiceWatcher(recorder)
    runOnMainSync { watcher.install() }
    installedWatchers += watcher
    return recorder
  }

  private fun uninstallWatchers() {
    installedWatchers.asReversed().forEach { watcher ->
      runOnMainSync { watcher.uninstall() }
    }
    installedWatchers.clear()
  }

  private fun startTestService() {
    application.startService(serviceIntent)
    assertThat(TestService.created.await(10, SECONDS))
      .describedAs("TestService received onCreate()")
      .isTrue()
  }

  private fun stopTestService() {
    application.stopService(serviceIntent)
    assertThat(TestService.destroyed.await(10, SECONDS))
      .describedAs("TestService received onDestroy()")
      .isTrue()
  }
}
