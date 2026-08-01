package shark.explorer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import shark.explorer.HeapSizes
import shark.explorer.ReachabilityStrength
import shark.explorer.formatByteSize
import shark.explorer.formatObjectCount

/**
 * What the view below it draws and how it's coloured, at the top of the view and as wide as it, because
 * that's what it controls — and only there, so that a screen that isn't the treemap doesn't come with
 * controls for one.
 */
@Composable
internal fun ViewControls(
  sizes: HeapSizes,
  shape: ViewShape,
  coloring: CellColoring,
  onColoringChange: (CellColoring) -> Unit,
  onShapeChange: (ViewShape) -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
    Column(
      Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      StrengthLegend(
        sizes = sizes,
        coloring = coloring,
        onColoredStrengthsChange = { onColoringChange(coloring.copy(coloredStrengths = it)) }
      )
      Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        OptionPicker(
          label = "Shape",
          options = ViewShape.values().toList(),
          selected = shape,
          displayName = { it.displayName },
          onSelect = onShapeChange
        )
        OptionPicker(
          label = "Colours",
          options = CellColorScheme.values().toList(),
          selected = coloring.scheme,
          displayName = { it.displayName },
          onSelect = { onColoringChange(coloring.copy(scheme = it)) }
        )
      }
      if (REFERENCE_STRENGTHS.none { sizes.byteCountByStrength.getValue(it) > 0L }) {
        // The fact on one line, the paragraph saying why on hover: this sits above the map for as long as
        // the heap dump is open, and the reader who has read it once is looking at the map underneath.
        Hint(NOTHING_WEAKER_HINT) {
          Text(NOTHING_WEAKER, style = MaterialTheme.typography.bodySmall, color = MUTED_TEXT)
        }
      }
    }
  }
}

/** One of a handful of named options, as radio buttons: there are only a few and their names are short. */
@Composable
private fun <T> OptionPicker(
  label: String,
  options: List<T>,
  selected: T,
  displayName: (T) -> String,
  onSelect: (T) -> Unit
) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 4.dp))
    options.forEach { option ->
      Row(
        // The whole thing is one radio button, label included, so clicking the name works too.
        Modifier.selectable(
          selected = option == selected,
          role = Role.RadioButton,
          onClick = { onSelect(option) }
        ).padding(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        RadioButton(selected = option == selected, onClick = null)
        Text(displayName(option), style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

/**
 * How much of the heap dump is held how firmly, one row per strength, and a checkbox per row that turns
 * that strength's colour on and off.
 *
 * Everything is always drawn — the tree is the whole heap dump, garbage included — so a checkbox here
 * changes nothing but the colour scale: unchecked is grey. Which is what makes it worth having, and every
 * row worth pressing: greying the strong heap leaves the little there is of everything else lit up, and
 * greying the garbage leaves the reachable heap to read on its own.
 *
 * The rows add up to the whole dump, in bytes and in objects, which is the point of listing the ones that
 * are none of it too.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StrengthLegend(
  sizes: HeapSizes,
  coloring: CellColoring,
  onColoredStrengthsChange: (Set<ReachabilityStrength>) -> Unit
) {
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp)
  ) {
    Text(
      "Colour",
      style = MaterialTheme.typography.labelLarge,
      modifier = Modifier.padding(end = 4.dp)
    )
    ReachabilityStrength.values().forEach { strength ->
      val checked = strength in coloring.coloredStrengths
      Row(
        // The whole thing is one toggle, label included, so clicking the name works too.
        Modifier.toggleable(
          value = checked,
          role = Role.Checkbox,
          onValueChange = { isChecked ->
            onColoredStrengthsChange(
              if (isChecked) {
                coloring.coloredStrengths + strength
              } else {
                coloring.coloredStrengths - strength
              }
            )
          }
        ).padding(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Box(Modifier.size(SWATCH_SIZE).background(legendColor(coloring, strength)))
        Text(strength.legendText(sizes), style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

/** What one legend row says: how firmly, how many bytes, how many objects. */
private fun ReachabilityStrength.legendText(sizes: HeapSizes): String {
  val byteCount = formatByteSize(sizes.byteCountByStrength.getValue(this))
  val objectCount = formatObjectCount(sizes.objectCountByStrength.getValue(this))
  return "$displayName $byteCount · $objectCount"
}

/**
 * The strengths a `java.lang.ref.Reference` gives, which is what [NOTHING_WEAKER] is about. A strong
 * reference, a cache, a thread's own storage, a running method and uncollected garbage are none of them,
 * and a heap dump wouldn't be odd for having nothing at those.
 */
private val REFERENCE_STRENGTHS = ReachabilityStrength.values().toList() - setOf(
  ReachabilityStrength.STRONG,
  ReachabilityStrength.CACHE,
  ReachabilityStrength.THREAD_LOCAL,
  ReachabilityStrength.LOCAL,
  ReachabilityStrength.UNREACHABLE
)

/**
 * Shown when every object a `java.lang.ref.Reference` points at is also reachable some stronger way,
 * which is what the legend rows reading 0 B mean and would otherwise read as a bug.
 */
internal const val NOTHING_WEAKER =
  "Nothing in this heap dump is reachable only through a java.lang.ref.Reference."

/** Why that is normal, which is a paragraph, and a paragraph is more than the bar above a map can hold. */
internal const val NOTHING_WEAKER_HINT =
  "$NOTHING_WEAKER That's common, because the garbage collection before a dump clears the references " +
    "whose referent nothing else was holding — but it isn't a given: a referent a thread got out of a " +
    "reference and has since let go of is weakly reachable again until the next collection. Unreachable " +
    "is a different thing again: objects nothing points at, which that collection didn't get to."
