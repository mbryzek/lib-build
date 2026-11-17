package cilists

import sbt.*
import sbt.Keys.*
import sbt.plugins.JvmPlugin

import scala.util.control.NonFatal

/** `ciTest [--stop] [<subproject>...] [-- <subproject>...]`: the subprojects' tests, then one rerun of
  * every spec that failed (ISS-19887, DES-18577 "Rerun"), phase by phase ([[CiTestPhases]], ISS-103214).
  * No subproject is every subproject. This is the whole of a Scala build's test phase: a build script
  * starts sbt once with it and reads its lines, and decides nothing about a rerun itself.
  *
  * A spec that fails and then passes its rerun is a FLAKE: one `ci-flake: <suite>` line, and it does
  * not fail the build. A spec that fails its rerun too prints `ci-rerun: failed again: <suite>` and
  * does. `ci-rerun: done` is printed once a rerun has run, whatever it found, and is what the fleet's
  * whole-build flake retry reads to stand down.
  *
  * A ROUND IS RERUN ONLY WHEN THE FAILING SPECS ARE THE WHOLE FAILURE. The suite runs as each
  * subproject's `Test / executeTests`, which answers per suite rather than throwing, so a failed
  * spec is named by sbt itself and never read out of a log. Anything that is not a spec — a compile
  * failure, a task that failed, a subproject whose result is red while no suite in it is — leaves
  * nothing a rerun can clear, and the build stays red without one.
  *
  * Each subproject's failed specs rerun as one `testOnly`, which runs the same `Tests.Setup` and the
  * same `Tests.Filter` as `test`. A green `testOnly` makes every spec in it a flake; a red one is
  * split by the reports the rerun wrote, in every report directory, and a spec whose report the
  * rerun did not write failed again, since nothing says it passed.
  *
  * BEFORE A RERUN, each spec's report is kept as `FIRST-<suite>.xml` beside it: the rerun rewrites
  * `TEST-<suite>.xml`, and the fleet's test ledger reads a flaked spec's first-run seconds, failed
  * tests and failure signature from the kept copy. A copy that fails costs the ledger that detail and
  * nothing else. The copy is kept beside EVERY `TEST-` report the first run wrote: the subproject's
  * `target/test-reports`, where sbt's own JUnit reporter writes, and each directory a ScalaTest `-u`
  * argument in `Test / testOptions` names, which is the one a build's `# ci-test-reports:` glob
  * declares. A `FIRST-` copy outside that glob is one the ledger never reads (ISS-59817).
  *
  * `ci-test-phase: <n>s` is the wall time of one phase's suite alone, before any rerun, a line per
  * phase: a repository's slow ceiling is a share of their sum, and a rerun is not the phase every pull
  * request waits on.
  *
  * A phase the build never started, because an earlier one went red under `--stop` or failed in a way
  * no rerun clears, is named on a `ci-stopped:` line.
  *
  * `ciVerdict` is the finish of a split build: each shard ran `ciTest`, and the verdict of the whole
  * test phase is read off the reports they left ([[CiVerdict]]).
  */
object CiTestRerunPlugin extends AutoPlugin {

  override def trigger = allRequirements

  override def requires = JvmPlugin

  object autoImport {
    val ciProjectTests: TaskKey[(String, Tests.Output)] =
      taskKey[(String, Tests.Output)]("This subproject's id and what its tests answered, suite by suite")
    val ciAllTests: TaskKey[Seq[(String, Tests.Output)]] =
      taskKey[Seq[(String, Tests.Output)]]("Every subproject's tests, by subproject id")
    val ciPhaseTests: TaskKey[Seq[(String, Tests.Output)]] =
      taskKey[Seq[(String, Tests.Output)]]("The tests of the subprojects one ciTest phase names, by subproject id")
  }

  import autoImport.*

  override def projectSettings: Seq[Setting[?]] = Seq(
    ciProjectTests := {
      val id = thisProject.value.id
      val output = (Test / executeTests).value
      val resultLogger = (Test / testResultLogger).value
      val log = streams.value.log
      // The summary `test` prints, without the exception `test` throws: the verdict is `ciTest`'s.
      try resultLogger.run(log, output, s"$id / Test / test")
      catch { case NonFatal(_) => () }
      id -> output
    }
  )

  private val everyProject = ScopeFilter(inAnyProject)

  override def globalSettings: Seq[Setting[?]] = Seq(
    ciAllTests := ciProjectTests.all(everyProject).value,
    ciPhaseTests := Def.taskDyn {
      val named = state.value.get(PhaseProjects).getOrElse(Nil).toSet
      val refs = loadedBuild.value.allProjectRefs.map(_._1).filter(ref => named(ref.project))
      ciProjectTests.all(ScopeFilter(inProjects(refs: _*)))
    }.value,
    commands ++= Seq(ciTest, ciVerdict),
  )

  /** The specs one subproject's run failed. `None` when the subproject is red and no suite in it
    * is, which is a failure no rerun can reach.
    */
  def failedSpecs(output: Tests.Output): Option[Seq[String]] = {
    val failed = output.events.toSeq.collect { case (spec, r) if r.result != TestResult.Passed => spec }.sorted
    if (output.overall != TestResult.Passed && failed.isEmpty) None else Some(failed)
  }

  /** Every directory a subproject's test run writes `TEST-<suite>.xml` into: `sbtReports`, sbt's
    * own JUnit reporter's, then each directory a ScalaTest `-u` argument names, a relative one
    * resolved against `workingDirectory`, the directory the tests run in. Distinct, in that order.
    */
  def reportDirectories(sbtReports: File, options: Seq[TestOption], workingDirectory: File): Seq[File] = {
    val scalaTest = options.flatMap {
      case Tests.Argument(framework, args) if framework.forall(_ == TestFrameworks.ScalaTest) =>
        args.sliding(2).collect { case Seq("-u", dir) => dir }.toSeq
      case _ => Nil
    }
    val declared = scalaTest.map { dir =>
      val f = new File(dir)
      if (f.isAbsolute) f else new File(workingDirectory, dir)
    }
    (sbtReports +: declared).map(f => f.toPath.toAbsolutePath.normalize.toFile).distinct
  }

  /** Whether a spec passed its rerun, from the reports the rerun wrote since `since`: at least one,
    * and every one of them green.
    */
  def rerunPassed(reports: Seq[File], since: Long): Boolean = {
    val fresh = reports.filter(r => r.isFile && r.lastModified() >= since - 1000)
    fresh.nonEmpty && fresh.forall(r => CiTestLists.reportPassed(IO.read(r)))
  }

  private def attempt[T](run: => (State, T)): Option[(State, T)] =
    try Some(run)
    catch { case NonFatal(_) => None }

  /** The subprojects one phase runs, handed from `ciTest` to `ciPhaseTests` on the state. */
  private val PhaseProjects = AttributeKey[Seq[String]]("ciPhaseProjects")

  private def ciTest: Command = Command.args("ciTest", CiTestPhases.Usage) { (state, args) =>
    CiTestPhases.parse(args) match {
      case Left(reason) =>
        println(s"ciTest: $reason")
        state.fail
      case Right(request) =>
        val defined = Project.extract(state).structure.allProjectRefs.map(_.project).distinct.sorted
        CiTestPhases.unknown(request, defined.toSet) match {
          case Seq() => runPhases(state, request, defined)
          case unknown =>
            println(s"ciTest: no subproject ${unknown.mkString(", ")} in this build")
            state.fail
        }
    }
  }

  private def runPhases(state: State, request: CiTestPhases.Request, defined: Seq[String]): State = {
    val phases = if (request.phases.isEmpty) Seq(defined) else request.phases
    val ((last, flakes), outcomes, stopped) =
      CiTestPhases.runAll((state, 0), phases, request.stop) { case ((current, count), phase) =>
        val (next, outcome, flaked) = runPhase(current, phase)
        ((next, count + flaked), outcome)
      }
    stopped.foreach(phase => println(CiTestPhases.stoppedLine(phase)))
    val rerun = outcomes.exists(o => o == CiTestPhases.Outcome.Flaked || o == CiTestPhases.Outcome.FailedTwice)
    if (rerun) println("ci-rerun: done")
    if (outcomes.exists(CiTestPhases.red)) last.fail
    else {
      if (rerun)
        println(s"sbt: green after rerunning every spec that failed ($flakes flake${if (flakes == 1) "" else "s"})")
      last
    }
  }

  /** One phase's tests, then one rerun of every spec that failed: how it ended, and how many flaked. */
  private def runPhase(state: State, projects: Seq[String]): (State, CiTestPhases.Outcome, Int) = {
    val started = System.nanoTime()
    attempt(Project.extract(state).runTask(Global / ciPhaseTests, state.put(PhaseProjects, projects))) match {
      case None =>
        println("ci-rerun: nothing to rerun — the suite did not run to a result, which is not a spec's failure")
        (state, CiTestPhases.Outcome.NotRerunnable, 0)
      case Some((next, outputs)) =>
        println(s"ci-test-phase: ${(System.nanoTime() - started) / 1000000000L}s")
        val byProject = outputs.sortBy(_._1).map { case (id, output) => id -> failedSpecs(output) }
        byProject.collectFirst { case (id, None) => id } match {
          case Some(id) =>
            println(s"ci-rerun: nothing to rerun — $id failed and no suite in it did")
            (next, CiTestPhases.Outcome.NotRerunnable, 0)
          case None =>
            val failing = byProject.collect { case (id, Some(specs)) if specs.nonEmpty => id -> specs }
            if (failing.isEmpty) (next, CiTestPhases.Outcome.Green, 0)
            else {
              val (after, answered) = rerunAll(next, failing)
              val flakes = answered.count(_._2)
              val outcome =
                if (answered.forall(_._2)) CiTestPhases.Outcome.Flaked else CiTestPhases.Outcome.FailedTwice
              (after, outcome, flakes)
            }
        }
    }
  }

  private def rerunAll(state: State, failing: Seq[(String, Seq[String])]): (State, Seq[(String, Boolean)]) = {
    val (last, outcomes) = failing.foldLeft((state, Seq.empty[(String, Boolean)])) {
      case ((current, acc), (project, specs)) =>
        val (next, passed) = rerun(current, project, specs)
        (next, acc ++ passed)
    }
    outcomes.foreach {
      case (spec, true) => println(s"ci-flake: $spec")
      case (spec, false) => println(s"ci-rerun: failed again: $spec")
    }
    (last, outcomes)
  }

  /** `ciVerdict`: the finish of a split build, read off every shard's reports ([[CiVerdict]]). Prints a
    * line per suite a shard reran or that failed, and fails when one failed; with no report at all it
    * prints `ci-verdict: no report` and fails, which the caller reports as the machine's fault.
    */
  private def ciVerdict: Command = Command.command("ciVerdict") { state =>
    val root = Project.extract(state).get(LocalRootProject / baseDirectory)
    val verdict = CiVerdict.read(root)
    verdict.lines.foreach(println)
    verdict.failed match {
      case None =>
        println(s"ci-verdict: no report under ${root.getPath}/*/target/test-reports")
        state.fail
      case Some(true) =>
        println("ci-verdict: failed")
        state.fail
      case Some(false) =>
        if (verdict.lines.nonEmpty) println("ci-rerun: done")
        println("ci-verdict: passed")
        state
    }
  }

  /** One subproject's failed specs, rerun once in one `testOnly`, each answered passed or not. */
  private def rerun(state: State, project: String, specs: Seq[String]): (State, Seq[(String, Boolean)]) = {
    val extracted = Project.extract(state)
    val ref = extracted.structure.allProjectRefs.find(_.project == project).getOrElse(sys.error(s"no project $project"))
    val forked = extracted.getOpt(ref / Test / fork).getOrElse(false)
    // A forked test JVM runs in the subproject's base directory, an unforked one in sbt's own.
    val workingDirectory = if (forked) extracted.get(ref / baseDirectory) else new File(sys.props("user.dir"))
    val dirs = reportDirectories(
      extracted.get(ref / target) / "test-reports",
      attempt(extracted.runTask(ref / Test / testOptions, state)).map(_._2).getOrElse(Nil),
      workingDirectory
    )
    specs.foreach { spec =>
      dirs.foreach { dir =>
        val report = dir / CiTestLists.reportName(spec)
        try { if (report.isFile) IO.copyFile(report, dir / s"FIRST-$spec.xml") }
        catch {
          case NonFatal(e) =>
            println(s"ci-rerun: the first run of $spec could not be kept in $dir: ${e.getMessage}")
        }
      }
    }
    println(s"ci-rerun: $project/testOnly ${specs.mkString(" ")}")
    val since = System.currentTimeMillis()
    attempt(extracted.runInputTask(ref / Test / testOnly, specs.mkString(" ", " ", ""), state)) match {
      case Some((next, _)) => (next, specs.map(_ -> true))
      case None =>
        val answered = specs.map(spec => spec -> rerunPassed(dirs.map(_ / CiTestLists.reportName(spec)), since))
        (state, answered)
    }
  }
}
