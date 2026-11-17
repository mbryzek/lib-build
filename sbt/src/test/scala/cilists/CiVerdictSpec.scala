package cilists

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.must.Matchers

import CiVerdict.{Report, Verdict}

/** The finish of a split build, read off the reports every shard left. */
class CiVerdictSpec extends AnyFunSpec with Matchers {

  private def xml(spec: String, failures: Int, errors: Int = 0): String =
    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<testsuite errors="$errors" failures="$failures" name="$spec" tests="3" time="1.0">
       |</testsuite>
       |""".stripMargin

  describe("readReport") {

    it("reads the suite's name and whether it failed from its testsuite element") {
      CiVerdict.readReport("core", "TEST-core.ASpec.xml", xml("core.ASpec", 0)) mustBe Report(
        "core",
        "core.ASpec",
        false
      )
      CiVerdict.readReport("core", "TEST-core.ASpec.xml", xml("core.ASpec", 1)) mustBe Report(
        "core",
        "core.ASpec",
        true
      )
      CiVerdict.readReport("core", "TEST-core.ASpec.xml", xml("core.ASpec", 0, errors = 2)).failed mustBe true
    }

    it("reads a report cut off before its testsuite element as failed, named from its file") {
      CiVerdict.readReport("api", "FIRST-api.BSpec.xml", "<?xml") mustBe Report("api", "api.BSpec", true)
    }
  }

  describe("verdict") {

    it("is no verdict when there is no last run at all") {
      CiVerdict.verdict(Seq(Report("core", "core.ASpec", true)), Nil) mustBe Verdict(Nil, None)
    }

    it("is green when every last run passed, with a flake line for each suite a shard reran") {
      val firsts = Seq(Report("core", "core.ASpec", true))
      val tests = Seq(Report("core", "core.ASpec", false), Report("core", "core.BSpec", false))
      CiVerdict.verdict(firsts, tests) mustBe Verdict(Seq("ci-flake: core.ASpec"), Some(false))
    }

    it("is red for a rerun that failed or left no report, and for a suite that failed and was never rerun") {
      val firsts = Seq(Report("core", "core.ASpec", true), Report("api", "api.GoneSpec", true))
      val tests = Seq(Report("core", "core.ASpec", true), Report("api", "api.CSpec", true))
      CiVerdict.verdict(firsts, tests) mustBe Verdict(
        Seq("ci-rerun: failed again: core.ASpec", "ci-rerun: failed again: api.GoneSpec", "ci-failed: api.CSpec"),
        Some(true)
      )
    }

    it("matches a first run to the last run of the same subproject") {
      val firsts = Seq(Report("core", "x.SharedSpec", true))
      val tests = Seq(Report("api", "x.SharedSpec", false))
      CiVerdict.verdict(firsts, tests).lines mustBe Seq("ci-rerun: failed again: x.SharedSpec")
    }
  }

  describe("read") {

    def write(root: File, path: String, text: String): Unit = {
      val f = new File(root, path)
      f.getParentFile.mkdirs()
      Files.write(f.toPath, text.getBytes(StandardCharsets.UTF_8))
    }

    it("reads every subproject's test-reports directory under the root, and nothing else") {
      val root = Files.createTempDirectory("verdict").toFile
      write(root, "core/target/test-reports/FIRST-core.ASpec.xml", xml("core.ASpec", 1))
      write(root, "core/target/test-reports/TEST-core.ASpec.xml", xml("core.ASpec", 0))
      write(root, "api/target/test-reports/TEST-api.BSpec.xml", xml("api.BSpec", 2))
      write(root, "api/target/test-reports/PROOF-alone-01-api.BSpec.xml", xml("api.BSpec", 0))
      write(root, "target/test-reports/TEST-root.Spec.xml", xml("root.Spec", 1))
      CiVerdict.read(root) mustBe Verdict(Seq("ci-flake: core.ASpec", "ci-failed: api.BSpec"), Some(true))
    }

    it("is no verdict for a root with no reports") {
      CiVerdict.read(Files.createTempDirectory("verdict").toFile) mustBe Verdict(Nil, None)
    }
  }
}
