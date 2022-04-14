package leakcanary

import android.app.Application
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import androidx.test.platform.app.InstrumentationRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class ManualInstallTest {

  private val application: Application
    get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

  @Test fun appWatcher_is_not_installed() {
    assertThat(AppWatcher.isInstalled).isFalse()
  }

  @Test fun can_update_LeakCanary_config_without_installing() = tryAndRestoreConfig {
    LeakCanary.config = LeakCanary.config.copy(dumpHeap = LeakCanary.config.dumpHeap)
  }

  @Test fun no_thread_policy_violations_on_install() {
    runOnMainSyncRethrowing {
      throwOnAnyThreadPolicyViolation {
        AppWatcher.manualInstall(application)
      }
    }
  }

  @Test fun no_thread_policy_violations_on_config_update() {
    runOnMainSyncRethrowing {
      throwOnAnyThreadPolicyViolation {
        LeakCanary.config = LeakCanary.config.copy(dumpHeap = LeakCanary.config.dumpHeap)
      }
    }
  }

  @Test fun no_thread_policy_violations_on_install_then_config_update() {
    runOnMainSyncRethrowing {
      throwOnAnyThreadPolicyViolation {
        AppWatcher.manualInstall(application)
        LeakCanary.config = LeakCanary.config.copy(dumpHeap = LeakCanary.config.dumpHeap)
      }
    }
  }

  @Test fun no_thread_policy_violations_on_config_update_then_install() {
    runOnMainSyncRethrowing {
      throwOnAnyThreadPolicyViolation {
        LeakCanary.config = LeakCanary.config.copy(dumpHeap = LeakCanary.config.dumpHeap)
        AppWatcher.manualInstall(application)
      }
    }
  }

  private fun throwOnAnyThreadPolicyViolation(block: () -> Unit) {
    val previousThreadPolicy = StrictMode.getThreadPolicy()
    try {
      StrictMode.setThreadPolicy(
        ThreadPolicy.Builder()
          .detectAll()
          .penaltyDeath()
          .build()
      )
      block()
    } finally {
      StrictMode.setThreadPolicy(previousThreadPolicy)
    }
  }

  private fun runOnMainSyncRethrowing(block: () -> Unit) {
    var mainThreadThrowable: Throwable? = null
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync {
      try {
        block()
      } catch (throwable: Throwable) {
        mainThreadThrowable = throwable
      }
    }
    mainThreadThrowable?.let { cause ->
      throw cause
    }
  }
}


