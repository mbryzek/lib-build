package cilists

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.must.Matchers
import sbt.{TestFramework, TestFrameworks, Tests}

/** Where `ciTest` keeps a spec's first run and reads its rerun: beside every `TEST-` report the run
  * writes, the ScalaTest `-u` directory a build declares included (ISS-59817).
  */
class CiTestRerunPluginSpec extends AnyFunSpec with Matchers {

  private val root = new File("/build")
  private val sbtReports = new File("/build/core/target/test-reports")

  describe("reportDirectories") {

    it("is sbt's own report directory alone when no -u is declared") {
      CiTestRerunPlugin.reportDirectories(sbtReports, Seq(Tests.Argument("-oDF")), root) mustBe Seq(sbtReports)
    }

    it("adds the directory a ScalaTest -u names when it differs, as play-core's root target did") {
      val options = Seq(
        Tests.Argument("-oDF"),
        Tests.Argument(TestFrameworks.ScalaTest, "-u", "/build/target/test-reports")
      )
      CiTestRerunPlugin.reportDirectories(sbtReports, options, root) mustBe
        Seq(sbtReports, new File("/build/target/test-reports"))
    }

    it("names a directory once when -u is sbt's own, as platform and acumen declare it") {
      val options = Seq(Tests.Argument(TestFrameworks.ScalaTest, "-u", "/build/core/target/./test-reports"))
      CiTestRerunPlugin.reportDirectories(sbtReports, options, root) mustBe Seq(sbtReports)
    }

    it("resolves a relative -u against the directory the tests run in") {
      val options = Seq(Tests.Argument(None, List("-oD", "-u", "target/test-reports")))
      CiTestRerunPlugin.reportDirectories(sbtReports, options, root) mustBe
        Seq(sbtReports, new File("/build/target/test-reports"))
    }

    it("ignores a -u handed to another test framework") {
      val options = Seq(Tests.Argument(TestFrameworks.JUnit, "-u", "/elsewhere"))
      CiTestRerunPlugin.reportDirectories(sbtReports, options, root) mustBe Seq(sbtReports)
      val custom = Seq(Tests.Argument(new TestFramework("my.Framework"), "-u", "/elsewhere"))
      CiTestRerunPlugin.reportDirectories(sbtReports, custom, root) mustBe Seq(sbtReports)
    }
  }

  describe("rerunPassed") {

    def report(failures: Int): String =
      s"""<testsuite errors="0" failures="$failures" name="core.FixedSpec" tests="2" time="0.1"></testsuite>"""

    def write(dir: File, xml: String, modified: Long): File = {
      val f = new File(dir, "TEST-core.FixedSpec.xml")
      Files.write(f.toPath, xml.getBytes(StandardCharsets.UTF_8))
      f.setLastModified(modified)
      f
    }

    it("passes when every report the rerun wrote is green, in whichever directory it wrote one") {
      val a = Files.createTempDirectory("rerun").toFile
      val b = Files.createTempDirectory("rerun").toFile
      val since = System.currentTimeMillis()
      val green = write(a, report(0), since + 5000)
      CiTestRerunPlugin.rerunPassed(Seq(green, new File(b, "TEST-core.FixedSpec.xml")), since) mustBe true
    }

    it("fails when any fresh report is red, and when the rerun wrote none since it started") {
      val a = Files.createTempDirectory("rerun").toFile
      val b = Files.createTempDirectory("rerun").toFile
      val since = System.currentTimeMillis()
      val green = write(a, report(0), since + 5000)
      val red = write(b, report(1), since + 5000)
      CiTestRerunPlugin.rerunPassed(Seq(green, red), since) mustBe false
      val stale = write(a, report(0), since - 60000)
      CiTestRerunPlugin.rerunPassed(Seq(stale), since) mustBe false
    }
  }
}
