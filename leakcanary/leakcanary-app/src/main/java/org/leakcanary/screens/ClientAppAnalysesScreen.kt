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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.leakcanary.WhileSubscribedOrRetained
import org.leakcanary.data.HeapRepository
import org.leakcanary.screens.ClientAppAnalysesState.Loaded
import org.leakcanary.screens.ClientAppAnalysesState.Loading
import org.leakcanary.screens.ClientAppAnalysisItemData.Failure
import org.leakcanary.screens.ClientAppAnalysisItemData.Success
import org.leakcanary.screens.Destination.ClientAppAnalysesDestination
import org.leakcanary.screens.Destination.ClientAppAnalysisDestination
import org.leakcanary.util.TimeFormatter

sealed class ClientAppAnalysisItemData(val id: Long, val createdAtTimeMillis: Long) {
  class Success(id: Long, createdAtTimeMillis: Long, val leakCount: Int) :
    ClientAppAnalysisItemData(id, createdAtTimeMillis)

  class Failure(id: Long, createdAtTimeMillis: Long, val exceptionSummary: String) :
    ClientAppAnalysisItemData(id, createdAtTimeMillis)
}

sealed interface ClientAppAnalysesState {
  object Loading : ClientAppAnalysesState
  class Loaded(val analyses: List<ClientAppAnalysisItemData>) : ClientAppAnalysesState
}

@HiltViewModel
class ClientAppAnalysesViewModel @Inject constructor(
  private val repository: HeapRepository,
  private val navigator: Navigator
) : ViewModel() {

  // This flow is stopped when unsubscribed, so renavigating to the same
  // screen always polls the latest screen.
  val state = navigator.currentScreenState
    .filter { it.destination is ClientAppAnalysesDestination }
    .flatMapLatest { state ->
      stateStream((state.destination as ClientAppAnalysesDestination).packageName)
    }.stateIn(
      viewModelScope, started = WhileSubscribedOrRetained, initialValue = Loading
    )

  private fun stateStream(appPackageName: String) =
    repository.listAppAnalyses(appPackageName).map { app ->
      Loaded(app.map { row ->
        if (row.exception_summary == null) {
          Success(
            id = row.id,
            createdAtTimeMillis = row.created_at_time_millis,
            leakCount = row.leak_count
          )
        } else {
          Failure(
            id = row.id,
            createdAtTimeMillis = row.created_at_time_millis,
            exceptionSummary = row.exception_summary
          )
        }
      })
    }

  fun onAnalysisClicked(analysis: ClientAppAnalysisItemData) {
    // TODO Don't go here if failure, go to a failure screen instead.
    check(analysis is Success)
    navigator.goTo(ClientAppAnalysisDestination(analysis.id))
  }
}

@Composable fun ClientAppAnalysesScreen(viewModel: ClientAppAnalysesViewModel = viewModel()) {
  val stateProp by viewModel.state.collectAsState()

  when (val state = stateProp) {
    is Loading -> {
      Text("Loading...")
    }
    is Loaded -> {
      LazyColumn(
        modifier =
        Modifier
          .fillMaxHeight()
          .padding(horizontal = 8.dp)
      ) {
        item {
          Row(modifier = Modifier.padding(horizontal = 8.dp)) {
            // TODO This should be a primary button
            Button(
              onClick = {},
              modifier = Modifier.weight(1f)
            ) {
              Text("Import Heap Dump")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(modifier = Modifier.weight(1f), onClick = {}) {
              Text("Dump Heap Now")
            }
          }
        }

        if (state.analyses.isEmpty()) {
          item {
            Text("No analysis")
          }
        }
        items(state.analyses) { analysis ->
          ClientAppAnalysisItem(analysis, onClick = { viewModel.onAnalysisClicked(analysis) })
        }

      }
    }
  }
}

@Composable private fun ClientAppAnalysisItem(analysis: ClientAppAnalysisItemData, onClick: () -> Unit) {
  Column(
    Modifier
      // TODO Why is there no ripple?
      .clickable(onClick = onClick)
      .fillMaxWidth()
      .padding(vertical = 16.dp, horizontal = 8.dp)
  ) {
    val context = LocalContext.current
    val createdAt = TimeFormatter.formatTimestamp(context, analysis.createdAtTimeMillis)
    Text(
      text = createdAt,
      style = MaterialTheme.typography.headlineSmall,
      modifier = Modifier.padding(vertical = 4.dp)
    )
    val description =
      when (analysis) {
        is Failure -> analysis.exceptionSummary
        is Success -> "${analysis.leakCount} Distinct Leak" +
          if (analysis.leakCount == 1) "" else "s"
      }
    Text(
      text = description,
      style = MaterialTheme.typography.bodyMedium
    )
  }
}
