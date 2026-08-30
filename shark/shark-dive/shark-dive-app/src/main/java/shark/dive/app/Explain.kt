package shark.dive.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import shark.dive.ReferencePage
import shark.dive.Topic

/**
 * Whatever it wraps, with a `?` after it: one sentence on hover, and the page it opens with that sentence
 * when clicked.
 *
 * **The same `?` for everyone, forever.** It doesn't fade, it isn't a first-run tour, and it says the same
 * thing on the thousandth heap dump as on the first — a hint that decays is a hint whoever built this stops
 * seeing, so the day it starts saying the wrong thing nobody here notices. It is also there to be found
 * later, which is the moment a hint is wanted: not while getting something done, but afterwards, wondering
 * what that column was.
 *
 * Which is what puts the whole explanation in the page rather than in the tooltip. The tooltip is one
 * sentence because it is read while deciding whether to look further, and it is the page's own first
 * sentence, so the two cannot disagree. See [Topic].
 *
 * The `?` and not the tooltip is what takes the click: a tooltip's surface swallows pointer events in
 * Compose Desktop, so the footer names the `?` rather than saying "click here".
 */
@Composable
internal fun Explain(
  topic: Topic,
  /** Where a click goes, which is a tab on [shark.dive.Place.Reference]. See [HeapDumpDive]. */
  onExplain: (Topic) -> Unit,
  /** In [RowScope] so that a label that has to ellipsize can take the width the `?` leaves. */
  content: @Composable RowScope.() -> Unit
) {
  val page = ReferencePage.of(topic)
  Row(
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    content()
    Hint(page.hint, footer = LEARN_MORE) {
      Text(
        EXPLAIN_GLYPH,
        // Named after the page rather than after the label it follows, because that is where it goes and
        // because a screen has several of these: one `?` is not findable, and this is also how a test
        // clicks the right one.
        Modifier.semantics { contentDescription = "$MORE_ABOUT ${page.title}" }
          .clickableRow { onExplain(topic) }
          // So that the character is not the whole of what a click has to land on.
          .padding(horizontal = 2.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MUTED_TEXT
      )
    }
  }
}

/** What a click on it does, said in the hint because the hint itself cannot be clicked. See [Hint]. */
private const val LEARN_MORE = "Click ? to read more"

/**
 * A question mark, in the muted grey of everything on screen that is about the window rather than about the
 * heap dump. An icon would be a glyph in a circle at this size, and a circle around a `?` is a `?`.
 */
private const val EXPLAIN_GLYPH = "?"

/** What the `?` is called to anything that can't see where it sits: a screen reader, or a test. */
internal const val MORE_ABOUT = "More about"
