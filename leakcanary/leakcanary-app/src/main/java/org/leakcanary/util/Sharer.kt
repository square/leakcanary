package org.leakcanary.util

import android.content.Intent
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import javax.inject.Inject

interface Sharer {
  fun share(content: String)
}

class ActivitySharer @Inject constructor(
  private val activityProvider: CurrentActivityProvider
) : Sharer {
  override fun share(content: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_TEXT, content)
    }

    activityProvider.withActivity {
      startActivity(
        Intent.createChooser(intent, "Share with…")
      )
    }
  }
}

@Module
@InstallIn(ActivityRetainedComponent::class)
interface SharerModule {
  @Binds fun bindSharer(sharer: ActivitySharer): Sharer
}
