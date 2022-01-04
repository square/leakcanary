package leakcanary

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties

class LeakCanaryConfigTest {

  /**
   * Validates that each field in [LeakCanary.Config] has a matching builder function
   * in [LeakCanary.Config.Builder]
   */
  @Test fun `LeakCanary Config Builder matches LeakCanary Config`() {
    assertThat(configProperties())
      .containsExactlyInAnyOrderElementsOf(configBuilderFunctions())
  }

  private fun configBuilderFunctions() = LeakCanary.Config.Builder::class.memberFunctions
    .map { it.name }
    .subtract(setOf("build", "equals", "hashCode", "toString"))

  private fun configProperties() = LeakCanary.Config::class.memberProperties
    .map { it.name }
}
