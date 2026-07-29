package shark.explorer.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import shark.explorer.HeapObjectSummary
import shark.explorer.HeapTreemap
import shark.explorer.TreemapNavigation
import shark.explorer.formatByteSize

/** The treemap of one open heap dump, with the breadcrumbs and details panel around it. */
@Composable
fun HeapDumpExplorer(
  treemap: HeapTreemap,
  modifier: Modifier = Modifier
) {
  // Reset when a different heap dump is opened: object ids from the previous one mean nothing here.
  var navigation by remember(treemap) { mutableStateOf(TreemapNavigation(treemap.root)) }
  var selectedObjectId: Long? by remember(treemap) { mutableStateOf(null) }
  val selected = remember(treemap, selectedObjectId) {
    selectedObjectId?.let { treemap.summarize(it) }
  }

  Column(modifier) {
    Breadcrumbs(
      path = navigation.path,
      labelOf = treemap::label,
      retainedSizeOf = treemap::weight,
      onClick = { navigation = navigation.zoomInto(it) }
    )
    Row(Modifier.weight(1f)) {
      TreemapView(
        tree = treemap,
        root = navigation.current,
        labelOf = treemap::label,
        selected = selectedObjectId,
        onSelect = { selectedObjectId = it },
        onZoomInto = { navigation = navigation.zoomInto(it) },
        modifier = Modifier.weight(1f).fillMaxHeight()
      )
      DetailsPanel(
        summary = selected,
        onZoomInto = { navigation = navigation.zoomInto(it) },
        modifier = Modifier.width(DETAILS_WIDTH).fillMaxHeight()
      )
    }
  }
}

@Composable
private fun Breadcrumbs(
  path: List<Long>,
  labelOf: (Long) -> String,
  retainedSizeOf: (Long) -> Long,
  onClick: (Long) -> Unit
) {
  Row(
    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    path.forEachIndexed { index, node ->
      if (index > 0) {
        Text(BREADCRUMB_SEPARATOR, style = MaterialTheme.typography.bodyMedium)
      }
      val label = "${labelOf(node)} · ${formatByteSize(retainedSizeOf(node))}"
      if (index == path.lastIndex) {
        Text(
          label,
          Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Bold
        )
      } else {
        TextButton(onClick = { onClick(node) }) {
          Text(label, maxLines = 1)
        }
      }
    }
  }
}

@Composable
private fun DetailsPanel(
  summary: HeapObjectSummary?,
  onZoomInto: (Long) -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      if (summary == null) {
        Text(NO_SELECTION, style = MaterialTheme.typography.bodyMedium)
      } else {
        Text(
          summary.label,
          style = MaterialTheme.typography.titleMedium,
          overflow = TextOverflow.Ellipsis
        )
        Text(summary.className, style = MaterialTheme.typography.bodySmall)
        Detail("Retained", formatByteSize(summary.retainedSize))
        Detail("Retained objects", summary.retainedCount.toString())
        Detail("Shallow", formatByteSize(summary.shallowSize.toLong()))
        Detail("Dominates", "${summary.dominatedObjectCount} objects")
        summary.stringValue?.let { Detail("Value", "\"$it\"") }
        summary.inspectorLabels.forEach { label ->
          Text(label, style = MaterialTheme.typography.bodySmall)
        }
        Button(
          onClick = { onZoomInto(summary.objectId) },
          enabled = summary.dominatedObjectCount > 0
        ) {
          Text("Zoom in")
        }
      }
    }
  }
}

@Composable
private fun Detail(
  name: String,
  value: String
) {
  Column {
    Text(name, style = MaterialTheme.typography.labelSmall)
    Text(value, style = MaterialTheme.typography.bodyMedium)
  }
}

/** Shown by the details panel until something is selected. */
internal const val NO_SELECTION = "Click a rectangle to see what it retains."

internal const val BREADCRUMB_SEPARATOR = "›"

private val DETAILS_WIDTH = 320.dp
