package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class PrettyPrintJsonTest {

  private val pretty = PrettyPrintJson()

  @Test
  fun `flat object`() {
    assertThat(pretty.format("""{"a":"b","c":"d"}""")).isEqualTo(
      """
      {
        "a": "b",
        "c": "d"
      }
      """.trimIndent()
    )
  }

  @Test
  fun `nested object`() {
    assertThat(pretty.format("""{"a":{"b":"c"}}""")).isEqualTo(
      """
      {
        "a": {
          "b": "c"
        }
      }
      """.trimIndent()
    )
  }

  @Test
  fun `array of primitives`() {
    assertThat(pretty.format("""[1,2,3]""")).isEqualTo(
      """
      [
        1,
        2,
        3
      ]
      """.trimIndent()
    )
  }

  @Test
  fun `array of objects`() {
    assertThat(pretty.format("""[{"a":1},{"b":2}]""")).isEqualTo(
      """
      [
        {
          "a": 1
        },
        {
          "b": 2
        }
      ]
      """.trimIndent()
    )
  }

  @Test
  fun `structural characters inside string are not treated as structural`() {
    assertThat(pretty.format("""{"a":"{[,:]}"}""")).isEqualTo(
      """
      {
        "a": "{[,:]}"
      }
      """.trimIndent()
    )
  }

  @Test
  fun `escaped quote inside string`() {
    assertThat(pretty.format("""{"a":"say \"hi\""}""")).isEqualTo(
      """
      {
        "a": "say \"hi\""
      }
      """.trimIndent()
    )
  }

  @Test
  fun `escaped backslash followed by closing quote`() {
    assertThat(pretty.format("""{"a":"path\\"}""")).isEqualTo(
      """
      {
        "a": "path\\"
      }
      """.trimIndent()
    )
  }

  @Test
  fun `null value`() {
    assertThat(pretty.format("""{"a":null}""")).isEqualTo(
      """
      {
        "a": null
      }
      """.trimIndent()
    )
  }

  @Test
  fun `number and boolean values`() {
    assertThat(pretty.format("""{"n":42,"b":true}""")).isEqualTo(
      """
      {
        "n": 42,
        "b": true
      }
      """.trimIndent()
    )
  }

  @Test
  fun `empty object`() {
    assertThat(pretty.format("{}")).isEqualTo("{\n}")
  }

  @Test
  fun `empty array`() {
    assertThat(pretty.format("[]")).isEqualTo("[\n]")
  }

  @Test
  fun `existing whitespace in input is ignored`() {
    assertThat(pretty.format("""{ "a" : "b" }""")).isEqualTo(
      """
      {
        "a": "b"
      }
      """.trimIndent()
    )
  }
}
