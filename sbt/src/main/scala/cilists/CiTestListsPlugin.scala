package cilists

import sbt.*
import sbt.Keys.*
import sbt.plugins.JvmPlugin

import scala.util.control.NonFatal

/** Makes every test task in this build run with the lists `CI_TEST_LISTS` names.
  *
  * Three things, all of them [[CiTestLists]] applied to sbt:
  *
  *   - a `Tests.Filter` on every subproject, so `test`, `testOnly` and `testQuick` leave out every
  *     quarantined and slow spec and every guard (a class ending in `GuardSpec`), leave out nothing
  *     when the variable is unset, with `CI_SLOW_SPECS=1` run the slow specs alone, and with
  *     `CI_GUARD_SPECS=1` run the guards alone ([[CiTestLists.runs]]);
  *   - `ciListsUnknown`, which prints `ci-lists: unknown <spec>` for a listed spec no subproject
  *     defines, and never fails, since the build script runs it ahead of `test` in one invocation;
  *   - `ciProof [<alone> <beside> [<clamped>]]`, which runs each `proof` spec `<alone>` times alone,
  *     `<clamped>` times alone with its forked test JVM under the machine's CPU clamp
  *     (`CiTestLists.cpuClamp`) and `<beside>` times with the rest of its subproject's specs, leaves
  *     a report per run, proves every spec even after one failed, and fails if any run failed. No
  *     arguments is 5, 3 and 2; two counts run no clamped run, and so does a subproject that does
  *     not fork its tests or a machine with no clamp, each saying so.
  *     Each run is named in the system property `CiTestLists.ProofRunProperty` while it runs, so a
  *     repository whose test tasks each open a fresh database can keep one across the alone runs.
  *     On a shard it reads the suites of the subprojects `CI_SHARD_PROJECTS` names alone, so it
  *     compiles nothing the shard's own suites did not.
  *
  * An AutoPlugin on every project, for the reason `TestDatabasePlugin` is one: a subproject added
  * next year must not be able to forget the filter, because forgetting it runs a quarantined spec.
  *
  * The two commands are commands and not tasks because a proof runs one test task many times, which
  * no task can do, and because both need every subproject's suites and say nothing about any one
  * project.
  */
object CiTestListsPlugin extends AutoPlugin {

  override def trigger = allRequirements

  override def requires = JvmPlugin

  object autoImport {
    val ciProjectSpecs: TaskKey[(String, Set[String])] =
      taskKey[(String, Set[String])]("This subproject's id and the suites its test task defines")
    val ciDefinedSpecs: TaskKey[Map[String, Set[String]]] =
      taskKey[Map[String, Set[String]]]("Every subproject's suites, by subproject id")
  }

  import autoImport.*

  /** Read once per load, and an environment asking for two runs at once fails the load. */
  private lazy val mode: CiTestLists.Mode =
    CiTestLists.mode(sys.env).fold(reason => sys.error(reason), identity)

  /** Read once per load, and a file that cannot be read fails the load: a build told to run with
    * lists it cannot read must not run without them.
    */
  private lazy val lists: Option[CiTestLists.Lists] = {
    val read = sys.env.get("CI_TEST_LISTS").filter(_.trim.nonEmpty).map { path =>
      CiTestLists.read(file(path)).fold(reason => sys.error(s"CI_TEST_LISTS=$path: $reason"), identity)
    }
    if (mode == CiTestLists.Mode.SlowOnly && read.isEmpty)
      sys.error("CI_SLOW_SPECS=1 runs only the slow specs and CI_TEST_LISTS names no lists")
    read
  }

  override def projectSettings: Seq[Setting[?]] = Seq(
    // Unscoped, matching `TestDatabasePlugin` and each subproject's own `testOptions ++=`, so the
    // filter reaches `test` and `testOnly` alike.
    testOptions += {
      val selected = lists
      val run = mode
      Tests.Filter(spec => CiTestLists.runs(selected, run, spec))
    },
    ciProjectSpecs := thisProject.value.id -> (Test / definedTestNames).value.toSet,
  )

  private val everyProject = ScopeFilter(inAnyProject)

  override def globalSettings: Seq[Setting[?]] = Seq(
    ciDefinedSpecs := ciProjectSpecs.all(everyProject).value.toMap,
    commands ++= Seq(ciListsUnknown, ciProof),
  )

  /** Runs a task and answers `None` when it failed. sbt has already logged why. */
  private def attempt[T](run: => (State, T)): Option[(State, T)] =
    try Some(run)
    catch { case NonFatal(_) => None }

  private def definedSpecs(state: State): Option[(State, Map[String, Set[String]])] =
    attempt(Project.extract(state).runTask(Global / ciDefinedSpecs, state))

  /** The suites `ciProof` reads: every subproject's, or on a shard that names its subprojects
    * ([[CiTestLists.ShardProjectsEnvVar]]) theirs alone. Reading a subproject's suites compiles its
    * tests, so reading every one on a shard compiles the whole build a second time, which a
    * developer machine's heap does not hold (ISS-80740). A named subproject this build does not
    * have fails the proof, naming it.
    */
  private def proofSpecs(state: State, projects: Option[Set[String]]): Option[(State, Map[String, Set[String]])] =
    projects match {
      case None => definedSpecs(state)
      case Some(ids) =>
        val refs = Project.extract(state).structure.allProjectRefs.filter(ref => ids(ref.project))
        val missing = ids -- refs.map(_.project)
        if (missing.nonEmpty) {
          println(
            s"ci-proof: ${CiTestLists.ShardProjectsEnvVar} names no subproject ${missing.toSeq.sorted.mkString(", ")}"
          )
          None
        } else
          refs.sortBy(_.project).foldLeft(Option((state, Map.empty[String, Set[String]]))) {
            case (Some((current, found)), ref) =>
              attempt(Project.extract(current).runTask(ref / ciProjectSpecs, current)).map { case (next, entry) =>
                (next, found + entry)
              }
            case (None, _) => None
          }
    }

  private def ciListsUnknown: Command = Command.command("ciListsUnknown") { state =>
    lists match {
      case None =>
        println("ci-lists: no lists to check")
        state
      case Some(l) =>
        // A test compile that fails here is left to the `test` after it, which fails on it and
        // reports it: this command runs ahead of the suite in the same invocation, and failing it
        // would stop sbt before the command whose failure says what broke.
        definedSpecs(state) match {
          case None =>
            println("ci-lists: could not resolve the defined specs")
            state
          case Some((next, defined)) =>
            CiTestLists.unknown(l, defined.values.flatten.toSet).foreach(spec => println(s"ci-lists: unknown $spec"))
            println(s"ci-lists: checked ${l.listed.size} listed spec(s)")
            next
        }
    }
  }

  private def ciProof: Command = Command.args("ciProof", "[<alone> <beside> [<clamped>]]") { (state, args) =>
    (lists.filter(_.proof.nonEmpty), CiTestLists.proofRuns(args)) match {
      case (_, Left(reason)) =>
        println(s"ci-proof: $reason")
        state.fail
      // The filter leaves every spec but the guards out of a guard run, and a guard is never proved.
      case (_, Right(_)) if mode == CiTestLists.Mode.GuardOnly =>
        println("ci-proof: a guard run proves nothing")
        state
      case (None, _) =>
        println("ci-proof: no proof specs")
        state
      case (Some(l), Right(runs)) =>
        val shard = CiTestLists.shardSuites(sys.env)
        proofSpecs(state, CiTestLists.shardProjects(sys.env)) match {
          case None => state.fail
          case Some((next, defined)) =>
            CiTestLists.unknownProof(l, defined.values.flatten.toSet, shard).foreach { spec =>
              println(s"ci-lists: unknown $spec")
            }
            proveAll(next, CiTestLists.proofPlan(l, defined, shard), runs)
        }
    }
  }

  /** Proves every spec of the plan, a red one included, and fails at the end when any failed:
    * [[CiTestLists.proveEvery]].
    */
  private def proveAll(state: State, plan: Seq[(String, String)], runs: CiTestLists.ProofRuns): State = {
    val clamp = if (runs.clamped > 0) CiTestLists.cpuClamp() else None
    if (runs.clamped > 0 && clamp.isEmpty)
      println("ci-proof: no CPU clamp (taskpolicy or taskset), so no clamped runs")
    // The clamp reaches a forked test JVM alone: a subproject that runs its tests inside sbt's own
    // JVM would clamp sbt itself, and every other subproject's work with it.
    val extracted = Project.extract(state)
    val unforked = plan.map(_._1).distinct.filterNot { project =>
      extracted.structure.allProjectRefs.find(_.project == project).forall(ref => extracted.get(ref / Test / fork))
    }
    if (clamp.isDefined)
      unforked.foreach(p => println(s"ci-proof: $p does not fork its tests, so no clamped runs there"))
    val (last, failed) = CiTestLists.proveEvery(state, plan) { case (current, (project, spec)) =>
      prove(current, project, spec, runs, clamp.filterNot(_ => unforked.contains(project)))
    }
    if (failed.nonEmpty) {
      println(s"ci-proof: FAILED ${failed.size} of ${plan.size} spec(s): ${failed.map(_._2).mkString(", ")}")
      last.fail
    } else {
      val clamped = if (clamp.isDefined && unforked.isEmpty) s", ${runs.clamped} clamped" else ""
      val counts = if (clamped.isEmpty) s"${runs.alone} alone and" else s"${runs.alone} alone$clamped and"
      println(s"ci-proof: passed ${plan.size} spec(s), $counts ${runs.beside} beside each")
      last
    }
  }

  /** The state a clamped run's test task runs in: `state` with the subproject's forked test JVM
    * started through [[CiTestLists.clampedJava]], written under the subproject's target. Appended
    * to the session, whose `set`s the clamped run must keep, and the state the run leaves is dropped
    * afterwards, so nothing else ever runs clamped.
    */
  private def clampedState(state: State, ref: ProjectRef, clamp: Seq[String]): State = {
    val extracted = Project.extract(state)
    val home = extracted.get(ref / target) / "ci-proof-clamp"
    val java = extracted.get(ref / Test / javaHome).getOrElse(file(sys.props("java.home"))) / "bin" / "java"
    val script = home / "bin" / "java"
    IO.write(script, CiTestLists.clampedJava(clamp, java.getAbsolutePath))
    val _ = script.setExecutable(true)
    extracted.appendWithSession(Seq(ref / Test / forkOptions ~= (_.withJavaHome(Some(home)))), state)
  }

  /** One spec's proof. The subproject's own reports are set aside for its duration and put back
    * after it, so what the build's suite run left is still what the ledger reads for every other
    * spec, and the proof's reports land beside them under their own names.
    */
  private def prove(
    state: State,
    project: String,
    spec: String,
    runs: CiTestLists.ProofRuns,
    clamp: Option[Seq[String]]
  ): (State, Boolean) = {
    val extracted = Project.extract(state)
    val ref = extracted.structure.allProjectRefs.find(_.project == project).getOrElse(sys.error(s"no project $project"))
    val reports = extracted.get(ref / target) / "test-reports"
    val aside = extracted.get(ref / target) / "test-reports.suite"
    val kept = extracted.get(ref / target) / "proof-reports"
    // `clamp` is None for a subproject that does not fork: `proveAll` says so.
    val clamped = clamp.fold(0)(_ => runs.clamped)
    val under = clamp.filter(_ => clamped > 0).fold("")(tool => s", $clamped alone under ${tool.mkString(" ")}")
    println(s"ci-proof: $spec in $project: ${runs.alone} alone$under, ${runs.beside} with the rest of $project's specs")
    lazy val clampedStart = clampedState(state, ref, clamp.getOrElse(Nil))

    def run(mode: String, index: Int, current: State): (State, Boolean) = {
      IO.delete(reports)
      val of = mode match {
        case "alone" => runs.alone
        case "clamped" => clamped
        case _ => runs.beside
      }
      // Read by the repository's own test machinery, never by this plugin: see `ProofRunProperty`.
      val _ = System.setProperty(CiTestLists.ProofRunProperty, CiTestLists.proofRun(mode, index, of))
      val next =
        try {
          (mode match {
            case "alone" => attempt(extracted.runInputTask(ref / Test / testOnly, s" $spec", current)).map(_._1)
            // The clamped state is dropped once the run ends, so the next run is not clamped.
            case "clamped" =>
              attempt(Project.extract(clampedStart).runInputTask(ref / Test / testOnly, s" $spec", clampedStart))
                .map(_ => current)
            case _ => attempt(extracted.runTask(ref / Test / test, current)).map(_._1)
          }).getOrElse(current)
        } finally {
          val _ = System.clearProperty(CiTestLists.ProofRunProperty)
        }
      val report = reports / CiTestLists.reportName(spec)
      val xml = if (report.isFile) IO.read(report) else CiTestLists.missingReport(spec)
      IO.write(kept / CiTestLists.proofReportName(mode, index, spec), xml)
      val passed = CiTestLists.reportPassed(xml)
      println(s"ci-proof: $spec $mode $index/$of ${if (passed) "passed" else "failed"}")
      (next, passed)
    }

    val attempts = CiTestLists.proofAttempts(runs.alone, runs.beside, clamped)
    IO.delete(Seq(aside, kept))
    if (reports.exists) IO.move(reports, aside)
    try {
      CiTestLists.proveRuns(state, attempts) { case (current, (mode, index)) => run(mode, index, current) }
    } finally {
      IO.delete(reports)
      if (aside.exists) IO.move(aside, reports)
      if (kept.exists) {
        IO.copyDirectory(kept, reports)
        IO.delete(kept)
      }
    }
  }
}
