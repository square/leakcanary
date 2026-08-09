package shark.explorer.app

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.onClick
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics

/**
 * Which tab a click is asking for an object in.
 *
 * Every way to an object in this window ends up here, which is what keeps them one thing: a rectangle of
 * the map, a step of the chain, a field in the panel and a row of a list all say where they lead and
 * nothing else, and the window decides what that means for the tabs.
 */
internal enum class OpenIn {

  /** A plain click, which moves the tab being read the way following a link moves a browser tab. */
  CURRENT_TAB,

  /**
   * A middle click, a ⌘ or Ctrl click, or "Open in a new tab" from the right click menu.
   *
   * Behind what is being read rather than in front of it: opening a tab this way is parking somewhere to
   * come back to, so moving the reader off what they were looking at would defeat it. The buttons on the
   * bar open in front instead, because clicking one is asking to be somewhere else.
   */
  NEW_TAB
}

/**
 * Whatever it wraps is a way to a place, with the gestures a browser has taught everyone.
 *
 * The right click menu is here rather than beside the click handling because it takes a composable to
 * draw, and it is what makes the gesture discoverable: a reader who has never tried ⌘ clicking a
 * rectangle finds the same thing spelled out in words.
 *
 * **Copying a link sits beside opening a tab wherever there is one of these**, because they are the same
 * thought a step apart: a place worth a tab of its own is a place worth sending to someone. See
 * [shark.explorer.DeepLink].
 */
@Composable
internal fun OpenTarget(
  onOpen: (OpenIn) -> Unit,
  onCopyLink: () -> Unit,
  content: @Composable () -> Unit
) {
  ContextMenuArea(
    items = {
      listOf(
        ContextMenuItem(OPEN_IN_NEW_TAB) { onOpen(OpenIn.NEW_TAB) },
        ContextMenuItem(COPY_LINK, onCopyLink)
      )
    },
    content = content
  )
}

/**
 * Whatever it wraps names a place that is already open, or that a click opens with no choice about where.
 *
 * A tab and a button on the bar: nothing to offer about which tab, and still a place to link to.
 */
@Composable
internal fun CopyLinkTarget(
  onCopyLink: () -> Unit,
  content: @Composable () -> Unit
) {
  ContextMenuArea(items = { listOf(ContextMenuItem(COPY_LINK, onCopyLink)) }, content = content)
}

/**
 * A row that leads to an object: clicking it goes there, middle clicking and ⌘ clicking open it in a tab
 * of its own.
 *
 * The hand is the whole of how it says it leads somewhere, rather than a colour: which object a step is,
 * is drawn the same way everywhere the window names one, and half of those places are nothing to click.
 *
 * The three matchers are exclusive, so exactly one of them answers a click. Semantics are added by hand
 * because this doesn't go through [androidx.compose.foundation.clickable], and a block naming an object
 * has to stay the one merged node that a test finds by any line of it. No role: this is a link rather
 * than a button, and the bar above has buttons of the same names as the places these lead to.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.openable(onOpen: (OpenIn) -> Unit): Modifier =
  pointerHoverIcon(PointerIcon.Hand)
    .semantics(mergeDescendants = true) {
      onClick {
        onOpen(OpenIn.CURRENT_TAB)
        true
      }
    }
    .onClick(matcher = PointerMatcher.mouse(PointerButton.Tertiary)) { onOpen(OpenIn.NEW_TAB) }
    .onClick(keyboardModifiers = { isMetaPressed || isCtrlPressed }) { onOpen(OpenIn.NEW_TAB) }
    .onClick(keyboardModifiers = { !isMetaPressed && !isCtrlPressed }) { onOpen(OpenIn.CURRENT_TAB) }

/**
 * Where each press inside a view lands and which tab it is asking for.
 *
 * The views draw their cells rather than composing them, so there is no modifier to hang [openable] on and
 * the same three gestures have to be read out of the pointer events by hand. All three read them through
 * this, so that a middle click on a rectangle, on a ring and on a row of the stack mean one thing.
 *
 * On press rather than on release, which is immediate: with nothing here waiting for a second click, holding
 * every click until the button came up would cost the whole map a delay for nothing.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal suspend fun PointerInputScope.detectOpenPresses(onPress: (Offset, OpenIn) -> Unit) {
  awaitPointerEventScope {
    while (true) {
      val event = awaitPointerEvent()
      // A right click is the context menu's, which is where opening in a new tab is spelled out in words.
      if (event.type == PointerEventType.Press && event.button != PointerButton.Secondary) {
        onPress(event.changes.first().position, event.openIn())
      }
    }
  }
}

/** Which tab a press is asking for, which is the same three gestures [openable] answers. */
@OptIn(ExperimentalComposeUiApi::class)
private fun PointerEvent.openIn(): OpenIn = when {
  button == PointerButton.Tertiary -> OpenIn.NEW_TAB
  keyboardModifiers.isMetaPressed || keyboardModifiers.isCtrlPressed -> OpenIn.NEW_TAB
  else -> OpenIn.CURRENT_TAB
}

/** What the right click menu on anything naming an object offers. */
internal const val OPEN_IN_NEW_TAB = "Open in a new tab"

/**
 * And what it offers beside it, everywhere: a `shark://` link to that same place, on the clipboard.
 *
 * "Copy link" rather than "Copy link to this object" or "…to this tab", because it is the same item on a
 * rectangle, a row, a field, a button and a tab, and a menu that renames it per surface reads as five
 * different things rather than as the one thing it is.
 */
internal const val COPY_LINK = "Copy link"
