import java.sql.{Connection, SQLException}
import java.util.Properties

import testshards.LaneClone

/** One Postgres database per subproject, so subprojects' test tasks can run CONCURRENTLY.
  *
  * WHY THIS EXISTS. Every subproject here forks its test JVM, and sbt's own default restrictions
  * carry `Tags.limit(Tags.ForkedTestGroup, 1)` — so the 15 subprojects' test tasks ran strictly end
  * to end. Measured by sampling `ps` once a second through a whole `dev scala run test` on a 10-core
  * runner: exactly one live forked test JVM in all 143 samples of a 156-second test phase, fork
  * windows laid end to end and not overlapping by a second.
  *
  * (ISS-800 measured 9m32s across a 10m06s phase. Do not expect that number: `testForkedParallel`
  * landed after it and collapsed the absolute times by roughly 3x. What survived is the SHAPE —
  * one fork at a time — which is what this changes.)
  *
  * The reason they were serialized is ISS-167, and it is a real one: a dozen forked JVMs pointed at
  * the ONE session database, each free to insert, claim, truncate and sweep rows the others are
  * mid-assertion on. Nothing scopes a suite's reads to the JVM that wrote them, so any spec that
  * counts rows, claims from a task queue, or asserts on a sweep can be handed another subproject's
  * state; three rounds of per-spec fixes (#1894, #1898, #1899) each closed one instance and the next
  * run surfaced another.
  *
  * Giving each subproject its OWN database removes the class rather than the instances: there is
  * nothing to share, so there is nothing to declare. A lane database is a `CREATE DATABASE ...
  * TEMPLATE` clone of the session database, which takes well under a second — cheaper than one
  * suite's Play boot, and it is a fresh clone per run, so it inherits ISS-801's "start from a known
  * state" guarantee for free. The one exception is a quarantine proof's alone runs, which share one
  * lane on purpose (`LaneClone.lifetime`).
  *
  * A run clones a lane per subproject per shard — over a hundred on a full run — so the session
  * database's size is multiplied by that. It is the slim one: the session-DB image carries no
  * pulled Court Reserve facts (each schema repository's `docker/data-policy.yml`, ISS-25812), and no
  * spec reads pulled rows, since every spec builds its own fixtures. Each forked test JVM clones its
  * own lane as it starts and drops it as it exits ([[forkEnv]], `testkit.ShardDatabase`), so the
  * lanes on disk are the shards running rather than every shard the run will have (ISS-25743).
  * A subproject's last test group drops every lane it had once more ([[drop]]), for the fork that
  * died without running its shutdown hooks, so none outlive the run.
  *
  * WHEN IT IS OFF. Only a `dev db session` database may be cloned. An interactive run with no
  * `CONF_DB_DEV_URL` is talking to Mike's own Postgres.app on :5432, where creating and dropping
  * `platformdb_core` is not ours to do — so `enabled` is false, nothing is created, and `build.sbt`
  * keeps the old one-test-task-at-a-time restriction. There is no half-on state: concurrency and
  * per-subproject databases are decided by the same flag, because concurrency WITHOUT them is
  * exactly ISS-167.
  */
object TestDatabases {

  /** What `dev db session` puts after the database's own name in every session database it creates:
    * `<database>_sess_`, see `devops/lib/db_apps.rb#sess_prefix` (`platformdb_sess_`, `playbookdb_sess_`).
    * It is what distinguishes a disposable per-session database, which this build may clone and drop
    * at will, from a real one it may not touch. Read as the database's first `_sess_` after a name of
    * letters and digits, so every app's build reads its own session database with no setting.
    */
  private val SessionDb = "^[A-Za-z0-9]+_sess_.".r

  /** Whether `db` is a `dev db session` database this build may clone, and not already a lane of one. */
  def isSessionDb(db: String): Boolean =
    SessionDb.findPrefixOf(db).isDefined && !db.contains(LaneSeparator)

  /** Separates the session database from the subproject in a lane database's name, and it is a
    * DOUBLE underscore for a reason that is not cosmetic.
    *
    * Session databases are named `<database>_sess_<sanitized session id>`, and the sanitiser
    * collapses runs of underscores to one (`devops/lib/db_images.rb#sanitize_session_id`) — so no
    * session database name can ever contain `__`. That is what makes `<session db>__<subproject>`
    * unambiguously OURS: with a single underscore, the workspaces the agent executor creates for an
    * epic and its children (`i682` beside `i682_c03`) would give session databases
    * `..._sess_i682` and `..._sess_i682_c03`, and `..._sess_i682_c03` reads equally well as
    * "session i682's `c03` lane". Anything reclaiming one session's lanes by prefix would then drop
    * another session's whole database.
    */
  private val LaneSeparator = LaneClone.LaneSeparator

  /** Postgres truncates identifiers past this silently, which for two lane names sharing a
    * truncated prefix would mean two subprojects quietly sharing one database — ISS-167 again, and
    * invisible. Names are shortened deliberately below instead.
    */
  private val MaxIdentifierLength = 63

  private val sessionUrl: Option[String] =
    sys.env.get("CONF_DB_DEV_URL").map(_.trim).filter(_.nonEmpty)

  private val parsed: Option[(String, String)] = sessionUrl.flatMap(LaneClone.split)

  /** True when this run has a `dev db session` database of its own, and may therefore clone it per
    * subproject. `build.sbt` reads this to decide whether to lift the one-test-task-at-a-time
    * restriction — the two must never disagree.
    */
  val enabled: Boolean = parsed.exists { case (_, db) =>
    // Not already a lane. Nothing constructs that today — the build reads the sbt JVM's
    // environment, and only the forked JVMs are handed a lane url — but a lane cloned from a lane
    // would drift from the session database silently rather than fail, so it is ruled out here
    // where the rule is one line.
    isSessionDb(db)
  }

  /** Lane database name: the session database, suffixed with the subproject.
    *
    * Shortened when the pair would exceed Postgres' identifier limit, by dropping characters from
    * the SESSION half and appending a hash of what it was. Truncating alone would let two sessions
    * whose ids share a prefix land on one database; the hash is what keeps distinct sessions
    * distinct.
    */
  private def laneDb(sessionDb: String, lane: String): String = {
    val room = MaxIdentifierLength - lane.length - LaneSeparator.length
    // Nine characters is what the hash below needs. A subproject named long enough to leave less
    // than that has outgrown the scheme, and saying so is the only honest option: silently
    // truncating is how two subprojects end up on one database.
    if (room < 10) {
      sys.error(
        s"Subproject name '$lane' is too long to derive a test database name from '$sessionDb' " +
          s"within Postgres' $MaxIdentifierLength-character identifier limit."
      )
    }
    val base =
      if (sessionDb.length <= room) sessionDb
      else sessionDb.take(room - 9) + "_" + f"${sessionDb.hashCode & 0xffffffffL}%08x"
    s"$base$LaneSeparator$lane"
  }

  /** What a forked test JVM is told so it clones its own lane as it starts: the lane's url, and the
    * session database to clone it from ([[LaneClone.TemplateEnvVar]], read by
    * `testkit.ShardDatabase`). Empty when lanes are off, which leaves the forwarded
    * `CONF_DB_DEV_URL` in place and clones nothing.
    *
    * The fork clones rather than this JVM because a clone made here, in `Tests.Setup`, is made long
    * before sbt admits the fork it is for — see [[LaneClone]] for why that put 88 lanes on disk at
    * once (ISS-25743). Dropping first and cloning afresh on every fork keeps ISS-801's "start from a
    * known state": `dev scala run test` resets the SESSION database before sbt starts, so every lane
    * cloned from it is pristine too.
    */
  def forkEnv(lane: String): Map[String, String] = parsed
    .collect {
      case (base, db) if enabled =>
        Map("CONF_DB_DEV_URL" -> (base + laneDb(db, lane)), LaneClone.TemplateEnvVar -> db)
    }
    .getOrElse(Map.empty)

  /** Refuses a subproject whose test task does not fork while lanes are on. Run from `Tests.Setup`.
    *
    * `Test / envVars` is delivered to a forked process and to nothing else, so an unforked test task
    * would ignore its lane entirely — nothing would clone one, and its suites would open the session
    * database quietly, while another subproject is allowed to be running. Refused rather than worked
    * around, because the fix is one line in build.sbt and the alternative is ISS-167 returning as an
    * intermittent failure in a spec nobody touched.
    */
  def requireForked(subproject: String, forked: Boolean): Unit =
    if (enabled && !forked) {
      sys.error(
        s"Subproject '$subproject' does not fork its test JVM, so it cannot be given its own test " +
          "database and would share the session database with whatever else is running " +
          "(ISS-167/ISS-813). Set `Test / fork := true` for it in build.sbt."
      )
    }

  /** Drops these lane databases. Run from `Tests.Cleanup` once a subproject's LAST test group has
    * finished, so a run leaves no lane behind even when a fork died before it could drop its own.
    */
  def drop(lanes: Seq[String]): Unit = synchronized {
    parsed.foreach { case (base, sessionDb) =>
      if (enabled) {
        withMaintenanceConnection(base) { c =>
          lanes.foreach(lane => LaneClone.drop(c, laneDb(sessionDb, lane)))
        }
      }
    }
  }

  /** Driven directly rather than through `DriverManager`, which cannot see it.
    *
    * `DriverManager` discovers drivers with a `ServiceLoader` scan it runs ONCE, against whichever
    * classloader is current the first time it is touched — and sbt loads a build's dependencies in
    * a child loader of its own, so by the time this code runs the scan has already happened
    * somewhere that could not see the driver. The failure is `No suitable driver found` for a jar
    * that is demonstrably on the classpath, which reads like a missing dependency and is not one.
    */
  private def withMaintenanceConnection[T](base: String)(f: Connection => T): T = {
    val url = base + LaneClone.MaintenanceDb
    val props = new Properties()
    props.setProperty("user", LaneClone.SuperUser)
    def unreachable(cause: Throwable): Nothing =
      throw new RuntimeException(
        s"Could not connect to $url as ${LaneClone.SuperUser} to drop this run's per-subproject test " +
          "databases, so they would outlive the run. Check the session container is up: " +
          "dev db session status",
        cause
      )

    val c =
      try new org.postgresql.Driver().connect(url, props)
      catch { case e: SQLException => unreachable(e) }
    // `connect` answers null for a url this driver does not handle, rather than throwing. Not
    // reachable while the url comes from CONF_DB_DEV_URL and the regex above has already matched
    // `jdbc:postgresql:`, but a null here would surface as an NPE three lines down.
    if (c == null) unreachable(new SQLException(s"the postgresql driver did not accept $url"))
    try f(c)
    finally c.close()
  }
}
