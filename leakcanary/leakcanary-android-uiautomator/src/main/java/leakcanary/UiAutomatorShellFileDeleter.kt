package leakcanary

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File

object UiAutomatorShellFileDeleter : FileDeleter {
  override fun delete(file: File) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device = UiDevice.getInstance(instrumentation)
    device.executeShellCommand("rm ${file.absolutePath}")
  }
}
