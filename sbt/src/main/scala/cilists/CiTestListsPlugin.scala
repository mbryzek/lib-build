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
  *     quarantined and slow spec, leave out nothing when the variable is unset, and with
  *     `CI_SLOW_SPECS=1` run the slow specs alone;
  *   - `ciListsUnknown`, which prints `ci-lists: unknown <spec>` for a listed spec no subproject
  *     defines, and never fails, since the build script runs it ahead of `test` in one invocation;
  *   - `ciProof [<alone> <beside>]`, which runs each `proof` spec `<alone>` times alone and `<beside>` times with
  *     the rest of its subproject's specs, leaves a report per run, proves every spec even after one failed, and fails if any run failed.
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

  private val slowOnly: Boolean = sys.env.get("CI_SLOW_SPECS").contains("1")

  /** Read once per load, and a file that cannot be read fails the load: a build told to run with
    * lists it cannot read must not run without them.
    */
  private lazy val lists: Option[CiTestLists.Lists] = {
    val read = sys.env.get("CI_TEST_LISTS").filter(_.trim.nonEmpty).map { path =>
      CiTestLists.read(file(path)).fold(reason => sys.error(s"CI_TEST_LISTS=$path: $reason"), identity)
    }
    if (slowOnly && read.isEmpty) sys.error("CI_SLOW_SPECS=1 runs only the slow specs and CI_TEST_LISTS names no lists")
    read
  }

  override def projectSettings: Seq[Setting[?]] = Seq(
    // Unscoped, matching `TestDatabasePlugin` and each subproject's own `testOptions ++=`, so the
    // filter reaches `test` and `testOnly` alike.
    testOptions += {
      val selected = lists
      Tests.Filter(spec => CiTestLists.runs(selected, slowOnly, spec))
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

  private def ciProof: Command = Command.args("ciProof", "[<alone> <beside>]") { (state, args) =>
    val runs = args match {
      case Seq(alone, beside) => (alone.toInt, beside.toInt)
      case _ => (CiTestLists.ProofRunsAlone, CiTestLists.ProofRunsBeside)
    }
    lists.filter(_.proof.nonEmpty) match {
      case None =>
        println("ci-proof: no proof specs")
        state
      case Some(l) =>
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
  private def proveAll(state: State, plan: Seq[(String, String)], runs: (Int, Int)): State = {
    val (last, failed) = CiTestLists.proveEvery(state, plan) { case (current, (project, spec)) =>
      prove(current, project, spec, runs)
    }
    if (failed.nonEmpty) {
      println(s"ci-proof: FAILED ${failed.size} of ${plan.size} spec(s): ${failed.map(_._2).mkString(", ")}")
      last.fail
    } else {
      println(s"ci-proof: passed ${plan.size} spec(s), ${runs._1} alone and ${runs._2} beside each")
      last
    }
  }

  /** One spec's proof. The subproject's own reports are set aside for its duration and put back
    * after it, so what the build's suite run left is still what the ledger reads for every other
    * spec, and the proof's reports land beside them under their own names.
    */
  private def prove(state: State, project: String, spec: String, runs: (Int, Int)): (State, Boolean) = {
    val extracted = Project.extract(state)
    val ref = extracted.structure.allProjectRefs.find(_.project == project).getOrElse(sys.error(s"no project $project"))
    val reports = extracted.get(ref / target) / "test-reports"
    val aside = extracted.get(ref / target) / "test-reports.suite"
    val kept = extracted.get(ref / target) / "proof-reports"
    println(s"ci-proof: $spec in $project: ${runs._1} alone, ${runs._2} with the rest of $project's specs")

    def run(mode: String, index: Int, current: State): (State, Boolean) = {
      IO.delete(reports)
      val of = if (mode == "alone") runs._1 else runs._2
      // Read by the repository's own test machinery, never by this plugin: see `ProofRunProperty`.
      val _ = System.setProperty(CiTestLists.ProofRunProperty, CiTestLists.proofRun(mode, index, of))
      val next =
        try {
          (mode match {
            case "alone" => attempt(extracted.runInputTask(ref / Test / testOnly, s" $spec", current)).map(_._1)
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

    val attempts = CiTestLists.proofAttempts(runs._1, runs._2)
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
