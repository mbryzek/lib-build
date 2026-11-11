package cilists

import cilists.CiTestLists.Lists
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.must.Matchers

/** The rules the build applies to the lists it was handed: the code `CiTestListsPlugin` filters
  * every test task with.
  */
class CiTestListsSpec extends AnyFunSpec with Matchers {

  private val text =
    """41
      |quarantined core.FlakySpec
      |slow api.SlowSpec
      |proof core.FixedSpec
      |""".stripMargin

  private val lists: Lists = CiTestLists.parse(text).fold(reason => fail(reason), identity)

  private def report(tests: Int, failures: Int, errors: Int): String =
    s"""<?xml version="1.0" encoding="UTF-8" ?>
       |<testsuite errors="$errors" failures="$failures" hostname="h" name="core.FixedSpec" tests="$tests" time="0.1">
       |  <testcase classname="core.FixedSpec" name="works" time="0.1"></testcase>
       |</testsuite>""".stripMargin

  describe("parse") {

    it("reads the version and the three kinds of line") {
      lists mustBe Lists("41", Set("core.FlakySpec"), Set("api.SlowSpec"), Set("core.FixedSpec"))
    }

    it("reads a version written as `version <n>`, a file of the version alone, and CRLF line ends") {
      CiTestLists.parse("version 7\r\nslow a.B\r\n") mustBe Right(Lists("7", Set.empty, Set("a.B"), Set.empty))
      CiTestLists.parse("3\n") mustBe Right(Lists("3", Set.empty, Set.empty, Set.empty))
    }

    it("refuses a file whose first line is not a version") {
      CiTestLists.parse("quarantined core.FlakySpec\n").isLeft mustBe true
      CiTestLists.parse("").isLeft mustBe true
    }

    it("refuses a line it does not understand, naming it") {
      CiTestLists.parse("41\nquarantine core.FlakySpec\n") mustBe
        Left("line 2 is neither quarantined, slow nor proof: quarantine core.FlakySpec")
      CiTestLists.parse("41\nslow\n").isLeft mustBe true
      CiTestLists.parse("41\nslow a.B c.D\n").isLeft mustBe true
    }
  }

  describe("runs") {

    it("leaves nothing out when there are no lists") {
      CiTestLists.runs(None, slowOnly = false, "core.FlakySpec") mustBe true
    }

    it("leaves out every quarantined and slow spec and runs the rest") {
      CiTestLists.runs(Some(lists), slowOnly = false, "core.FlakySpec") mustBe false
      CiTestLists.runs(Some(lists), slowOnly = false, "api.SlowSpec") mustBe false
      CiTestLists.runs(Some(lists), slowOnly = false, "core.OrdinarySpec") mustBe true
    }

    it("runs a proof spec in the build that proves it, whichever list also names it") {
      val both = lists.copy(quarantined = lists.quarantined + "core.FixedSpec", slow = lists.slow + "core.FixedSpec")
      CiTestLists.runs(Some(lists), slowOnly = false, "core.FixedSpec") mustBe true
      CiTestLists.runs(Some(both), slowOnly = false, "core.FixedSpec") mustBe true
    }

    it("runs only the slow specs when asked for them") {
      CiTestLists.runs(Some(lists), slowOnly = true, "api.SlowSpec") mustBe true
      CiTestLists.runs(Some(lists), slowOnly = true, "core.OrdinarySpec") mustBe false
      CiTestLists.runs(Some(lists), slowOnly = true, "core.FixedSpec") mustBe false
    }

    it("keeps a quarantined spec out of the slow run too") {
      val both = lists.copy(quarantined = lists.quarantined + "api.SlowSpec")
      CiTestLists.runs(Some(both), slowOnly = true, "api.SlowSpec") mustBe false
    }

    it("runs nothing when asked for the slow specs with no lists") {
      CiTestLists.runs(None, slowOnly = true, "api.SlowSpec") mustBe false
    }
  }

  describe("unknown") {

    it("names every listed spec no suite matches, of all three kinds, in order") {
      CiTestLists.unknown(lists, Set("api.SlowSpec", "core.OrdinarySpec")) mustBe Seq(
        "core.FixedSpec",
        "core.FlakySpec"
      )
      CiTestLists.unknown(lists, lists.listed) mustBe Nil
    }
  }

  describe("proofPlan") {

    it("pairs each proof spec with the subproject that defines it") {
      val defined = Map("api" -> Set("api.SlowSpec"), "core" -> Set("core.FixedSpec", "core.FlakySpec"))
      CiTestLists.proofPlan(lists, defined) mustBe Seq("core" -> "core.FixedSpec")
    }

    it("plans nothing for a proof spec no subproject defines") {
      CiTestLists.proofPlan(lists, Map("api" -> Set("api.SlowSpec"))) mustBe Nil
    }

    it("plans only the shard's own proof specs on one shard of a split build") {
      val defined = Map("core" -> Set("core.FixedSpec", "core.OtherFixedSpec"))
      val two = lists.copy(proof = Set("core.FixedSpec", "core.OtherFixedSpec"))
      CiTestLists.proofPlan(two, defined, Some(Set("core.OtherFixedSpec"))) mustBe Seq(
        "core" -> "core.OtherFixedSpec"
      )
      CiTestLists.proofPlan(two, defined, None) mustBe Seq("core" -> "core.FixedSpec", "core" -> "core.OtherFixedSpec")
    }
  }

  describe("shard suites") {

    it("reads one suite per line, skipping blank and comment lines") {
      CiTestLists.parseShardSuites("# shard 2 of 3\ncore.ASpec\n\n  api.BSpec  \n") mustBe Set(
        "core.ASpec",
        "api.BSpec"
      )
    }

    it("leaves a build whole when no shard file is named") {
      CiTestLists.shardSuites(Map.empty) mustBe None
      CiTestLists.shardSuites(Map(CiTestLists.ShardSuitesEnvVar -> "  ")) mustBe None
    }

    it("refuses a shard file that is not there rather than running every suite") {
      val thrown = intercept[RuntimeException] {
        CiTestLists.shardSuites(Map(CiTestLists.ShardSuitesEnvVar -> "/nonexistent/ci-shard-suites"))
      }
      thrown.getMessage must include("names no file")
    }
  }

  describe("proof runs") {

    it("names the run under way as its mode, index and count") {
      CiTestLists.proofRun("alone", 2, 5) mustBe "alone 2 5"
      CiTestLists.ProofRunProperty mustBe "ci.proof.run"
    }
  }

  describe("reports") {

    it("passes a run in which tests ran and none failed") {
      CiTestLists.reportPassed(report(tests = 3, failures = 0, errors = 0)) mustBe true
    }

    it("fails a run with a failure, an error, no test at all, or no testsuite") {
      CiTestLists.reportPassed(report(tests = 3, failures = 1, errors = 0)) mustBe false
      CiTestLists.reportPassed(report(tests = 3, failures = 0, errors = 1)) mustBe false
      CiTestLists.reportPassed(report(tests = 0, failures = 0, errors = 0)) mustBe false
      CiTestLists.reportPassed("") mustBe false
    }

    it("writes a failed report for a run that left none") {
      CiTestLists.reportPassed(CiTestLists.missingReport("core.FixedSpec")) mustBe false
      CiTestLists.missingReport("core.FixedSpec") must include("""name="core.FixedSpec"""")
    }

    it("names a proof run's report apart from the suite's own, by mode and run") {
      CiTestLists.reportName("core.FixedSpec") mustBe "TEST-core.FixedSpec.xml"
      CiTestLists.proofReportName("alone", 7, "core.FixedSpec") mustBe "PROOF-alone-07-core.FixedSpec.xml"
      CiTestLists.proofReportName("load", 20, "core.FixedSpec") mustBe "PROOF-load-20-core.FixedSpec.xml"
    }

    it("runs a proof 5 times alone and then 3 times beside its subproject") {
      CiTestLists.proofAttempts(CiTestLists.ProofRunsAlone, CiTestLists.ProofRunsBeside) mustBe Seq(
        "alone" -> 1,
        "alone" -> 2,
        "alone" -> 3,
        "alone" -> 4,
        "alone" -> 5,
        "load" -> 1,
        "load" -> 2,
        "load" -> 3,
      )
    }
  }
  describe("proveEvery") {
    it("proves every spec after a red one and names each red spec in plan order (ISS-79457)") {
      val plan = Seq("a.ASpec", "b.BSpec", "c.CSpec", "d.DSpec")
      val red = Set("a.ASpec", "c.CSpec")
      val (proved, failed) = CiTestLists.proveEvery(Vector.empty[String], plan) { (seen, spec) =>
        (seen :+ spec, !red(spec))
      }
      proved mustBe plan
      failed mustBe Seq("a.ASpec", "c.CSpec")
    }

    it("answers no failed spec when every proof passed") {
      CiTestLists.proveEvery(0, Seq("a.ASpec", "b.BSpec"))((n, _) => (n + 1, true)) mustBe ((2, Seq.empty))
    }
  }

  describe("proveRuns") {
    it("stops one spec's runs at its first failed run") {
      val attempts = CiTestLists.proofAttempts(5, 3)
      val (ran, passed) = CiTestLists.proveRuns(Vector.empty[(String, Int)], attempts) { (seen, attempt) =>
        (seen :+ attempt, attempt != ("alone" -> 2))
      }
      ran mustBe Seq("alone" -> 1, "alone" -> 2)
      passed mustBe false
    }

    it("runs every attempt when each passes") {
      CiTestLists.proveRuns(0, CiTestLists.proofAttempts(5, 3))((n, _) => (n + 1, true)) mustBe ((8, true))
    }
  }
}
