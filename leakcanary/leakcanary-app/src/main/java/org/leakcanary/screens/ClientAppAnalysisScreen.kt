package org.leakcanary.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import org.leakcanary.WhileSubscribedOrRetained
import org.leakcanary.data.HeapRepository
import org.leakcanary.screens.ClientAppAnalysisState.Loading
import org.leakcanary.screens.ClientAppAnalysisState.Success
import org.leakcanary.screens.Destination.ClientAppAnalysisDestination
import org.leakcanary.screens.HeaderCardLink.EXPLORE_HPROF
import org.leakcanary.screens.HeaderCardLink.PRINT
import org.leakcanary.screens.HeaderCardLink.SHARE_ANALYSIS
import org.leakcanary.screens.HeaderCardLink.SHARE_HPROF
import org.leakcanary.util.LeakTraceWrapper
import org.leakcanary.util.Sharer
import shark.HeapAnalysisSuccess
import shark.Leak
import shark.LibraryLeak
import shark.SharkLog

data class AnalysisDetails(
  val analysis: HeapAnalysisSuccess,
  val leakReadStatusMap: Map<String, Boolean>
)

sealed interface ClientAppAnalysisState {
  object Loading : ClientAppAnalysisState
  class Success(val details: AnalysisDetails) : ClientAppAnalysisState
}

// TODO Surface in the UI which app we're in still.

@HiltViewModel
class ClientAppAnalysisViewModel @Inject constructor(
  private val repository: HeapRepository,
  private val navigator: Navigator,
  private val sharer: Sharer
) : ViewModel() {

  val state =
    navigator.filterDestination<ClientAppAnalysisDestination>()
      .flatMapLatest { destination ->
        stateStream(destination.analysisId)
      }.stateIn(
        viewModelScope, started = WhileSubscribedOrRetained, initialValue = Loading
      )

  private fun stateStream(analysisId: Long) = repository.getHeapAnalysis(analysisId)
    .combine(repository.getLeakReadStatuses(analysisId)) { analysis, leakReadStatusMap ->
      Success(AnalysisDetails(analysis as HeapAnalysisSuccess, leakReadStatusMap))
    }

  fun onHeaderCardLinkClicked(heapAnalysis: HeapAnalysisSuccess, link: HeaderCardLink) {
    when (link) {
      EXPLORE_HPROF -> TODO()
      SHARE_ANALYSIS -> {
        sharer.share(LeakTraceWrapper.wrap(heapAnalysis.toString(), 80))
      }
      PRINT -> {
        // TODO SharkLog will likely be disabled in release builds, we always want to print
        // here.
        SharkLog.d { "\u200B\n" + LeakTraceWrapper.wrap(heapAnalysis.toString(), 120) }
      }
      SHARE_HPROF -> TODO()
    }
  }

  fun onLeakClicked(leak: Leak) {
    val currentScreen =
      navigator.currentScreenState.value.destination as ClientAppAnalysisDestination
    navigator.goTo(Destination.LeakDestination(leak.signature, currentScreen.analysisId))
  }
}

enum class HeaderCardLink {
  EXPLORE_HPROF,
  SHARE_ANALYSIS,
  PRINT,
  SHARE_HPROF
}

@Composable fun ClientAppAnalysisScreen(viewModel: ClientAppAnalysisViewModel = viewModel()) {
  val stateProp by viewModel.state.collectAsState()

  when (val state = stateProp) {
    is Loading -> {
      Text("Loading...")
    }
    is Success -> {
      val heapAnalysis = state.details.analysis
      LazyColumn(
        modifier = Modifier
          .fillMaxHeight()
          .padding(horizontal = 8.dp)
      ) {
        item {
          Card {

            // TODO Query consuming app
            val heapDumpFileExist = false

            val annotatedString = buildAnnotatedString {
              if (heapDumpFileExist) {
                append("Explore ")
                appendLink("HeapDump", EXPLORE_HPROF)
                append("\n\n")
              }
              append("Share ")
              appendLink("Heap Dump analysis", SHARE_ANALYSIS)
              append("\n\n")
              append("Print analysis ")
              appendLink("to Logcat", PRINT)
              append(" (tag: LeakCanary)\n\n")
              if (heapDumpFileExist) {
                append("Share ")
                appendLink("Heap Dump file", SHARE_HPROF)
                append("\n\n")
              }
              // TODO this should be an expendable item row instead.
              /*
              val dumpDurationMillis =
              if (heapAnalysis.dumpDurationMillis != HeapAnalysis.DUMP_DURATION_UNKNOWN) {
                "${heapAnalysis.dumpDurationMillis} ms"
              } else {
                "Unknown"
              }

             val metadata = (heapAnalysis.metadata + mapOf(
              "Analysis duration" to "${heapAnalysis.analysisDurationMillis} ms",
              "Heap dump file path" to heapAnalysis.heapDumpFile.absolutePath,
              "Heap dump timestamp" to "${heapAnalysis.createdAtTimeMillis}",
              "Heap dump duration" to dumpDurationMillis
            ))
              .map { "<b>${it.key}:</b> ${it.value}" }
              .joinToString("<br>")
               */
              // append("See ")
              // appendLink("Metadata", SEE_METADATA)
            }

            ClickableText(text = annotatedString,
              style = MaterialTheme.typography.bodySmall,
              onClick = { offset ->
                val link = HeaderCardLink.valueOf(
                  annotatedString.getStringAnnotations(tag = "link", start = offset, end = offset)
                    .single().item
                )
                viewModel.onHeaderCardLinkClicked(heapAnalysis, link)
              })
          }
        }
        val leaks = heapAnalysis.allLeaks.sortedByDescending { it.leakTraces.size }.toList()
        item {
          // leak title
          val title = "${leaks.size} Distinct Leak" +
            if (leaks.size == 1) "" else "s"
          Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 16.dp, horizontal = 8.dp)
          )
        }
        items(leaks) { leak ->
          val isNew = !state.details.leakReadStatusMap.getValue(leak.signature)

          LeakItem(leak, isNew, onLeakClicked = {
            viewModel.onLeakClicked(leak)
          })
        }
      }
    }
  }
}

@Composable
private fun LeakItem(leak: Leak, isNew: Boolean, onLeakClicked: () -> Unit) {
  val count = leak.leakTraces.size
  val leakDescription = leak.shortDescription
  val isLibraryLeak = leak is LibraryLeak

  Row(
    modifier = Modifier
      .clickable(onClick = onLeakClicked)
      .padding(vertical = 16.dp, horizontal = 8.dp)
  ) {
    // TODO Nicer count
    Text("$count")
    Spacer(modifier = Modifier.width(16.dp))
    Column(
      Modifier.fillMaxWidth()
    ) {
      Text(
        text = leakDescription,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(vertical = 4.dp)
      )
      // TODO Add date for list of leaks
      // Text(
      //   text = formattedDate, style = MaterialTheme.typography.bodyMedium
      // )
      // TODO pills
      val pillsText =
        (if (isNew) "New " else "") + if (isLibraryLeak) "Library Leak" else ""
      Text(
        text = pillsText,
        style = MaterialTheme.typography.bodySmall
      )
    }
  }
}

@Composable private fun AnnotatedString.Builder.appendLink(
  text: String,
  headerCardLink: HeaderCardLink
) {
  pushStringAnnotation(tag = "link", annotation = headerCardLink.name)
  withStyle(style = SpanStyle(color = MaterialTheme.colorScheme.primary)) {
    append(text)
  }
  pop()
}
