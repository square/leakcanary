package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.SharkLog.Logger
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class SharkLogTest {

  @Test fun `logging works when logger is set`() {

    val outputStream = ByteArrayOutputStream()
    val printStream = PrintStream(outputStream)

    SharkLog.logger = object : Logger {
      override fun d(message: String) = printStream.print(message)
      override fun e(throwable: Throwable, message: String) = printStream.print("$message+${throwable.message}")
    }

    //Test debug logging
    SharkLog.d { "Test debug" }
    assertThat(outputStream.toString()).isEqualTo("Test debug")

    outputStream.reset()

    //Test error logging
    SharkLog.e(Exception("Test exception")) { "Test error" }
    assertThat(outputStream.toString()).isEqualTo("Test error+Test exception")
  }

  @Test fun `logging is no-op without logger and string is ignored`() {

    SharkLog.logger = null

    //Logging message will throw an exception when attempting to use it
    //But since it's in lambda string will not be accessed
    SharkLog.d { "".substring(1) }

    SharkLog.e(Exception("Test exception")) { "".substring(1) }
  }
}