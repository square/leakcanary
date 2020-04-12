package leakcanary.lint

import com.android.tools.lint.detector.api.CURRENT_API
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import org.junit.Test

class ProductionUsageIssueRegistryTest {

  @Test
  fun `production issue registry returns expected list`() {
    with(ProductionUsageIssueRegistry()) {
      assertTrue(issues.contains(ISSUE_PROD_USAGE_PATTERN))
    }
  }

  @Test
  fun `production registry returns correct API version`() {
    assertEquals(CURRENT_API, ProductionUsageIssueRegistry().api)
  }
}