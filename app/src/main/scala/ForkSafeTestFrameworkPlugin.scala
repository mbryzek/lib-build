import play.sbt.PlayScala
import sbt.*
import sbt.Keys.*

/** Wires up both halves of `scala-build-testkit`'s `testkit` package, each of which closes one way a
  * forked test run hangs a build's CI. Neither bug is ours and both are explained where they are fixed; this is only the
  * wiring.
  *
  *   - Suites run through `testkit.ForkSafeScalaTestFramework` instead of ScalaTest's own
  *     `Framework`, whose `done()` joins an acceptor with no timeout (ISS-1380).
  *   - Forked test JVMs run under the `testkit.ForkSafeExitAgent` java agent, which makes a fork
  *     that died before it could report anything exit non-zero instead of 0 — the exit status sbt
  *     needs in order not to join an acceptor nothing will ever connect to (ISS-3439).
  *
  * An AutoPlugin rather than a settings block each subproject opts into, for the same reason
  * [[TestDatabasePlugin]] is one: the failure of a subproject that forgets to opt in is invisible.
  * It is not a broken build, it is one subproject still able to hang the whole run — and nobody
  * discovers that until CI goes red on a green branch some week later. Triggering on every project
  * that requires `PlayScala`, which is every subproject in this build, makes the wiring
  * unforgettable, and the plugin adds `scala-build-testkit` to each one's Test classpath itself, at the
  * version of the release it came from ([[ScalaBuildVersion]]), so the framework the plugin names and
  * the jar it attaches as an agent are always the ones published beside it.
  */
object ForkSafeTestFrameworkPlugin extends AutoPlugin {

  override def trigger = allRequirements

  override def requires = PlayScala

  private val ForkSafeFrameworkClass = "testkit.ForkSafeScalaTestFramework"

  object autoImport {
    val checkForkSafeTestFramework: TaskKey[Unit] =
      taskKey[Unit]("Fail the test task if sbt did not load testkit.ForkSafeScalaTestFramework")

    /** `scala-build-testkit` at this plugin's own version: the fork-safe framework and agent, and the pin suites a
      * build declares a one-line subclass of. Without a configuration, so a build names it `% Test` as it needs.
      */
    val scalaBuildTestkit: ModuleID = "com.bryzek" %% ScalaBuildVersion.testkitName % ScalaBuildVersion.version
  }

  import autoImport.*

  override def projectSettings: Seq[Setting[?]] = Seq(
    libraryDependencies += scalaBuildTestkit % Test,
    // REPLACES ScalaTest in the list and leaves the rest of sbt's defaults alone. Nothing in this
    // repo uses JUnit, MUnit, Specs2 or ScalaCheck today, but dropping them here would mean a
    // future test written against one is silently never run, which is the same class of failure
    // this plugin exists to prevent.
    testFrameworks := TestFramework(ForkSafeFrameworkClass) +:
      testFrameworks.value.filterNot(_ == TestFrameworks.ScalaTest),

    // THE GUARD, and it is not ceremony. `loadedTestFrameworks` keeps only the frameworks whose
    // class it can actually instantiate off the test classpath, so a subproject that loses its
    // `testkit` dependency does not fail — it loads NO framework, discovers NO suites, and reports
    // a green build having run zero tests. That is the one outcome worse than the wedge, so it is
    // asserted rather than assumed, on every `test`.
    checkForkSafeTestFramework := {
      val loaded = (Test / loadedTestFrameworks).value.values.map(_.getClass.getName).toSeq
      if (!loaded.contains(ForkSafeFrameworkClass)) {
        sys.error(
          s"${thisProject.value.id}: sbt loaded ${
              if (loaded.isEmpty) "no test framework" else loaded.mkString(", ")
            }, " +
            s"not $ForkSafeFrameworkClass. Its suites would run unprotected against ISS-1380, or not run at all. " +
            s"The subproject needs `${ScalaBuildVersion.testkitName}` on its Test classpath, which this plugin adds; " +
            "a build that removes it from `libraryDependencies` removes the framework too."
        )
      }
    },
    // ONLY WHERE THERE IS A FORK. Every subproject this triggers on forks today — `PlayScala` sets
    // `Test / fork := true` and most of them reaffirm it — so the condition buys nothing right now
    // and is here for the day one of them stops: sbt ignores `Test / javaOptions` entirely when
    // `fork` is false and warns about every entry it had to drop, and an agent that cannot be
    // attached should not also be a warning nobody can act on.
    //
    // `scala-build-testkit`'s jar is the agent (its manifest names `Premain-Class`), so this is the
    // same artifact these subprojects already carry on their test classpath, named a second way.
    Test / javaOptions ++= {
      if ((Test / fork).value) Seq(s"-javaagent:${agentJar((Test / dependencyClasspath).value).getAbsolutePath}")
      else Nil
    },
    Test / test := (Test / test).dependsOn(checkForkSafeTestFramework).value,
  )

  /** The `scala-build-testkit` jar on a Test classpath. Refused when absent rather than skipped: a fork with no agent
    * is one that can exit 0 having reported nothing (ISS-3439).
    */
  def agentJar(classpath: Classpath): File =
    classpath
      .find(_.get(moduleID.key).exists(_.name.startsWith(ScalaBuildVersion.testkitName)))
      .map(_.data)
      .getOrElse(
        sys.error(
          s"No ${ScalaBuildVersion.testkitName} jar on the Test classpath, so the forked test JVM would run without " +
            "testkit.ForkSafeExitAgent (ISS-3439)."
        )
      )
}
