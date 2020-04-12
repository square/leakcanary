package leakcanary.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

class ProductionUsageIssueRegistry(
  override val issues: List<Issue> = listOf(ISSUE_PROD_USAGE_PATTERN),
  override val api: Int = CURRENT_API
) : IssueRegistry()