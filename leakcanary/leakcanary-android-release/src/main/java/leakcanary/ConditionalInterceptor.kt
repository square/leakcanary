package leakcanary

import leakcanary.HeapAnalysisInterceptor.Chain
import leakcanary.HeapAnalysisJob.Result

/**
 * An interceptor that runs only when [evaluateCondition] returns true.
 */
class ConditionalInterceptor(
  private val delegate: HeapAnalysisInterceptor,
  private val evaluateCondition: (HeapAnalysisJob) -> Boolean
) : HeapAnalysisInterceptor {
  override fun intercept(chain: Chain): Result {
    if (evaluateCondition(chain.job)) {
      return delegate.intercept(object : Chain {
        override val job = chain.job

        override fun proceed(): Result {
          return chain.proceed()
        }
      })
    } else {
      return chain.proceed()
    }
  }
}