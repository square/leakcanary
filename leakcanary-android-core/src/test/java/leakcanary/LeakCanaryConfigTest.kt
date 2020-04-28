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
      .subtract(listOf("build", "equals", "hashCode", "toString"))

  private fun configProperties() = LeakCanary.Config::class.memberProperties
      .filter { member ->
        // Ignore deprecated fields, we don't need builders for those
        member.annotations.none { it is Deprecated }
      }
      .map { it.name }
}