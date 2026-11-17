package testkit

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.must.Matchers

class ShardReportSpec extends AnyFunSpec with Matchers {

  describe("format") {
    it("prints the shard, its wall and CPU seconds, and its resident megabytes") {
      ShardReport.format("integrations__3", 81.2044, 140.5, 1712L) mustBe
        "ci-shard: integrations__3 wall 81.204 cpu 140.500 rss_mb 1712"
    }

    it("says it could not read a figure rather than printing a number for it") {
      ShardReport.format("integrations__0", 1.0, -1.0, -1L) mustBe
        "ci-shard: integrations__0 wall 1.000 cpu ? rss_mb ?"
    }
  }

  describe("residentMegabytes") {
    it("reads this JVM's own resident set") {
      ShardReport.residentMegabytes(ProcessHandle.current().pid()) must be > 0L
    }
  }
}
