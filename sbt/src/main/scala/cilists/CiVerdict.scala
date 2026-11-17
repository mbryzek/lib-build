package cilists

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** THE FINISH OF A SPLIT BUILD (DES-25567, ISS-103214): the whole test phase's verdict, read off the
  * reports every shard left, with nothing left to rerun.
  *
  * Each shard ran its suites through `ciTest` and reran its own failures, and the runner gathers every
  * shard's `<subproject>/target/test-reports/` under one checkout. There `TEST-<suite>.xml` is each
  * suite's LAST run and `FIRST-<suite>.xml` the first run of one a shard reran. So:
  *
  *   - a suite with a `FIRST-` report whose last run passed is a flake, `ci-flake: <suite>`;
  *   - one whose last run failed, or that has no last run at all, failed twice,
  *     `ci-rerun: failed again: <suite>`, since its first run failed and nothing says the second
  *     passed;
  *   - any other suite whose last run failed is `ci-failed: <suite>`.
  *
  * No `TEST-` report at all is no verdict: a finish that measured nothing is never a verdict about the
  * tree, and the caller reports it as the machine's fault rather than as green or red.
  */
object CiVerdict {

  final case class Report(subproject: String, spec: String, failed: Boolean)

  /** `failed` is `None` when there was no report at all. */
  final case class Verdict(lines: Seq[String], failed: Option[Boolean])

  private val FirstPrefix = "FIRST-"
  private val TestPrefix = "TEST-"
  private val Testsuite = """<testsuite\b[^>]*>""".r
  private val Name = """\sname="([^"]+)"""".r

  private def count(tag: String, attribute: String): Int =
    (s"""\\s$attribute="(\\d+)"""").r.findFirstMatchIn(tag).map(_.group(1).toInt).getOrElse(0)

  /** One report, from the head of its XML. A report whose `<testsuite>` element cannot be read was cut
    * off while it was written, and reads as failed: it is the suite's only record.
    */
  def readReport(subproject: String, fileName: String, head: String): Report = {
    val fromName = fileName.stripSuffix(".xml").stripPrefix(FirstPrefix).stripPrefix(TestPrefix)
    Testsuite.findFirstIn(head) match {
      case None => Report(subproject, fromName, failed = true)
      case Some(tag) =>
        val spec = Name.findFirstMatchIn(tag).map(_.group(1)).getOrElse(fromName)
        Report(subproject, spec, failed = count(tag, "failures") > 0 || count(tag, "errors") > 0)
    }
  }

  /** The verdict of the first runs kept and the last runs left. */
  def verdict(firsts: Seq[Report], tests: Seq[Report]): Verdict =
    if (tests.isEmpty) Verdict(Nil, None)
    else {
      val reran = firsts.map { first =>
        val last = tests.find(t => t.subproject == first.subproject && t.spec == first.spec)
        first.spec -> !last.exists(!_.failed)
      }
      val failedAgain = reran.collect { case (spec, true) => spec }.toSet
      val failedOnce = tests.filter(t => t.failed && !failedAgain(t.spec)).map(_.spec).distinct
      val lines = reran.map {
        case (spec, false) => s"ci-flake: $spec"
        case (spec, true) => s"ci-rerun: failed again: $spec"
      } ++ failedOnce.map(spec => s"ci-failed: $spec")
      Verdict(lines, Some(failedAgain.nonEmpty || failedOnce.nonEmpty))
    }

  /** Every XML report in each `<subproject>/target/test-reports` under `root`, read into the verdict. */
  def read(root: File): Verdict = {
    val reports = Option(root.listFiles).toSeq.flatten.filter(_.isDirectory).sortBy(_.getName).flatMap { dir =>
      val reportDir = new File(dir, "target/test-reports")
      Option(reportDir.listFiles).toSeq.flatten
        .filter(f => f.isFile && f.getName.endsWith(".xml"))
        .sortBy(_.getName)
        .map { f =>
          (dir.getName, f)
        }
    }
    def load(prefix: String): Seq[Report] = reports.collect {
      case (subproject, f) if f.getName.startsWith(prefix) => readReport(subproject, f.getName, head(f))
    }
    verdict(load(FirstPrefix), load(TestPrefix))
  }

  private def head(f: File): String = {
    val bytes = Files.readAllBytes(f.toPath)
    new String(bytes.take(4096), StandardCharsets.UTF_8)
  }
}
