package shark.explorer.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.onClick
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import shark.explorer.Place
import shark.explorer.Tabs

/**
 * The tabs open in this window, left to right, as a strip under the bar that opens them.
 *
 * Every tab is closeable, the last one included: a window with no tab still holds the heap dump it spent
 * seconds reading, and the bar above is one click from a tab again. See [Tabs].
 *
 * The strip scrolls rather than shrinking its tabs to nothing, because what a tab is called — which class,
 * and which instance of it — is the whole of how a strip of a dozen instances of one class is one you can
 * pick out of.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TabStrip(
  tabs: Tabs,
  /** What each tab is called, which for an object is a read of the heap dump. See [Tabs]. */
  titleOf: (Place) -> String,
  onSelect: (Int) -> Unit,
  onClose: (Int) -> Unit,
  modifier: Modifier = Modifier
) {
  if (tabs.tabs.isEmpty()) {
    return
  }
  Row(
    modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(2.dp),
    verticalAlignment = Alignment.Bottom
  ) {
    tabs.tabs.forEach { tab ->
      val isSelected = tab.id == tabs.selectedId
      Row(
        Modifier
          .background(
            if (isSelected) {
              MaterialTheme.colorScheme.surface
            } else {
              MaterialTheme.colorScheme.surfaceVariant
            }
          )
          // Middle clicking a tab closes it, which is the other half of middle clicking opening one.
          .onClick(matcher = PointerMatcher.mouse(PointerButton.Tertiary)) { onClose(tab.id) }
          // Selectable rather than clickable, because a strip of these is a set with one of them on: it
          // is also what tells a tab apart from the button and the chain row that lead to the same place.
          .selectable(selected = isSelected, role = Role.Tab) { onSelect(tab.id) }
          .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
          .widthIn(max = MAX_TAB_WIDTH),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          titleOf(tab.place),
          style = MaterialTheme.typography.bodySmall,
          fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false)
        )
        // Its own click target rather than a modifier on the tab, so that closing a tab is never
        // selecting it first: a strip where closing the fourth tab shows you the fourth tab is a strip
        // that fights back.
        Text(
          CLOSE_TAB,
          Modifier.clickable { onClose(tab.id) }.padding(horizontal = 2.dp),
          style = MaterialTheme.typography.bodySmall
        )
      }
    }
  }
}

/**
 * Shown where a tab would be once the last one has been closed.
 *
 * Says what to do rather than nothing at all, because a window that has gone blank reads as one that has
 * broken — and the heap dump behind it is still open, which is the thing worth not throwing away.
 */
internal const val NO_TAB_OPEN =
  "No tab open. The buttons above open one, and the heap dump stays read while you decide."

/** What closes a tab, on the tab: its own click target, so a test presses it rather than the tab. */
internal const val CLOSE_TAB = "✕"

/** Wide enough for a class name and an address, short enough that ten tabs are all still on the strip. */
private val MAX_TAB_WIDTH = 220.dp
