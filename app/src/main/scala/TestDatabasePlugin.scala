import java.nio.charset.StandardCharsets
import java.nio.file.Files

import play.sbt.PlayScala
import sbt.*
import sbt.Keys.*
import cilists.CiTestLists
import testshards.{LaneClone, LastGroup, TestShards}

/** Points every subproject's forked test JVM at its OWN database, which the fork clones as it
  * starts, and drops it once the subproject's last fork has exited.
  *
  * An AutoPlugin rather than a settings block each subproject opts into, because opting in is the
  * kind of thing a subproject added next year forgets — and a subproject that forgets is one that
  * runs CONCURRENTLY (see `concurrentRestrictions` in `build.sbt`) against the shared session
  * database, which is ISS-167 and does not present as a broken build, it presents as somebody
  * else's spec failing intermittently three weeks later. Triggering on every project that requires
  * `PlayScala` — which is every subproject here — makes the wiring unforgettable.
  *
  * `envVars` decides which database the forked JVM opens and which it clones that one from
  * (`testkit.ShardDatabase` makes it exist); `testOptions` refuses a test task that does not fork
  * and drops the lanes afterwards. See [[TestDatabases]] for why, and for when this is a no-op.
  *
  * Every subproject that forks runs in shards (design DES-18577, "Test isolation"): its suites are
  * split into [[TestShards.shardsFor]] groups, each a forked JVM with its own database
  * `<session>__<subproject>__<n>`, running one suite at a time. See [[TestShards]] for how a suite is
  * placed. Shards need the same thing per-subproject databases need — a session database this build
  * may clone — so without one the subproject runs in one fork, as it does interactively.
  */
object TestDatabasePlugin extends AutoPlugin {

  override def trigger = allRequirements

  /** Every subproject in this build enables `PlayScala`, and the only thing here that does not is
    * the ROOT project sbt generates to aggregate them. That one has no test sources and does not
    * fork, so giving it a database is waste and asking it to fork is nonsense — requiring
    * `PlayScala` draws the line exactly where the subprojects are, declaratively, instead of
    * special-casing a name.
    */
  override def requires = PlayScala

  object autoImport {
    val testShards: SettingKey[Boolean] =
      settingKey[Boolean]("Run this subproject's suites in shards: one fork, one database, one suite at a time each")
  }

  import autoImport.*

  /** Carried by every shard's test group, so `concurrentRestrictions` in build.sbt can let a
    * subproject's shards run side by side while still limiting how many OTHER forked test groups
    * overlap them.
    */
  val ShardTag: Tags.Tag = Tags.Tag("test-shard")

  /** How many shards a sharded subproject gets in this build. Read once: the runner exports it to
    * the build, and it does not change underneath one.
    */
  lazy val shardCount: Int = TestShards.count(sys.env, logicalCores)

  /** The most shards this build runs at once while the machine has room ([[TestShards.ceiling]]).
    * Printed once, so a build log says what it was allowed.
    */
  lazy val shardCeiling: Int = {
    val ceiling = TestShards.ceiling(sys.env, logicalCores, shardCount)
    println(
      s"ci-shards: floor $shardCount, ceiling $ceiling while the one-minute load is under " +
        s"${logicalCores * TestShards.LoadShare}"
    )
    ceiling
  }

  private def logicalCores: Int = java.lang.Runtime.getRuntime.availableProcessors

  /** Whether one more shard may start, `running` counting it: what `concurrentRestrictions` in
    * build.sbt asks, every time it considers starting one ([[TestShards.admit]]).
    */
  def admitShard(running: Int): Boolean =
    running <= shardCount || TestShards.admit(
      running,
      shardCount,
      shardCeiling,
      java.lang.management.ManagementFactory.getOperatingSystemMXBean.getSystemLoadAverage,
      logicalCores
    )

  /** Names the shard in its own JVM, which `testkit.ShardReport` prints its cost under. */
  val ShardEnvVar: String = "DEV_TEST_SHARD"

  private def sharded(enabled: Boolean): Boolean = enabled && TestDatabases.enabled

  /** The suites a test task runs: every one it defines, or on a shard of a split build the ones
    * the shard names. Narrowed BEFORE the shards inside this build are placed, so a runner spreads
    * its share over its own forks and clones no database for a suite another runner holds.
    */
  private def selected(tests: Seq[TestDefinition]): Seq[TestDefinition] =
    CiShardPlugin.shardSuites.fold(tests)(names => tests.filter(t => names(t.name)))

  /** The shards that hold at least one suite, each with the suites placed on it. A subproject with
    * fewer suites than the build has shards gets one shard per suite, and no database is cloned and
    * no JVM forked for a shard with nothing to run.
    */
  private def occupiedShards(tests: Seq[TestDefinition], timings: Map[String, Double]): Seq[(Int, Vector[String])] =
    occupied(tests.map(_.name), timings)

  private def occupied(names: Seq[String], timings: Map[String, Double]): Seq[(Int, Vector[String])] = {
    val shards = TestShards.shardsFor(names, timings, shardCount, shardCeiling)
    TestShards.assign(names, timings, shards).zipWithIndex.collect {
      case (placed, shard) if placed.nonEmpty => shard -> placed
    }
  }

  /** The shard timings, or none: the fetched revision `CI_SHARD_TIMINGS` names, else the committed
    * `ci/test-shards.txt` ([[TestShards.timingsFile]]). A file that is present but malformed fails the
    * build rather than quietly falling back to hashing, which would be a balance nobody chose, and so
    * does a fetched file that is named but missing: the build was handed a revision to plan and run on.
    */
  def readTimings(root: File): Map[String, Double] = {
    val fetched = sys.env.get(TestShards.TimingsEnvVar).exists(_.trim.nonEmpty)
    val file = new File(TestShards.timingsFile(sys.env, root.getAbsolutePath))
    if (!file.exists) {
      if (fetched) sys.error(s"${TestShards.TimingsEnvVar} names $file, which does not exist")
      Map.empty
    } else
      TestShards.parse(new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)) match {
        case Right(timings) => timings
        case Left(reason) => sys.error(s"$file: $reason")
      }
  }

  /** One forked test group per occupied shard, each told its own database, the session database to
    * clone it from as its fork starts, its name, and the file beside the suite reports its start seconds
    * go to ([[CiShardPlugin.partStartFile]]).
    */
  private def shardGroups(
    subproject: String,
    tests: Seq[TestDefinition],
    base: ForkOptions,
    timings: Map[String, Double],
    reports: File,
  ): Seq[Tests.Group] = {
    val byName = tests.groupBy(_.name)
    occupiedShards(tests, timings).map { case (shard, names) =>
      val lane = TestShards.lane(subproject, shard)
      val env = base.envVars ++ TestDatabases.forkEnv(lane) + (ShardEnvVar -> lane) +
        (CiShardPlugin.PartStartEnvVar -> CiShardPlugin.partStartFile(reports, lane).getAbsolutePath) ++
        proofRun.map(LaneClone.ProofRunEnvVar -> _)
      Tests.Group(
        name = lane,
        tests = names.flatMap(byName(_)),
        runPolicy = Tests.SubProcess(base.withEnvVars(env)),
        tags = Seq(ShardTag -> 1)
      )
    }
  }

  /** The proof run under way, read while the test task runs: `ciProof` sets the property for the
    * length of each run and clears it after (`CiTestLists.ProofRunProperty`). A proof's alone runs
    * keep one lane between them ([[LaneClone.lifetime]]), so its forks are told which run they are
    * and the drop below leaves the lane for the next one.
    */
  private def proofRun: Option[String] = sys.props.get(CiTestLists.ProofRunProperty).filter(_.trim.nonEmpty)

  /** How a subproject's suites overlap: inside one fork when it is not sharded, across its shards
    * when it is.
    */
  private def concurrencySettings: Seq[Setting[?]] = Seq(
    // One suite at a time inside a shard. That is the isolation: two suites in one JVM would share
    // its database again.
    Test / testForkedParallel := (Test / testForkedParallel).value && !sharded(testShards.value),

    // The shards THEMSELVES side by side. sbt runs a test task's groups one after another unless
    // `parallelExecution` is true, and Play's plugin sets it false for every subproject here, so a
    // sharded subproject that does not re-assert it in build.sbt runs its shards in series: api's
    // three ran end to end, one fork at a time (ISS-19699). Forced here, as the rest of this plugin
    // is, so that nothing in build.sbt has to remember it. An unsharded subproject keeps what
    // build.sbt says, because for it the same key decides whether its suites overlap inside one fork.
    Test / parallelExecution := (Test / parallelExecution).value || sharded(testShards.value),
  )

  override def projectSettings: Seq[Setting[?]] = concurrencySettings ++ Seq(
    // Every subproject that forks. One that does not cannot be handed a database of its own at all,
    // which `TestDatabases.requireForked` refuses for it.
    testShards := (Test / fork).value,

    // Overrides the CONF_DB_DEV_URL that `ThisBuild / Test / envVars` forwards from the parent
    // environment, and names the session database the fork clones it from. Empty when lanes are
    // off, which leaves that forwarded value in place. A sharded subproject's groups override it
    // once more, per shard, below.
    Test / envVars ++= TestDatabases.forkEnv(thisProject.value.id),

    Test / testGrouping := {
      // Read outside the `if`: a task's dependencies are evaluated whichever branch is taken, and
      // the default grouping needs both anyway.
      val default = (Test / testGrouping).value
      val base = (Test / forkOptions).value
      val tests = selected((Test / definedTests).value)
      if (!sharded(testShards.value)) default
      else
        shardGroups(
          thisProject.value.id,
          tests,
          base,
          readTimings((ThisBuild / baseDirectory).value),
          target.value / "test-reports",
        )
    },

    // Unscoped, matching the `testOptions ++= scalaTestOptions.value` each subproject already sets,
    // so the two compose instead of one shadowing the other at the Test axis.
    //
    // NOTHING HERE CLONES A DATABASE. Each fork clones its own lane as it starts
    // (`testkit.ShardDatabase`, told how by `forkEnv`), because sbt runs a group's `Tests.Setup` as
    // an untagged task of its own, long before `concurrentRestrictions` admits the group's fork: a
    // setup that cloned put nearly every shard's lane of a full run on disk at once (ISS-25743).
    // What stays here is the refusal of a test task that does not fork, which `Tests.Setup` reports
    // before any suite runs, and `Tests.Cleanup`, run after each group's fork exits, the last of
    // which drops every lane the subproject's forks cloned.
    testOptions ++= {
      val subproject = thisProject.value.id
      // `envVars` reaches a FORKED JVM and nothing else, so a subproject that stops forking would
      // silently ignore its lane and open the session database instead — and with two test tasks
      // allowed at once, two such subprojects would share it. That is ISS-167, arriving quietly, by
      // way of a one-line build change that looks unrelated. Read here and refused there.
      val forked = (Test / fork).value
      val tests = selected((Test / definedTests).value)
      val timings = readTimings((ThisBuild / baseDirectory).value)
      val groups = (Test / testGrouping).value.size
      val lanes =
        if (!sharded(testShards.value)) Seq(subproject)
        else occupiedShards(tests, timings).map { case (shard, _) => TestShards.lane(subproject, shard) }
      val kept = LaneClone.lifetime(proofRun).keep
      val last = new LastGroup(groups, () => if (!kept) TestDatabases.drop(lanes))
      Seq(
        Tests.Setup(() => TestDatabases.requireForked(subproject, forked)),
        Tests.Cleanup(() => last.finished()),
      )
    },
  )
}
