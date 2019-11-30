package leakcanary

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class AppWatcherTest {

  @Test fun appWatcherLoads_notInstalled() {
    assertThat(AppWatcher.isInstalled)
        .describedAs("Ensure AppWatcher doesn't crash in JUnit tests")
        .isFalse()
  }
}