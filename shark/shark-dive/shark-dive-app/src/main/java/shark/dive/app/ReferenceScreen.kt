package shark.dive.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import shark.dive.NoteLink
import shark.dive.ReferencePage
import shark.dive.Topic

/**
 * One page of the reference, and the way to every other one under it.
 *
 * In a tab rather than in a browser, so that reading up on a label is a move like any other: the back arrow
 * comes back from it, a link to it can be pasted to somebody, and the window it is read in is the window
 * with the heap dump in it. And the text is the text this build ships — see [shark.dive.Topic].
 *
 * The other pages are listed at the foot of every page rather than down the side, which is what makes one `?`
 * the way in to all of them: a reader who wanted to know what one column meant is a reader who may want the
 * next one, and a `?` is not something to have to go and find twice.
 */
@Composable
internal fun ReferenceScreen(
  page: ReferencePage,
  onOpenTopic: (Topic, OpenIn) -> Unit,
  onCopyTopicLink: (Topic) -> Unit,
  /** Where a link written in the reference goes: out to a browser, or into this heap dump. */
  onLink: (NoteLink) -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Column(
        // A measure, not the width of the window: prose set across a maximised window is prose nobody
        // finds the start of the next line in.
        Modifier.widthIn(max = PAGE_WIDTH),
        verticalArrangement = Arrangement.spacedBy(BLOCK_SPACING)
      ) {
        Text(page.title, style = MaterialTheme.typography.titleLarge)
        page.blocks.forEach { block ->
          NoteBlockView(block, onLink)
        }
      }
      HorizontalDivider(Modifier.fillMaxWidth().padding(top = 8.dp))
      Text(MORE_TOPICS, style = MaterialTheme.typography.labelSmall, color = MUTED_TEXT)
      ReferencePage.all.filter { it.topic != page.topic }.forEach { other ->
        // A row per page rather than its title alone, so that the list says what each one answers: a
        // reader picking between six titles is guessing, and the sentence is already written.
        val open: (OpenIn) -> Unit = { openIn -> onOpenTopic(other.topic, openIn) }
        OpenTarget(open, { onCopyTopicLink(other.topic) }) {
          Column(Modifier.widthIn(max = PAGE_WIDTH).openable(open).padding(vertical = 2.dp)) {
            Text(other.title, style = MaterialTheme.typography.bodyMedium, color = LINK_COLOR)
            Text(other.hint, style = MaterialTheme.typography.bodySmall, color = MUTED_TEXT)
          }
        }
      }
    }
  }
}

/** What the list under a page is called: the pages, minus the one being read. */
private const val MORE_TOPICS = "More topics"

/** Long enough for the sentences these pages are written in, short enough to read across. */
private val PAGE_WIDTH = 720.dp

/** The same space between two blocks that a note leaves. See [NoteSection]. */
private val BLOCK_SPACING = 4.dp
