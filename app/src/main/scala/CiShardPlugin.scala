import java.nio.charset.StandardCharsets
import java.nio.file.Files

import sbt.*
import sbt.Keys.*
import sbt.plugins.JvmPlugin
import cilists.CiTestLists
import testshards.TestShards

import scala.util.control.NonFatal

/** A split build's two halves in sbt (design DES-25567): the plan the build splits by, and the
  * narrowing a shard's runner runs its share with.
  *
  * THE NARROWING. When `CI_SHARD_SUITES` names a file (`CiTestLists.ShardSuitesEnvVar` in scala-build), every test task
  * of every subproject runs the suites it names and no others: a `Tests.Filter` here, on every
  * project as `CiTestListsPlugin`'s is, so a subproject that is not a Play app is narrowed too, and
  * in [[TestDatabasePlugin]] before a sharded subproject places its suites in its own forks.
  *
  * THE PLAN. `ciShardPlan <file> [<subproject>...]`: whether this build runs its test phase itself
  * or splits it across runners, and how ("When a build splits"). A subproject may be named by
  * package, `integrations:mail`, to plan only its suites in that package ([[TestShards.scope]]):
  * a build's Prepare names the scope its change reaches (ISS-36226).
  *
  * `ci/build.slow.sh` runs it after the compile and the cheap checks, naming the subprojects its scope
  * tests (none for the full build). It reads the suites each one's test task WOULD run — every
  * suite it defines, narrowed by every `Tests.Filter` and `Tests.Exclude` on that task, so the test
  * lists and the class-level selection leave out here exactly what they leave out of `test` — and
  * hands them to [[TestShards.plan]], weighted by the shard timings: the fetched revision `CI_SHARD_TIMINGS` names,
  * else none, placing suites by hash ([[TestDatabasePlugin.readTimings]]).
  *
  * It prints the plan's `ci-shard-plan:` lines, and writes `<file>` ([[TestShards.render]]) only when
  * the plan is a split, so a file that exists is the whole of what says "split". It reads every
  * subproject's suites in one evaluation and keeps the named ones', so it compiles the test sources of
  * every subproject, which a build's `Test/compile` has just done, and runs no test.
  *
  * A command and not a task because it reads every named subproject's tasks and says nothing about
  * any one project, as `ciListsUnknown` and `ciImpactPlan` are. It fails when a subproject is
  * unknown or a test compile fails; `ci/build.slow.sh` reads either as "run the suite here".
  *
  * THE PART STARTS (design DES-94295, "How finely a subproject is cut"). Every forked shard of a sharded subproject
  * writes how long it took from its launch to its first suite, [[partStartFile]], beside the suite reports the shard
  * already leaves for its runner to upload: one `<subproject> <seconds>` line in
  * `<subproject>/target/test-reports/PART-START-<lane>.txt`. The runner sends them with the suites in the shard's
  * report, and the timings carry each subproject's median as its `start:<subproject>` line, which the plan weighs
  * every part with. The fork writes it (`testkit.PartStart`), because only the fork knows when it was launched; this
  * is where its path is decided, and `TestDatabasePlugin` hands it to each fork as [[PartStartEnvVar]].
  */
object CiShardPlugin extends AutoPlugin {

  override def trigger = allRequirements

  override def requires = JvmPlugin

  /** Names, in a forked shard's environment, the file its start seconds go to (`testkit.PartStart`). */
  val PartStartEnvVar: String = "DEV_TEST_PART_START"

  /** The file a fork of `lane` writes its start seconds to: beside the suite reports in `reports`, the
    * subproject's `target/test-reports`, named so no `TEST-*.xml` glob reads it as a suite's report.
    */
  def partStartFile(reports: File, lane: String): File = reports / s"PART-START-$lane.txt"

  /** One subproject's suites, as its test task would select them: None when it defines none. */
  private val ciShardProjectSuites =
    taskKey[Option[Seq[String]]]("The suites this subproject's test task would run, for ciShardPlan")

  /** Every subproject's suites, in ONE evaluation (ISS-44012). A `runTask` per subproject evaluated the
    * test compile of it and of everything it depends on each time, twice per subproject: 12 seconds of
    * a Prepare's plan over three subprojects that had just compiled.
    */
  private val ciShardAllSuites =
    taskKey[Map[String, Seq[String]]]("Each subproject's suites as its test task would select them")

  override def globalSettings: Seq[Setting[?]] = Seq(
    commands += ciShardPlan,
    ciShardAllSuites := {
      val ids = thisProjectRef.all(ScopeFilter(inAnyProject)).value.map(_.project)
      val suites = ciShardProjectSuites.all(ScopeFilter(inAnyProject)).value
      ids.zip(suites).collect { case (id, Some(names)) => id -> names }.toMap
    },
  )

  /** The suites this run is one runner's shard of, when `CI_SHARD_SUITES` names a file of them. Read
    * once per load, and a file that cannot be read fails the load: a shard told which suites to run
    * must not run every suite instead.
    */
  lazy val shardSuites: Option[Set[String]] = CiTestLists.shardSuites(sys.env)

  // Unscoped, matching `CiTestListsPlugin`'s filter, so it reaches `test` and `testOnly` alike.
  override def projectSettings: Seq[Setting[?]] = Seq(
    testOptions ++= shardSuites.toSeq.map(names => Tests.Filter(names)),
    ciShardProjectSuites := {
      val names = (Test / definedTestNames).value
      val options = (Test / test / testOptions).value
      if (names.isEmpty) None else Some(names.filter(runs(options)))
    },
  )

  private def ciShardPlan: Command = Command.args("ciShardPlan", "<file> [<subproject>[:<package>]...]") {
    (state, args) =>
      args.toList match {
        case Nil =>
          state.log.error("ciShardPlan needs the file to write the plan to")
          state.fail
        case path :: tokens =>
          val extracted = Project.extract(state)
          val refs = extracted.structure.allProjectRefs
          val scope = TestShards.scope(tokens)
          val unknown = scope.keys.toSeq.sorted.filterNot(n => refs.exists(_.project == n))
          if (unknown.nonEmpty) {
            state.log.error(s"ciShardPlan: no subproject ${unknown.mkString(", ")}")
            state.fail
          } else {
            val wanted = if (scope.isEmpty) refs else refs.filter(r => scope.contains(r.project))
            suites(state, extracted, wanted) match {
              case None => state.fail
              case Some((next, found)) =>
                val bySubproject = if (scope.isEmpty) found else TestShards.scoped(found, scope)
                val out = file(path)
                IO.delete(out)
                val timings = TestDatabasePlugin.readTimings()
                val plan = TestShards.plan(bySubproject, timings, TestShards.sizing(sys.env))
                plan match {
                  case split: TestShards.Split =>
                    Files.write(out.toPath, TestShards.render(split).getBytes(StandardCharsets.UTF_8))
                  case _: TestShards.Alone => ()
                }
                TestShards.summary(plan).foreach(println)
                next
            }
          }
      }
  }

  /** Each named subproject's suites, as its test task would select them. None when a task failed, which
    * sbt has already logged.
    */
  private def suites(
    state: State,
    extracted: Extracted,
    refs: Seq[ProjectRef],
  ): Option[(State, Map[String, Seq[String]])] =
    try {
      val wanted = refs.map(_.project).toSet
      val (next, all) = extracted.runTask(Global / ciShardAllSuites, state)
      Some(next -> all.filter { case (project, _) => wanted.contains(project) })
    } catch { case NonFatal(_) => None }

  /** Whether a test task given `options` runs the suite `name`: sbt runs a suite every filter
    * accepts and no exclusion names.
    */
  private def runs(options: Seq[TestOption])(name: String): Boolean =
    options.forall {
      case Tests.Filter(accepts) => accepts(name)
      case Tests.Exclude(names) => !names.exists(_ == name)
      case _ => true
    }
}
