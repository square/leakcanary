package shark.explorer.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ExplorerAppTest {

  @Test fun `app renders`() {
    runComposeUiTest {
      setContent {
        ExplorerApp()
      }
      onNodeWithText("Shark Explorer").assertIsDisplayed()
    }
  }
}
