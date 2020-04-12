package leakcanary.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.detector.api.CURRENT_API

class ProductionUsageIssueRegistry : IssueRegistry() {
  override val issues get() = listOf(ISSUE_PROD_USAGE_PATTERN)

  override val api = CURRENT_API
}