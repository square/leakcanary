package shark.dive.app

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class SharkDiveDirectoryTest {

  @Test fun `nothing in the environment is the home directory`() {
    val directory = sharkDiveDirectory(environment = emptyMap(), homeDirectory = "/home/somebody")

    assertThat(directory).isEqualTo(File("/home/somebody/.shark-dive"))
  }

  @Test fun `the variable moves everything this app writes`() {
    val directory = sharkDiveDirectory(
      environment = mapOf(SHARK_DIVE_DIRECTORY_VARIABLE to "/tmp/eval/shark-dive"),
      homeDirectory = "/home/somebody"
    )

    assertThat(directory).isEqualTo(File("/tmp/eval/shark-dive"))
  }

  @Test fun `a blank variable is the home directory and not a directory with no name`() {
    // Which is what a shell that exported an unset variable hands over, and the home directory is a better
    // answer for it than this process's working directory.
    val directory = sharkDiveDirectory(
      environment = mapOf(SHARK_DIVE_DIRECTORY_VARIABLE to " "),
      homeDirectory = "/home/somebody"
    )

    assertThat(directory).isEqualTo(File("/home/somebody/.shark-dive"))
  }
}
