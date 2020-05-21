package leakcanary

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties

class AppWatcherTest {

  @Test fun appWatcherLoads_notInstalled() {
    assertThat(AppWatcher.isInstalled)
        .describedAs("Ensure AppWatcher doesn't crash in JUnit tests")
        .isFalse()
  }

  /**
   * Validates that each field in [AppWatcher.Config] has a matching builder function
   * in [AppWatcher.Config.Builder]
   */
  @Test fun `AppWatcher Config Builder matches AppWatcher Config`() {
    assertThat(configProperties())
        .containsExactlyInAnyOrderElementsOf(configBuilderFunctions())
  }

  private fun configBuilderFunctions() = AppWatcher.Config.Builder::class.memberFunctions
      .filter { member ->
        member.annotations.none { it is Deprecated }
      }
      .map { it.name }
      .subtract(listOf("build", "equals", "hashCode", "toString"))

  private fun configProperties() = AppWatcher.Config::class.memberProperties
      .filter { member ->
        // Ignore deprecated fields, we don't need builders for those
        member.annotations.none { it is Deprecated }
      }
      .map { it.name }
}