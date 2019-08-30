package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class ProguardMappingTest {

  @Test
  fun readAndParseMappingFile() {
    val proguardMappingText = """
            # comment
            com.test.ClearClassName1 -> com.test.ObfuscatedClassName1:
                com.test.FieldType1 clearFieldName1 -> obfuscatedFieldName1
            com.test.ClearClassName2 -> com.test.ObfuscatedClassName2:
                com.test.FieldType2 clearFieldName2 -> obfuscatedFieldName2
        """.trimIndent()

    val proguardMapping =
        ProguardMappingReader(proguardMappingText.byteInputStream(Charsets.UTF_8))
            .readProguardMapping()

    assertThat(
        proguardMapping.deobfuscateClassName("com.test.ObfuscatedClassName1")
    ).isEqualTo("com.test.ClearClassName1")

    assertThat(
        proguardMapping.deobfuscateFieldName(
            "com.test.ObfuscatedClassName1",
            "obfuscatedFieldName1"
        )
    ).isEqualTo("clearFieldName1")

    assertThat(
        proguardMapping.deobfuscateClassName("com.test.ObfuscatedClassName2")
    ).isEqualTo("com.test.ClearClassName2")

    assertThat(
        proguardMapping.deobfuscateFieldName(
            "com.test.ObfuscatedClassName2",
            "obfuscatedFieldName2"
        )
    ).isEqualTo("clearFieldName2")
  }
}