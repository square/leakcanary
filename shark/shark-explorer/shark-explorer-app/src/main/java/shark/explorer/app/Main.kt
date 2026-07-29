package shark.explorer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
  var followedStrengths: Set<ReachabilityStrength> by remember { mutableStateOf(emptySet()) }
  var shape: ViewShape by remember { mutableStateOf(ViewShape.TREEMAP) }
  var scheme: CellColorScheme by remember { mutableStateOf(CellColorScheme.DAISY) }

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
        SharkLog.d { "${it.statusLine()} · ${sizes.weakerStrengthsText()}" }
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
      followedStrengths = followedStrengths,
      shape = shape,
      scheme = scheme,
      onOpenClick = { chooseHeapDumpFile()?.let { requestedFile = it } },
      onFollowedStrengthsChange = { followedStrengths = it },
      onShapeChange = { shape = it },
      onSchemeChange = { scheme = it }
    )
    if (currentState is HeapDumpState.Open) {
      HeapDumpExplorer(currentState.session, followedStrengths, shape, scheme, Modifier.weight(1f))
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
  followedStrengths: Set<ReachabilityStrength>,
  shape: ViewShape,
  scheme: CellColorScheme,
  onOpenClick: () -> Unit,
  onFollowedStrengthsChange: (Set<ReachabilityStrength>) -> Unit,
  onShapeChange: (ViewShape) -> Unit,
  onSchemeChange: (CellColorScheme) -> Unit
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
        StrengthCheckboxes(
          sizes = state.sizes,
          followedStrengths = followedStrengths,
          scheme = scheme,
          onFollowedStrengthsChange = onFollowedStrengthsChange
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
            selected = scheme,
            displayName = { it.displayName },
            onSelect = onSchemeChange
          )
        }
        if (state.sizes.byteCountByStrength.none { (strength, byteCount) ->
            strength != ReachabilityStrength.STRONG && byteCount > 0L
          }
        ) {
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
 * A checkbox per reachability strength beyond strong, all off to begin with: following one rebuilds the
 * dominator tree, and the strongly reachable heap is what you want to look at first.
 *
 * Each one says how many bytes are reachable at that strength and no better, and is disabled when that
 * is none — following it would then add nothing to the tree, and a checkbox that changes nothing is
 * worse than one you can't press.
 */
@Composable
private fun StrengthCheckboxes(
  sizes: HeapSizes,
  followedStrengths: Set<ReachabilityStrength>,
  scheme: CellColorScheme,
  onFollowedStrengthsChange: (Set<ReachabilityStrength>) -> Unit
) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      "Follow",
      style = MaterialTheme.typography.labelLarge,
      modifier = Modifier.padding(end = 4.dp)
    )
    ReachabilityStrength.values().forEach { strength ->
      // Strong references are always followed: without them there is no graph to walk.
      val isStrong = strength == ReachabilityStrength.STRONG
      val byteCount = sizes.byteCountByStrength.getValue(strength)
      val enabled = !isStrong && byteCount > 0L
      val checked = isStrong || strength in followedStrengths
      Row(
        // The whole thing is one toggle, label included, so clicking the name works too.
        Modifier.toggleable(
          value = checked,
          enabled = enabled,
          role = Role.Checkbox,
          onValueChange = { isChecked ->
            onFollowedStrengthsChange(
              if (isChecked) followedStrengths + strength else followedStrengths - strength
            )
          }
        ).padding(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = null)
        Box(Modifier.size(SWATCH_SIZE).background(legendColor(scheme, strength)))
        Text(
          "${strength.displayName} ${formatByteSize(byteCount)}",
          style = MaterialTheme.typography.bodySmall
        )
      }
    }
  }
}

private fun HeapDumpState.statusLine(): String = when (this) {
  HeapDumpState.None -> ""
  is HeapDumpState.Opening -> step
  is HeapDumpState.Failed -> "Could not open ${file.name}"
  is HeapDumpState.Open -> "${session.heapDumpFile.name} · " +
    "${formatByteSize(sizes.totalByteCount)} total · " +
    "${formatByteSize(sizes.reachableByteCount)} reachable · " +
    "${formatByteSize(sizes.unreachableByteCount)} uncollected garbage"
}

/**
 * What the checkboxes say, for the log: which strengths a dump has anything at all at is the least
 * predictable thing about it, so a terminal run should say it rather than only the window.
 */
private fun HeapSizes.weakerStrengthsText(): String {
  val weaker = ReachabilityStrength.values()
    .filter { it != ReachabilityStrength.STRONG && byteCountByStrength.getValue(it) > 0L }
  return if (weaker.isEmpty()) {
    "nothing reachable only through a java.lang.ref.Reference"
  } else {
    weaker.joinToString(", ") { strength ->
      "${formatByteSize(byteCountByStrength.getValue(strength))} ${strength.displayName.lowercase()}"
    }
  }
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
 * Shown when every object a `java.lang.ref.Reference` points at is also reachable some stronger way,
 * which would otherwise read as a bug. Common but not a rule — see the notes on reachability.
 */
internal const val NOTHING_WEAKER =
  "Nothing in this heap dump is reachable only through a java.lang.ref.Reference. That's common, " +
    "because the garbage collection before a dump clears the references whose referent nothing else " +
    "was holding — but it isn't a given: a referent a thread got out of a reference and has since let " +
    "go of is weakly reachable again until the next collection. The uncollected garbage above is " +
    "objects nothing referenced at all."
