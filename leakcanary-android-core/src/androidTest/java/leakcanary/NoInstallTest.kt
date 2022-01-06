package leakcanary

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class NoInstallTest {

  @Test fun appWatcher_is_not_installed() {
    assertThat(AppWatcher.isInstalled).isFalse()
  }

  @Test fun can_update_LeakCanary_config_without_installing() = tryAndRestoreConfig {
    LeakCanary.config = LeakCanary.config.copy(dumpHeap = false)
  }
}
