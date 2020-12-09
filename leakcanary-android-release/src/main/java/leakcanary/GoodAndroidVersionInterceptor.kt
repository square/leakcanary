package leakcanary

import android.os.Build
import leakcanary.HeapAnalysisInterceptor.Chain

class GoodAndroidVersionInterceptor : HeapAnalysisInterceptor {
  private val errorMessage: String? by lazy {
    val sdkInt = Build.VERSION.SDK_INT
    if (// findObjectById() sometimes failing. See #1759
      sdkInt != 23 &&
      // findObjectById() sometimes failing. See #1759
      sdkInt != 25 &&
      // Android 11 seem to sometimes have super slow heap dumps.
      // See https://issuetracker.google.com/issues/168634429
      sdkInt < 30
    ) {
      null
    } else {
      "Build.VERSION.SDK_INT $sdkInt not supported"
    }
  }

  override fun intercept(chain: Chain): HeapAnalysisJob.Result {
    errorMessage?.let {
      chain.job.cancel(it)
    }
    return chain.proceed()
  }
}