package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.SharkLog.Logger
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class SharkLogTest {

  private class StreamLogger(private val stream: PrintStream) : Logger {
    override fun d(message: String) = stream.print(message)
    override fun d(
      throwable: Throwable,
      message: String
    ) = stream.print("$message ${throwable.message}")
  }

  @Test fun `logging works when logger is set`() {
    val outputStream = ByteArrayOutputStream()

    SharkLog.logger = StreamLogger(PrintStream(outputStream))

    // Test debug logging
    SharkLog.d { "Test debug" }
    assertThat(outputStream.toString()).isEqualTo("Test debug")
  }

  @Test fun `logging with exception works when logger is set`() {
    val outputStream = ByteArrayOutputStream()

    SharkLog.logger = StreamLogger(PrintStream(outputStream))

    // Test error logging
    SharkLog.d(Exception("Test exception")) { "Test error" }
    assertThat(outputStream.toString()).isEqualTo("Test error Test exception")
  }

  @Test fun `logging is no-op without logger and string is ignored`() {
    SharkLog.logger = null

    // Logging message will throw an exception when attempting to use it
    // But since it's in lambda string will not be accessed
    SharkLog.d { "".substring(1) }

    SharkLog.d(Exception("Test exception")) { "".substring(1) }
  }
}