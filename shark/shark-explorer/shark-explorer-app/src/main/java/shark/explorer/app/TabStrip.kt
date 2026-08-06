package shark.explorer.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.onClick
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
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
 * The tabs open in this window, left to right and wrapping onto a line of its own once a line is full, as
 * a strip under the bar that opens them.
 *
 * Every tab is closeable, the last one included: a window with no tab still holds the heap dump it spent
 * seconds reading, and the bar above is one click from a tab again. See [Tabs].
 *
 * Neither shrinking its tabs nor scrolling them off the edge, because what a tab is called — which class,
 * and which instance of it — is the whole of how a strip of a dozen instances of one class is one you can
 * pick out of, and a tab you have to go looking for is one you may as well not have opened. So the strip
 * grows down into the window instead: what it costs is the view's height, and only for someone who has
 * opened enough tabs to be reading across them anyway.
 */
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
  FlowRow(
    modifier.fillMaxWidth().padding(horizontal = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(2.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
    itemVerticalAlignment = Alignment.Bottom
  ) {
    tabs.tabs.forEach { tab ->
      // Keyed on the tab rather than on where it is in the strip, because a tab opens *beside* the one it
      // was opened from: without this, inserting one in the middle would hand its state to the tab that
      // used to be there, and the one that grew in would be whichever ended up last.
      key(tab.id) {
        TabView(
          title = titleOf(tab.place),
          isSelected = tab.id == tabs.selectedId,
          onSelect = { onSelect(tab.id) },
          onClose = { onClose(tab.id) }
        )
      }
    }
  }
}

/**
 * One tab, growing into the strip as it opens.
 *
 * Widening from nothing rather than appearing at full width, the way a browser does it, because a tab
 * opened in the background is one nothing else on screen announces: what says a middle click did anything
 * at all is the strip moving over to make room. It also puts the tab where it went — beside the one it was
 * opened from, which is not where a strip that pops a tab in reads as having put it.
 *
 * Closing is not animated, deliberately: a strip that holds a tab open for a moment after it was closed is
 * one where the tab under the pointer isn't the tab a second click closes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabView(
  title: String,
  isSelected: Boolean,
  onSelect: () -> Unit,
  onClose: () -> Unit
) {
  // Starting closed and opening on the first composition, which is what makes this animate on the way in:
  // a tab that was already open when the strip drew it has nothing to animate.
  val opening = remember { MutableTransitionState(false) }
  opening.targetState = true
  AnimatedVisibility(
    visibleState = opening,
    // From the start edge, so the tab holds the spot it was inserted at and pushes the ones after it along,
    // rather than sliding in from under its neighbour.
    enter = expandHorizontally(
      animationSpec = tween(TAB_OPEN_MILLIS),
      expandFrom = Alignment.Start
    ) + fadeIn(tween(TAB_OPEN_MILLIS))
  ) {
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
        .onClick(matcher = PointerMatcher.mouse(PointerButton.Tertiary)) { onClose() }
        // Selectable rather than clickable, because a strip of these is a set with one of them on: it
        // is also what tells a tab apart from the button and the chain row that lead to the same place.
        .selectable(selected = isSelected, role = Role.Tab) { onSelect() }
        .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
        .widthIn(max = MAX_TAB_WIDTH),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        title,
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
        Modifier.clickable { onClose() }.padding(horizontal = 2.dp),
        style = MaterialTheme.typography.bodySmall
      )
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

/**
 * How long a tab takes to grow into the strip.
 *
 * Long enough to be read as the strip making room, short enough that a middle click and the tab being
 * there are one thing: a tab you have to wait for is one you would rather had just appeared.
 */
private const val TAB_OPEN_MILLIS = 150
