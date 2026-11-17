import sbt.*
import sbt.Keys.*

/** Opt-in Scala compiler profiling for every subproject.
  *
  * Set `PLATFORM_PROFILE_TRACE` to a directory and every `compile` in this build additionally
  * writes a Chrome trace of its own compiler run there, one file per subproject per configuration
  * (`<subproject>-compile.json`, `<subproject>-test.json`). Unset — which is every ordinary build,
  * every CI build and every merge-lane build — this plugin contributes no settings at all, so it
  * cannot cost, slow or invalidate a build that did not ask for it. `scripts/compile-profile` is
  * the reader.
  *
  * An environment variable rather than a task or a `set` on the command line, because the thing
  * being measured is `compile` itself: a task that turned profiling on would have to invalidate the
  * compile it is about to measure, and `set every scalacOptions += ...` cannot name the subproject
  * it is being applied to, so all fifteen would write one file and the last one to finish would be
  * the only one left.
  *
  * `-Yprofile-trace` is the only one of dotc's profiling options that attributes time to a SOURCE
  * FILE. `-Vprofile` reports tree sizes rather than time, and `-Yprofile-enabled` on its own
  * reports per-phase totals for the run as a whole — neither can answer "which files are
  * expensive", which is the question a compile-time investigation starts from. The trace carries
  * one duration event per phase per file, plus one per macro expansion and one per implicit
  * search, so the same file can be read as a total, as a phase breakdown, or as an expansion count.
  *
  * IT NEEDS THREE ARGUMENTS, NOT ONE. `-Yprofile-trace=<file>` is rejected as an unrecognised
  * option; the path is a separate argument, and `-Yprofile-enabled` has to be there too or dotc
  * refuses with `incomplete option -Yprofile-trace (requires -Yprofile-enabled)`. Under this
  * build's `-Werror` an unrecognised option is a compile error rather than a run that quietly
  * measures nothing, which is the one thing that makes getting this wrong cheap.
  *
  * `Test / scalacOptions` DELEGATES TO `Compile / scalacOptions`, because sbt's `Test`
  * configuration extends `Compile` — so a test compile inherits the compile trace's arguments and
  * dotc reports `Option -Yprofile-trace was updated`, again a `-Werror` failure. The filter below
  * is what drops them, and it has to run between the two contributions rather than after both.
  *
  * The trace is written when a compiler run ENDS, so a subproject zinc found nothing to recompile
  * in writes no file at all: there was no run. That is the correct reading of an incremental build
  * in which that subproject was untouched, and `scripts/compile-profile` reports the absence as
  * such rather than as a measurement failure.
  */
object CompileProfilePlugin extends AutoPlugin {

  override def trigger = allRequirements

  override def requires = plugins.JvmPlugin

  private val TraceDirEnvVar = "PLATFORM_PROFILE_TRACE"

  private def traceDir: Option[File] =
    sys.env.get(TraceDirEnvVar).map(_.trim).filter(_.nonEmpty).map(file(_).getAbsoluteFile)

  private def traceOptions(dir: File, project: String, configuration: String): Seq[String] = {
    IO.createDirectory(dir)
    Seq("-Yprofile-enabled", "-Yprofile-trace", (dir / s"$project-$configuration.json").getAbsolutePath)
  }

  private def isTraceOption(dir: File)(option: String): Boolean =
    option == "-Yprofile-enabled" ||
      option == "-Yprofile-trace" ||
      (option.startsWith(dir.getAbsolutePath) && option.endsWith(".json"))

  override def projectSettings: Seq[Setting[?]] = traceDir.toSeq.flatMap { dir =>
    Seq(
      Compile / scalacOptions ++= traceOptions(dir, name.value, "compile"),
      Test / scalacOptions ~= (_.filterNot(isTraceOption(dir))),
      Test / scalacOptions ++= traceOptions(dir, name.value, "test"),
    )
  }
}
