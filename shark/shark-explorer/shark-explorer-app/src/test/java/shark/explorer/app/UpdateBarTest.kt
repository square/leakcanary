package shark.explorer.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * The bar that says a newer release exists. The check itself is [UpdateCheckTest]; this is only about the
 * window saying so, and about it going away.
 */
@OptIn(ExperimentalTestApi::class)
class UpdateBarTest {

  @get:Rule val logged = RecordedLog()

  @Test fun `a window with no update found says nothing about updates`() = explorerUiTest {
    setContent { ExplorerApp(heapDumpFile = null, onHeapDumpChosen = { _, _ -> }) }

    assertThat(onAllNodesWithText(DOWNLOAD_UPDATE).fetchSemanticsNodes()).isEmpty()
  }

  @Test fun `a window with an update found names the version`() = explorerUiTest {
    setContent {
      ExplorerApp(
        heapDumpFile = null,
        onHeapDumpChosen = { _, _ -> },
        updateNotice = UpdateNotice().apply { offer(AN_UPDATE) }
      )
    }

    onNodeWithText(updateAvailableText(AN_UPDATE.version, SharkExplorerVersion.current)).assertIsDisplayed()
    onNodeWithText(DOWNLOAD_UPDATE).assertIsDisplayed()
  }

  /**
   * One notice per run, so a window is not the thing that has been told: dismissing in one has to take the
   * bar out of every window of that run, which is what sharing the [UpdateNotice] gets us.
   */
  @Test fun `dismissing takes the bar out of every window sharing the notice`() = explorerUiTest {
    val notice = UpdateNotice().apply { offer(AN_UPDATE) }
    setContent {
      ExplorerApp(heapDumpFile = null, onHeapDumpChosen = { _, _ -> }, updateNotice = notice)
    }

    onNodeWithText(DISMISS_UPDATE).performClick()

    assertThat(notice.availableUpdate).isNull()
    assertThat(onAllNodesWithText(DOWNLOAD_UPDATE).fetchSemanticsNodes()).isEmpty()
  }

  private companion object {
    val AN_UPDATE = AvailableUpdate(
      version = "99.0.0",
      releaseUrl = "https://github.com/square/leakcanary/releases/tag/shark-explorer-99.0.0"
    )
  }
}
