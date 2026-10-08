package shark.dive.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import shark.SharkLog
import shark.dive.Verdict
import shark.dive.VerdictConflict
import shark.dive.VerdictOverride
import shark.dive.Topic
import shark.dive.text

/**
 * The verdict on the object the tab is on, and the pencil that overrules it.
 *
 * At the top of what the object is, under its name and above its size, because it is the conclusion the
 * rest of that panel is the evidence for. In the colours a path draws a verdict in — because it is the same
 * answer, and a reader who has learnt the green and the red on one surface reads them on the other.
 *
 * **Loud for the two verdicts that mean something and quiet for the third**: a heap dump is mostly objects
 * nothing knows either way about, so shouting `Unknown` on every object would be a line nobody reads by the
 * time it says something. Which is also why the reason is here rather than in a tooltip: the verdict is a
 * conclusion, and half the objects on a path are green or red because of what another object is.
 */
@Composable
internal fun VerdictDetail(
  objectVerdict: ObjectVerdict,
  /**
   * Whether the verdicts of this heap dump have been read off the disk, which is what makes changing one
   * safe. See [HeapDumpVerdicts.isRead].
   */
  isRead: Boolean,
  /** What went wrong reading or writing them, which is the one thing this window can say about it. */
  problem: String?,
  /** What the reason is read with, which makes the addresses and links in it lead somewhere. */
  noteReader: NoteReader,
  onChange: () -> Unit,
  modifier: Modifier = Modifier
) {
  val isKnown = objectVerdict.verdict != Verdict.UNKNOWN
  Column(modifier.fillMaxWidth()) {
    // Named the way every other line of this panel is, because one word over a verdict is what lets the
    // verdict itself be one word: a label nobody reads twice, on a line that repeats down a whole path.
    Text(VERDICT_LABEL, style = MaterialTheme.typography.labelSmall)
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Left of the answer rather than after it, because it is what changes that answer: reading the verdict
      // and reaching for the pencil is one movement, and a pencil at the end of a wrapping line is not.
      Hint(if (objectVerdict.setByHand == null) SET_VERDICT_HINT else CHANGE_VERDICT_HINT) {
        Text(
          EDIT_VERDICT_GLYPH,
          Modifier.clickableRow(enabled = isRead, onClick = onChange).padding(horizontal = 2.dp),
          style = MaterialTheme.typography.bodyMedium,
          color = if (isRead) LINK_COLOR else MaterialTheme.colorScheme.outline
        )
      }
      // The verdict behind its own shade, the way a step of a path is drawn, so that an object being
      // stuck is something you see before reading anything.
      Text(
        "${objectVerdict.verdict.glyph} ${objectVerdict.verdict.text}",
        Modifier.then(
          if (isKnown) {
            Modifier.background(objectVerdict.verdict.background!!, TARGET_SHAPE)
              .padding(horizontal = TARGET_PADDING, vertical = 1.dp)
          } else {
            Modifier
          }
        ),
        style = if (isKnown) {
          MaterialTheme.typography.bodyMedium
        } else {
          MaterialTheme.typography.bodySmall
        },
        color = objectVerdict.verdict.textColor,
        fontWeight = if (isKnown) FontWeight.Bold else FontWeight.Normal
      )
    }
    // Why, which is most of the answer: an object is red because of what it is, or because of what
    // something holding it is, and only the reason says which. On its own line and whole, because this
    // panel is a column narrow enough that any of these would wrap anyway.
    objectVerdict.reason?.let { reason ->
      ProseText(
        text = reason,
        reader = noteReader,
        style = MaterialTheme.typography.bodySmall,
        color = objectVerdict.verdict.textColor
      )
    }
    if (problem != null) {
      Text(
        problem,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error
      )
    }
  }
}

/**
 * A verdict being set by hand: what has been picked, why, and how far the flow has got.
 *
 * Held by [HeapDumpDive] against the tab it was started in rather than `remember`ed by the composable that
 * draws it, which is half of what makes this a dialog of the tab rather than of the window: switching tabs
 * leaves it where it is, and coming back finds the reason half typed exactly as it was left. The other half
 * is where [VerdictSetter] draws — inside the tab's panes, so the scrim stops at them.
 */
internal class SettingVerdict(
  /**
   * The object it is about, as it was when the pencil was pressed.
   *
   * Kept rather than read off the tab each time, because the tab is free to move while this is open: going
   * to look at a verdict this one disagrees with is the whole point, and what is being set has to stay the
   * thing that was asked about.
   */
  val objectVerdict: ObjectVerdict
) {
  var chosen: Verdict by mutableStateOf(objectVerdict.setByHand?.verdict ?: objectVerdict.verdict)
  var reason: String by mutableStateOf(objectVerdict.setByHand?.reason.orEmpty())
  var step: SetStep by mutableStateOf(SetStep.Choosing)

  /** What has been asked for, and null while nothing has: the ask is what the reading below follows. */
  var requested: VerdictOverride? by mutableStateOf(null)
}

/**
 * Sets the verdict of one object by hand, and settles what that disagrees with.
 *
 * Two steps, and the second one only when it has to be: a verdict, with the reason that makes it worth
 * keeping, and then whatever else was set by hand that it cannot be true alongside. The reader decides which
 * of the two readings to keep — nothing is flipped without being shown, and nothing is written until it is.
 *
 * **A dialog of the tab rather than of the window**, which is the whole of what it is doing by hand what an
 * `AlertDialog` would have done for it: the scrim covers the panes and stops at them, so the screen bar and
 * the tab strip above are still there to click. That is what the `?` on why two verdicts disagree needs —
 * every `?` in this window opens the reference in a tab, and a tab opened behind a window wide scrim is a tab
 * nobody can reach. Each verdict this one contradicts opens the object it is about for the same reason.
 * Switching tabs leaves this where it is, because [SettingVerdict] belongs to the tab and not to this
 * composable, so coming back finds the reason half typed exactly as it was left.
 *
 * Finding the disagreements is a walk up the heap dump's references, so it happens on the heap dump's thread
 * like everything else here and this waits for it. See [shark.dive.verdictConflictsWith].
 */
@Composable
internal fun VerdictSetter(
  setting: SettingVerdict,
  /** What the new verdict would disagree with, read off the heap dump. See [HeapDumpDive]. */
  onFindConflicts: suspend (VerdictOverride) -> List<VerdictConflict>,
  /** Sets it, along with whatever had to change for it to be true. */
  onSet: suspend (VerdictOverride, List<VerdictOverride>) -> Unit,
  /** Takes the verdict off the object, so that the heap dump says what it says about it again. */
  onClear: suspend () -> Unit,
  /** Where a verdict this one disagrees with leads: the object it is about, in a tab of its own. */
  onOpenObject: (Long, OpenIn) -> Unit,
  /** A link to that object, from the right click menu on the verdict. See [OpenTarget]. */
  onCopyLink: (Long) -> Unit,
  /** What the reasons shown here are read with, the one being overruled and the ones disagreeing. */
  noteReader: NoteReader,
  /** Where the `?` on what a disagreement is goes. See [Explain]. */
  onExplain: (Topic) -> Unit,
  /** Answered or abandoned, which is the same thing to whoever is holding this. */
  onDone: () -> Unit,
  modifier: Modifier = Modifier
) {
  val objectVerdict = setting.objectVerdict

  LaunchedEffect(setting, setting.requested) {
    val override = setting.requested ?: return@LaunchedEffect
    setting.step = SetStep.Checking
    val conflicts = onFindConflicts(override)
    setting.step = if (conflicts.isEmpty()) {
      // Nothing to settle, so the verdict someone typed is the whole of what they were asked for.
      SetStep.Writing(Decision.Set(override, emptyList()))
    } else {
      SetStep.Conflicts(override, conflicts)
    }
    setting.requested = null
  }

  // Every write goes through here, and closing this is the last thing it does: a save started from a button
  // and a dialog that goes away in the same breath is a save whose result nothing is left to keep.
  val writing = (setting.step as? SetStep.Writing)?.decision
  LaunchedEffect(setting, writing) {
    when (val decision = writing ?: return@LaunchedEffect) {
      is Decision.Set -> onSet(decision.override, decision.solved)
      Decision.Clear -> onClear()
    }
    onDone()
  }

  Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    // Its own box with nothing in it, so that the click it swallows is a click and not the semantics of
    // everything below merged into one node. It swallows rather than dismisses, which is the one way this
    // differs from an `AlertDialog`: a click landing outside is a click that missed, and treating it as an
    // answer would throw away a reason somebody was half way through typing.
    Box(
      Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null
        ) {}
    )
    Surface(
      Modifier.padding(16.dp).widthIn(min = DIALOG_MIN_WIDTH, max = DIALOG_WIDTH),
      shape = AlertDialogDefaults.shape,
      color = AlertDialogDefaults.containerColor,
      tonalElevation = AlertDialogDefaults.TonalElevation,
      shadowElevation = DIALOG_SHADOW
    ) {
      Column(
        Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        Text(settingVerdictTitle(objectVerdict.objectName), style = MaterialTheme.typography.headlineSmall)
        Column(
          Modifier.heightIn(max = DIALOG_MAX_HEIGHT).verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          when (val currentStep = setting.step) {
            SetStep.Choosing -> ChoosingVerdict(
              objectVerdict = objectVerdict,
              chosen = setting.chosen,
              reason = setting.reason,
              noteReader = noteReader,
              onChoose = { setting.chosen = it },
              onReason = { setting.reason = it }
            )
            SetStep.Checking -> Waiting(CHECKING_CONFLICTS)
            is SetStep.Conflicts -> Conflicts(
              requested = currentStep.requested,
              conflicts = currentStep.conflicts,
              noteReader = noteReader,
              onOpenObject = onOpenObject,
              onCopyLink = onCopyLink,
              onExplain = onExplain
            )
            is SetStep.Writing -> Waiting(WRITING_VERDICT)
          }
        }
        Row(
          Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)
        ) {
          SetterButtons(setting, onDone)
        }
      }
    }
  }
}

/**
 * What there is to press, in the order a dialog puts them: what leaves things as they were on the left,
 * what changes them on the right, and neither while a file is being written.
 */
@Composable
private fun SetterButtons(
  setting: SettingVerdict,
  onDone: () -> Unit
) {
  val objectVerdict = setting.objectVerdict
  // Only for a verdict a hand set: there is nothing to take off an object the inspectors alone spoke about,
  // and a button that says there is would read as a way to silence them.
  if (setting.step == SetStep.Choosing && objectVerdict.setByHand != null) {
    TextButton(onClick = { setting.step = SetStep.Writing(Decision.Clear) }) {
      Text(CLEAR_VERDICT)
    }
  }
  if (setting.step !is SetStep.Writing) {
    TextButton(
      onClick = {
        if (setting.step is SetStep.Conflicts) {
          // Which is a reader who read what they were about to overrule and decided not to, and is worth as
          // much in the log as the other choice.
          SharkLog.d {
            "Left the verdicts of this heap dump as they were rather than setting " +
              "${objectVerdict.objectName} to ${setting.chosen}"
          }
        }
        onDone()
      }
    ) {
      Text(if (setting.step is SetStep.Conflicts) UNDO_VERDICT else CANCEL_VERDICT)
    }
  }
  when (val currentStep = setting.step) {
    SetStep.Choosing -> TextButton(
      onClick = {
        setting.requested = VerdictOverride(
          objectId = objectVerdict.objectId,
          verdict = setting.chosen,
          reason = setting.reason.trim()
        )
      },
      // A verdict set by hand overrules the heap dump, so it is worth nothing to whoever reads it next
      // without the why — which is why this waits for one rather than filling one in.
      enabled = setting.reason.isNotBlank()
    ) {
      Text(SAVE_VERDICT)
    }
    is SetStep.Conflicts -> TextButton(
      onClick = {
        SharkLog.d {
          "Keeping ${currentStep.requested.verdict} for ${objectVerdict.objectName} and flipping the " +
            "${currentStep.conflicts.size} verdicts set by hand that disagreed with it"
        }
        setting.step = SetStep.Writing(
          Decision.Set(currentStep.requested, currentStep.conflicts.map { it.solved })
        )
      }
    ) {
      Text(SOLVE_CONFLICTS)
    }
    // Nothing to confirm while the heap dump is being read or written: both of them are already the answer
    // to a button someone pressed.
    SetStep.Checking, is SetStep.Writing -> Unit
  }
}

/** What the object is now, the three verdicts it could be, and the reason that has to come with a change. */
@Composable
private fun ChoosingVerdict(
  objectVerdict: ObjectVerdict,
  chosen: Verdict,
  reason: String,
  noteReader: NoteReader,
  onChoose: (Verdict) -> Unit,
  onReason: (String) -> Unit
) {
  // What it is now and why, so that overruling it is done while reading it rather than from memory.
  val now = "$NOW_LABEL ${objectVerdict.verdict.text}"
  val nowReason = objectVerdict.reason
  if (nowReason == null) {
    Text(now, style = MaterialTheme.typography.bodySmall, color = objectVerdict.verdict.textColor)
  } else {
    ProseText(
      text = nowReason,
      reader = noteReader,
      style = MaterialTheme.typography.bodySmall,
      color = objectVerdict.verdict.textColor,
      lead = "$now — "
    )
  }
  HorizontalDivider()
  Verdict.values().forEach { option ->
    Row(
      Modifier.fillMaxWidth().clickable { onChoose(option) },
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // No click of its own, so that the row is the one thing to press: a mark beside a line of text is
      // easier to hit as the line than as the mark.
      RadioButton(selected = option == chosen, onClick = null)
      Text(
        option.text,
        style = MaterialTheme.typography.bodyMedium,
        color = option.textColor,
        fontWeight = if (option == chosen) FontWeight.Bold else FontWeight.Normal
      )
    }
  }
  OutlinedTextField(
    value = reason,
    onValueChange = onReason,
    label = { Text(REASON_LABEL, style = MaterialTheme.typography.bodySmall) },
    placeholder = { Text(REASON_PLACEHOLDER, style = MaterialTheme.typography.bodySmall) },
    textStyle = MaterialTheme.typography.bodyMedium,
    modifier = Modifier.fillMaxWidth().height(REASON_HEIGHT)
      .semantics { contentDescription = REASON_DESCRIPTION }
  )
}

/**
 * Everything set by hand that the new verdict cannot be true alongside, and what keeping it would do to them.
 *
 * Every one of them rather than a count, with the reason each was given, because that reason is the case for
 * the other reading: whoever is about to overrule it is the one person who can weigh the two, and they can
 * only do that if they can read what they are overruling.
 *
 * Which is also why each one leads to the object it is about. A reason somebody typed is what they knew and
 * not what the heap dump says, so weighing it against this one is sometimes going and looking — and the
 * dialog belonging to its tab rather than to the window is what lets that happen without throwing away the
 * verdict half set. *Why* two verdicts can disagree at all is the `?`, for the same reason it is a `?`
 * everywhere else: it is one paragraph, read once, above something read every time.
 */
@Composable
private fun Conflicts(
  requested: VerdictOverride,
  conflicts: List<VerdictConflict>,
  noteReader: NoteReader,
  onOpenObject: (Long, OpenIn) -> Unit,
  onCopyLink: (Long) -> Unit,
  onExplain: (Topic) -> Unit
) {
  Explain(Topic.CONFLICTING_VERDICTS, onExplain) {
    Text(
      "${conflicts.size} ${if (conflicts.size == 1) CONFLICT_ONE else CONFLICT_MANY} " +
        "\"${requested.verdict.text}\".",
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Bold
    )
  }
  conflicts.forEach { conflict ->
    val objectId = conflict.existing.objectId
    val open: (OpenIn) -> Unit = { openIn -> onOpenObject(objectId, openIn) }
    OpenTarget(open, { onCopyLink(objectId) }) {
      Column(
        Modifier.fillMaxWidth()
          .openable(open)
          .padding(vertical = 4.dp)
      ) {
        Text(
          "${conflict.objectName} ${if (conflict.isAbove) CONFLICT_ABOVE else CONFLICT_BELOW}",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Bold,
          color = LINK_COLOR
        )
        ProseText(
          text = conflict.existing.reason,
          reader = noteReader,
          style = MaterialTheme.typography.bodySmall,
          color = conflict.existing.verdict.textColor,
          lead = "${conflict.existing.verdict.text}: "
        )
        Text(
          "$CONFLICT_BECOMES ${conflict.solved.verdict.text}",
          style = MaterialTheme.typography.bodySmall,
          color = conflict.solved.verdict.textColor
        )
      }
    }
  }
}

/**
 * Where the dialog is: choosing a verdict, waiting on the heap dump, or asking about what it disagrees with.
 *
 * Nothing has been written in any of the three. What is on disk changes once, when the last of them is
 * answered.
 */
internal sealed interface SetStep {

  object Choosing : SetStep

  object Checking : SetStep

  class Conflicts(
    /** What was asked for, which is what the conflicts are conflicts with. */
    val requested: VerdictOverride,
    val conflicts: List<VerdictConflict>
  ) : SetStep

  /** The one step that changes what is on disk, and the last: the dialog closes when it is done. */
  class Writing(val decision: Decision) : SetStep
}

/** What was decided, which is the whole of what a dialog full of choices comes down to. */
internal sealed interface Decision {

  class Set(
    val override: VerdictOverride,
    /** What had to be flipped for it to be true, which is empty unless something disagreed. */
    val solved: List<VerdictOverride>
  ) : Decision

  object Clear : Decision
}

/**
 * The verdict on the object a tab is on, from wherever the window knows it.
 *
 * Which is either of two reads, and they answer slightly different questions: the last step of the path from
 * a GC root, which is the verdict with the objects above and below it taken into account, or the object's own
 * if no path reaches it. See [shark.dive.HeapObjectSummary.verdict].
 */
internal class ObjectVerdict(
  val objectId: Long,
  /** What the object is called, for a dialog that has to name what is being changed. */
  val objectName: String,
  val verdict: Verdict,
  val reason: String?,
  /** What a hand set, and null for a verdict the heap dump alone decided. */
  val setByHand: VerdictOverride?
)

/** A mark beside the words, so that which verdict this is doesn't rest on the colour alone. */
private val Verdict.glyph: String
  get() = when (this) {
    Verdict.EXPECTED -> "✓"
    Verdict.UNKNOWN -> "?"
    Verdict.STUCK -> "✗"
  }

/**
 * What the panel calls the line, in one word like every other label in that panel.
 *
 * A verdict rather than a measurement, which is the difference between this line and the rest of them: a
 * number is read off the heap dump, and this is what somebody — an inspector or a reader — made of it. Which
 * is also what says the pencil beside it is allowed to disagree.
 */
internal const val VERDICT_LABEL = "Verdict"

/** What the dialog setting one is titled, which is the one thing on it that names what is being changed. */
internal fun settingVerdictTitle(objectName: String) = "Verdict on $objectName"

/** What opens the dialog that sets one, set or not: the same mark the app writes a note with. */
internal const val EDIT_VERDICT_GLYPH = "✎"

internal const val SAVE_VERDICT = "Set the verdict"
internal const val CANCEL_VERDICT = "Cancel"
internal const val CLEAR_VERDICT = "Take it off"

/** What keeping the new verdict and flipping everything that disagrees with it is called. */
internal const val SOLVE_CONFLICTS = "Keep this and flip those"

/** And what leaving the heap dump as it was is called, which is what the reader came in able to do. */
internal const val UNDO_VERDICT = "Undo"

internal const val CHECKING_CONFLICTS =
  "Looking for verdicts set by hand that this one could not be true alongside…"

private const val WRITING_VERDICT = "Keeping it…"

private const val NOW_LABEL = "Now:"

private const val SET_VERDICT_HINT =
  "Say whether this object is stuck, whatever the heap dump says. Kept between runs, and the reason with it."

private const val CHANGE_VERDICT_HINT = "Change or take off the verdict set by hand for this object."

private const val REASON_LABEL = "Why"

private const val REASON_PLACEHOLDER =
  "What you know that the heap dump doesn't: whoever reads this next has only this sentence to go on."

/** What the box is called, which is also how a test finds it: there is no other text field in the dialog. */
internal const val REASON_DESCRIPTION = "Why this object is being given that verdict."

private const val CONFLICT_ONE = "verdict set by hand cannot be true alongside"
private const val CONFLICT_MANY = "verdicts set by hand cannot be true alongside"

private const val CONFLICT_ABOVE = "holds it"
private const val CONFLICT_BELOW = "is held by it"

private const val CONFLICT_BECOMES = "Would become:"

/** Enough for the sentence someone is expected to type, and no more: the panes below keep the window. */
private val REASON_HEIGHT = 90.dp

/** What Material gives a dialog, since this is one drawn by hand: 280dp to 560dp wide, over a 32% scrim. */
private val DIALOG_MIN_WIDTH = 280.dp
private val DIALOG_WIDTH = 560.dp
private val DIALOG_SHADOW = 6.dp
private const val SCRIM_ALPHA = 0.32f
