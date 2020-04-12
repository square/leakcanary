package leakcanary.lint

import com.android.tools.lint.detector.api.Category.Companion.CORRECTNESS
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.GradleContext
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity.ERROR
import leakcanary.lint.ProductionUsageDetector.Companion.DETAIL_MESSAGE
import leakcanary.lint.ProductionUsageDetector.Companion.ISSUE_ID
import leakcanary.lint.ProductionUsageDetector.Companion.MESSAGE

val ISSUE_PROD_USAGE_PATTERN = Issue.create(
    ISSUE_ID,
    MESSAGE,
    DETAIL_MESSAGE,
    CORRECTNESS, 10, ERROR,
    Implementation(ProductionUsageDetector::class.java, Scope.GRADLE_SCOPE)
)

class ProductionUsageDetector : Detector(), Detector.GradleScanner {

  companion object {
    const val BLOCK_NAME = "dependencies"
    const val DEPENDENCY_PREFIX = "com.squareup.leakcanary"
    const val DESIRED_ENVIRONMENT = "debug"
    const val MESSAGE = "LeakCanary should not be used in production."
    const val DETAIL_MESSAGE = "$MESSAGE Please use debugImplementation instead."
    const val ISSUE_ID = "ProductionUsagePattern"
  }

  override fun checkDslPropertyAssignment(
    context: GradleContext,
    property: String,
    value: String,
    parent: String,
    parentParent: String?,
    propertyCookie: Any,
    valueCookie: Any,
    statementCookie: Any
  ) {
    if (parent == BLOCK_NAME) {
      if (value.contains(DEPENDENCY_PREFIX) &&
          property.contains(DESIRED_ENVIRONMENT)
              .not()
      ) {
        context.report(
            ISSUE_PROD_USAGE_PATTERN, context.getLocation(propertyCookie), DETAIL_MESSAGE
        )
      }
    }
  }
}
