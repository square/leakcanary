package org.leakcanary.screens

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

sealed class Destination(val title: String) : Parcelable {

  @Parcelize
  object ClientAppsDestination : Destination("Apps")

  // TODO Figure out dynamic titles, this should say "X Heap Analyses"
  // Should also show the app name, icon..
  // Can use content for now.
  @Parcelize
  class ClientAppAnalysesDestination(val packageName: String) : Destination("Heap Analyses")

  @Parcelize
  class ClientAppAnalysisDestination(val analysisId: Long) : Destination("Analysis")

  @Parcelize
  object LeaksDestination : Destination("Leaks")

  @Parcelize
  class LeakDestination(
    val leakSignature: String,
    val selectedAnalysisId: Long? = null
  ) : Destination("Leak")
}
