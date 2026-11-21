package cilists

import cilists.CiTestLists.Lists
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import sbt.io.IO
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

    import CiTestLists.Mode.{Build, GuardOnly, SlowOnly}

    it("leaves nothing out when there are no lists") {
      CiTestLists.runs(None, Build, "core.FlakySpec") mustBe true
    }

    it("leaves out every quarantined and slow spec and runs the rest") {
      CiTestLists.runs(Some(lists), Build, "core.FlakySpec") mustBe false
      CiTestLists.runs(Some(lists), Build, "api.SlowSpec") mustBe false
      CiTestLists.runs(Some(lists), Build, "core.OrdinarySpec") mustBe true
    }

    it("runs a proof spec in the build that proves it, whichever list also names it") {
      val both = lists.copy(quarantined = lists.quarantined + "core.FixedSpec", slow = lists.slow + "core.FixedSpec")
      CiTestLists.runs(Some(lists), Build, "core.FixedSpec") mustBe true
      CiTestLists.runs(Some(both), Build, "core.FixedSpec") mustBe true
    }

    it("runs only the slow specs when asked for them") {
      CiTestLists.runs(Some(lists), SlowOnly, "api.SlowSpec") mustBe true
      CiTestLists.runs(Some(lists), SlowOnly, "core.OrdinarySpec") mustBe false
      CiTestLists.runs(Some(lists), SlowOnly, "core.FixedSpec") mustBe false
    }

    it("keeps a quarantined spec out of the slow run too") {
      val both = lists.copy(quarantined = lists.quarantined + "api.SlowSpec")
      CiTestLists.runs(Some(both), SlowOnly, "api.SlowSpec") mustBe false
    }

    it("runs nothing when asked for the slow specs with no lists") {
      CiTestLists.runs(None, SlowOnly, "api.SlowSpec") mustBe false
    }

    describe("guards") {

      val guard = "core.helpers.SpecWaitBudgetGuardSpec"

      it("runs every guard in a local sbt test, which has no lists") {
        CiTestLists.runs(None, Build, guard) mustBe true
      }

      it("leaves every guard out of a build, whichever list names it") {
        CiTestLists.runs(Some(lists), Build, guard) mustBe false
        val named = lists.copy(proof = lists.proof + guard, slow = lists.slow + guard)
        CiTestLists.runs(Some(named), Build, guard) mustBe false
      }

      it("runs the guards and nothing else in a guard run, with lists or without") {
        Seq(None, Some(lists)).foreach { selected =>
          CiTestLists.runs(selected, GuardOnly, guard) mustBe true
          CiTestLists.runs(selected, GuardOnly, "core.OrdinarySpec") mustBe false
          CiTestLists.runs(selected, GuardOnly, "api.SlowSpec") mustBe false
          CiTestLists.runs(selected, GuardOnly, "core.FixedSpec") mustBe false
        }
      }

      it("never runs a guard in the slow run, even one the slow list names") {
        CiTestLists.runs(Some(lists.copy(slow = lists.slow + guard)), SlowOnly, guard) mustBe false
      }

      it("marks a guard by the GuardSpec suffix alone") {
        CiTestLists.isGuard(guard) mustBe true
        CiTestLists.isGuard("core.GuardSpecHelpersSpec") mustBe false
        CiTestLists.isGuard("core.GuardSpecs") mustBe false
      }
    }
  }

  describe("mode") {

    it("is a build with neither variable set, or with either set to anything but 1") {
      CiTestLists.mode(Map.empty) mustBe Right(CiTestLists.Mode.Build)
      CiTestLists.mode(Map("CI_SLOW_SPECS" -> "0", "CI_GUARD_SPECS" -> "")) mustBe Right(CiTestLists.Mode.Build)
    }

    it("is the slow run under CI_SLOW_SPECS=1 and the guard run under CI_GUARD_SPECS=1") {
      CiTestLists.mode(Map("CI_SLOW_SPECS" -> "1")) mustBe Right(CiTestLists.Mode.SlowOnly)
      CiTestLists.mode(Map("CI_GUARD_SPECS" -> "1")) mustBe Right(CiTestLists.Mode.GuardOnly)
    }

    it("refuses both at once") {
      CiTestLists.mode(Map("CI_SLOW_SPECS" -> "1", "CI_GUARD_SPECS" -> "1")).isLeft mustBe true
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

    it("never plans a guard, which no build runs") {
      val guarded = lists.copy(proof = lists.proof + "core.ShapeGuardSpec")
      val defined = Map("core" -> Set("core.FixedSpec", "core.ShapeGuardSpec"))
      CiTestLists.proofPlan(guarded, defined) mustBe Seq("core" -> "core.FixedSpec")
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

  describe("shard projects (ISS-80740)") {

    it("reads the subprojects separated by spaces or commas") {
      CiTestLists.shardProjects(Map(CiTestLists.ShardProjectsEnvVar -> " core api,jobs ")) mustBe Some(
        Set("core", "api", "jobs")
      )
    }

    it("reads every subproject when none is named") {
      CiTestLists.shardProjects(Map.empty) mustBe None
      CiTestLists.shardProjects(Map(CiTestLists.ShardProjectsEnvVar -> " , ")) mustBe None
    }
  }

  describe("unknownProof") {

    val two = lists.copy(proof = Set("core.FixedSpec", "api.FixedSpec"))

    it("names every proof spec no subproject read defines on a whole build") {
      CiTestLists.unknownProof(two, Set("core.FixedSpec"), None) mustBe Seq("api.FixedSpec")
    }

    it("names only the shard's own, so a proof spec another shard holds is not called unknown") {
      CiTestLists.unknownProof(two, Set("core.FixedSpec"), Some(Set("core.FixedSpec"))) mustBe Nil
      CiTestLists.unknownProof(two, Set.empty, Some(Set("core.FixedSpec"))) mustBe Seq("core.FixedSpec")
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

    it("names a clamped run's report by its own mode") {
      CiTestLists.proofReportName("clamped", 2, "core.FixedSpec") mustBe "PROOF-clamped-02-core.FixedSpec.xml"
    }

    it("runs the clamped runs after the alone runs and before the beside runs (ISS-96329)") {
      CiTestLists.proofAttempts(2, 1, 2) mustBe Seq(
        "alone" -> 1,
        "alone" -> 2,
        "clamped" -> 1,
        "clamped" -> 2,
        "load" -> 1,
      )
    }

    it("reads ciProof's counts: none is 5, 3 and 2 clamped, two run no clamped run") {
      CiTestLists.proofRuns(Nil) mustBe Right(CiTestLists.ProofRuns(alone = 5, beside = 3, clamped = 2))
      CiTestLists.proofRuns(Seq("2", "1")) mustBe Right(CiTestLists.ProofRuns(alone = 2, beside = 1, clamped = 0))
      CiTestLists.proofRuns(Seq("2", "1", "4")) mustBe Right(CiTestLists.ProofRuns(alone = 2, beside = 1, clamped = 4))
      Seq(Seq("2"), Seq("2", "x"), Seq("-1", "3"), Seq("1", "2", "3", "4")).foreach { args =>
        CiTestLists.proofRuns(args).isLeft mustBe true
      }
    }

    it("clamps with whichever tool the machine has, and with none when it has neither") {
      def on(files: String*) = CiTestLists.cpuClamp(files.toSet)
      on("/usr/sbin/taskpolicy") mustBe Some(Seq("/usr/sbin/taskpolicy", "-b"))
      on("/usr/bin/taskset") mustBe Some(Seq("/usr/bin/taskset", "-c", "0"))
      on("/bin/taskset") mustBe Some(Seq("/bin/taskset", "-c", "0"))
      on() mustBe None
    }

    it("starts the fork's java under the clamp, every argument passed through as one word") {
      // The clamp here is a script that prints each word it was started with, so the line it prints
      // is the clamp's own arguments, then java, then the fork's arguments.
      val dir = Files.createTempDirectory("clamped-java")
      try {
        val tool = dir.resolve("clamp")
        val java = dir.resolve("bin").resolve("java")
        Files.write(tool, "#!/bin/sh\nprintf '%s|' \"$@\"\n".getBytes(StandardCharsets.UTF_8))
        Files.createDirectories(java.getParent)
        Files.write(java, CiTestLists.clampedJava(Seq(tool.toString, "it's"), "/jdk home/bin/java").getBytes)
        tool.toFile.setExecutable(true) mustBe true
        java.toFile.setExecutable(true) mustBe true
        scala.sys.process.Process(Seq(java.toString, "-cp", "a b", "sbt.ForkMain")).!!.trim mustBe
          "it's|/jdk home/bin/java|-cp|a b|sbt.ForkMain|"
      } finally IO.delete(dir.toFile)
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
