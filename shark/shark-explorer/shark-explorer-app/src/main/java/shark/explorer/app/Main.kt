package shark.explorer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import shark.SharkLog
import shark.explorer.HeapSizes
import shark.explorer.ReachabilityStrength
import shark.explorer.formatByteSize
import shark.explorer.formatObjectCount

fun main(args: Array<String>) {
  // Launched from a terminal, so Shark's own diagnostics and any failure to open a heap dump belong
  // on stdout as well as in the window.
  SharkLog.logger = object : SharkLog.Logger {
    override fun d(message: String) = println(message)

    override fun d(
      throwable: Throwable,
      message: String
    ) {
      println(message)
      throwable.printStackTrace()
    }
  }
  explorerApplication(args)
}

private fun explorerApplication(args: Array<String>) = application {
  Window(
    onCloseRequest = ::exitApplication,
    title = "Shark Explorer",
    state = rememberWindowState(width = 1440.dp, height = 900.dp)
  ) {
    MaterialTheme {
      // A heap dump path on the command line opens straight away, which is how this is usually run.
      ExplorerApp(initialHeapDumpFile = args.firstOrNull()?.let(::File))
    }
  }
}

@Composable
fun ExplorerApp(
  initialHeapDumpFile: File? = null,
  /** Overridden by tests, which have no display to put a file dialog on. */
  chooseHeapDumpFile: () -> File? = ::showHeapDumpFileDialog
) {
  var requestedFile: File? by remember { mutableStateOf(initialHeapDumpFile) }
  var state: HeapDumpState by remember { mutableStateOf(HeapDumpState.None) }
  var shape: ViewShape by remember { mutableStateOf(ViewShape.TREEMAP) }
  var coloring: CellColoring by remember { mutableStateOf(CellColoring.DEFAULT) }

  LaunchedEffect(requestedFile) {
    val file = requestedFile
    if (file == null) {
      state = HeapDumpState.None
      return@LaunchedEffect
    }
    state = HeapDumpState.Opening(file, "Opening ${file.name}")
    state = try {
      val session = HeapDumpSession.open(file) { step ->
        state = HeapDumpState.Opening(file, step)
      }
      val sizes = session.read { it.sizes }
      HeapDumpState.Open(session, sizes).also {
        SharkLog.d { "${it.statusLine()} · ${sizes.strengthsText()}" }
      }
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Could not open $file" }
      HeapDumpState.Failed(file, throwable.toString())
    }
  }

  val currentState = state
  // Opening another heap dump replaces this state, which is when the previous one has to be closed.
  DisposableEffect(currentState) {
    onDispose { (currentState as? HeapDumpState.Open)?.session?.close() }
  }

  Column(Modifier.fillMaxSize()) {
    TopBar(
      state = currentState,
      shape = shape,
      coloring = coloring,
      onOpenClick = { chooseHeapDumpFile()?.let { requestedFile = it } },
      onColoringChange = { coloring = it },
      onShapeChange = { shape = it }
    )
    if (currentState is HeapDumpState.Open) {
      HeapDumpExplorer(currentState.session, shape, coloring, Modifier.weight(1f))
    } else {
      Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          if (currentState is HeapDumpState.Opening) {
            CircularProgressIndicator()
          }
          Text(currentState.centerMessage(), style = MaterialTheme.typography.bodyLarge)
        }
      }
    }
  }
}

/** Which heap dump the app has open, if any. */
private sealed interface HeapDumpState {

  object None : HeapDumpState

  /** [step] describes what opening the heap dump is currently doing, which takes a while. */
  data class Opening(
    val file: File,
    val step: String
  ) : HeapDumpState

  data class Failed(
    val file: File,
    val message: String
  ) : HeapDumpState

  data class Open(
    val session: HeapDumpSession,
    val sizes: HeapSizes
  ) : HeapDumpState
}

@Composable
private fun TopBar(
  state: HeapDumpState,
  shape: ViewShape,
  coloring: CellColoring,
  onOpenClick: () -> Unit,
  onColoringChange: (CellColoring) -> Unit,
  onShapeChange: (ViewShape) -> Unit
) {
  Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Button(onClick = onOpenClick) {
          Text(OPEN_HEAP_DUMP)
        }
        Text(state.statusLine(), style = MaterialTheme.typography.bodyMedium)
      }
      if (state is HeapDumpState.Open) {
        StrengthLegend(
          sizes = state.sizes,
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
        if (REFERENCE_STRENGTHS.none { state.sizes.byteCountByStrength.getValue(it) > 0L }) {
          Text(NOTHING_WEAKER, style = MaterialTheme.typography.bodySmall)
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

private fun HeapDumpState.statusLine(): String = when (this) {
  HeapDumpState.None -> ""
  is HeapDumpState.Opening -> step
  is HeapDumpState.Failed -> "Could not open ${file.name}"
  // The split between reachable and uncollected is the legend's job now, and it says it per strength.
  is HeapDumpState.Open -> "${session.heapDumpFile.name} · " +
    "${formatByteSize(sizes.totalByteCount)} in ${formatObjectCount(sizes.totalObjectCount)}"
}

/**
 * What the legend says, for the log: how a dump splits up by strength is the least predictable thing
 * about it, so a terminal run should say it rather than only the window.
 */
private fun HeapSizes.strengthsText(): String = ReachabilityStrength.values()
  .filter { byteCountByStrength.getValue(it) > 0L }
  .joinToString(", ") { strength ->
    "${formatByteSize(byteCountByStrength.getValue(strength))} ${strength.displayName.lowercase()}"
  }

private fun HeapDumpState.centerMessage(): String = when (this) {
  HeapDumpState.None -> NO_HEAP_DUMP
  is HeapDumpState.Opening -> step
  is HeapDumpState.Failed -> "${file.name} could not be opened.\n$message"
  is HeapDumpState.Open -> ""
}

/**
 * The platform file picker, through AWT: Compose Multiplatform has none of its own, and this is the
 * native dialog on macOS and Windows.
 */
private fun showHeapDumpFileDialog(): File? {
  val dialog = FileDialog(null as Frame?, "Open heap dump", FileDialog.LOAD)
  dialog.setFilenameFilter { _, name -> name.endsWith(".hprof") }
  dialog.isVisible = true
  val directory = dialog.directory
  val fileName = dialog.file
  return if (directory == null || fileName == null) null else File(directory, fileName)
}

internal const val OPEN_HEAP_DUMP = "Open heap dump…"
internal const val NO_HEAP_DUMP = "Open an Android heap dump to see what retains its memory."

/**
 * The strengths a `java.lang.ref.Reference` gives, which is what [NOTHING_WEAKER] is about. A cache, a
 * strong reference and uncollected garbage are none of them, and a heap dump wouldn't be odd for having
 * nothing at those.
 */
private val REFERENCE_STRENGTHS = ReachabilityStrength.values().toList() - setOf(
  ReachabilityStrength.STRONG,
  ReachabilityStrength.CACHE,
  ReachabilityStrength.UNREACHABLE
)

/**
 * Shown when every object a `java.lang.ref.Reference` points at is also reachable some stronger way,
 * which would otherwise read as a bug. Common but not a rule — see the notes on reachability.
 */
internal const val NOTHING_WEAKER =
  "Nothing in this heap dump is reachable only through a java.lang.ref.Reference. That's common, " +
    "because the garbage collection before a dump clears the references whose referent nothing else " +
    "was holding — but it isn't a given: a referent a thread got out of a reference and has since let " +
    "go of is weakly reachable again until the next collection. Unreachable is a different thing " +
    "again: objects nothing points at, which that collection didn't get to."
