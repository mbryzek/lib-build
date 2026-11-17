package testshards

import java.sql.{Connection, SQLException}

/** How one lane database is cloned from the session database, and how it is dropped.
  *
  * Compiled twice, from this one file: into `testkit`, where each forked test JVM clones its OWN
  * lane as it starts and drops it as it exits (`testkit.ShardDatabase`), and into the meta-build,
  * where `TestDatabases` drops a subproject's lanes once more when its last test group has finished.
  *
  * WHY THE FORK CLONES, AND NOT THE sbt JVM. A clone made before a fork is admitted is a lane on disk
  * for a fork that is not running. sbt runs a test group's `Tests.Setup` as a task of its own,
  * untagged, that the group's fork waits on; `concurrentRestrictions` admits the FORK, and the setup
  * runs as soon as the test task is reached. So a setup that clones runs for nearly every group of a
  * full run at its start, whichever groups the restrictions go on to hold back, and a 16-core run
  * peaked at 88 lanes at once (ISS-25743). The fork itself exists only once it has been admitted,
  * so a clone made there, with a drop as it exits, bounds the lanes on disk to the shards running.
  *
  * Clones are serialized per session database with an advisory lock, as they were when one sbt JVM
  * made them all: two `CREATE DATABASE ... TEMPLATE` statements copying one template at once
  * contend for the same pages and checkpoints, and a lock costs a waiting fork a fraction of a
  * second.
  */
object LaneClone {

  /** The session database a fork's lane is cloned from, set only on a forked test JVM's
    * environment by `TestDatabasePlugin`. Its absence is what makes `testkit.ShardDatabase` a no-op:
    * an interactive run, and every JVM that is not a test fork.
    */
  val TemplateEnvVar: String = "DEV_TEST_LANE_TEMPLATE"

  /** The container's superuser. `dev db session` connects the same way (`psql -U postgres`, trust
    * auth inside the container); `db.default.username` is `api`, which has neither SUPERUSER nor
    * CREATEDB and so cannot clone a database.
    */
  val SuperUser: String = "postgres"

  /** Maintenance database to issue CREATE/DROP DATABASE against — never one of the databases being
    * created or dropped.
    */
  val MaintenanceDb: String = "postgres"

  /** Separates the session database from the lane in a lane database's name. `TestDatabases`
    * explains why it is a double underscore; here it is what tells a lane from a session database,
    * so a lane is never cloned from a lane and a session database is never dropped as one.
    */
  val LaneSeparator: String = "__"

  /** `jdbc:postgresql://host:port/database[?params]` */
  private val JdbcUrl = """^(jdbc:postgresql://[^/]+/)([^?]+)(\?.*)?$""".r

  /** The server half of a JDBC url and the database it names. */
  def split(url: String): Option[(String, String)] = url.trim match {
    case JdbcUrl(base, db, _) => Some((base, db))
    case _ => None
  }

  /** Why `lane` must not be cloned from `template`, or None when it may be. Checked before anything
    * is dropped: the drop is `WITH (FORCE)`, and aimed at the wrong name it would take a session
    * database and everyone connected to it.
    */
  def refusal(template: String, lane: String): Option[String] =
    if (template.isEmpty || lane.isEmpty) Some(s"an empty database name (template '$template', lane '$lane')")
    else if (template.contains(LaneSeparator)) Some(s"template '$template' is itself a lane")
    else if (!lane.contains(LaneSeparator)) Some(s"'$lane' is not a lane database, so it is not this build's to drop")
    else if (lane == template) Some(s"'$lane' is the template itself")
    else None

  /** Which run of a quarantine's proof a fork belongs to, `<mode> <index> <of>` (`alone 2 5`),
    * handed to the forks of that run by `TestDatabasePlugin` from the sbt JVM's
    * `cilists.CiTestLists.ProofRunProperty`. Absent on every other run, which is every build's suite.
    */
  val ProofRunEnvVar: String = "CI_PROOF_RUN"

  /** `<mode> <index> <of>`: `alone 2 5` is the second of five runs alone. */
  def proofRun(mode: String, index: Int, of: Int): String = s"$mode $index $of"

  /** What a fork does with its lane: open the one an earlier run left (`reuse`) or clone it afresh,
    * and leave it for the next run (`keep`) or drop it.
    */
  final case class Lifetime(reuse: Boolean, keep: Boolean)

  /** Every run but a proof's alone runs: a fresh clone, dropped at the end. */
  val Fresh: Lifetime = Lifetime(reuse = false, keep = false)

  /** A proof's alone runs share ONE lane: the first clones it and keeps it, each later run opens it,
    * and the last drops it. Every other run is [[Fresh]].
    *
    * WHY. A spec in a build never meets an empty database: an earlier spec on its shard, or its own
    * first attempt before the rerun, has always left rows behind, and the tables' statistics are
    * whatever those rows made them. A proof that clones a fresh lane for every run asks the one
    * question no build asks, five times over, so a fix that holds only on an empty database passes
    * it and flakes again within days (ISS-47188: a spec whose cases leaked rows another case read by
    * key, and a plan assertion that failed only once the table held rows). Run back to back on one
    * database, the second run meets everything the first left, which is the leak itself.
    *
    * The beside runs stay fresh: they run the whole subproject, which leaves its own rows anyway.
    */
  def lifetime(proofRun: Option[String]): Lifetime =
    proofRun.map(_.trim.split("\\s+").toList) match {
      case Some(List("alone", index, of)) =>
        (number(index), number(of)) match {
          case (Some(i), Some(n)) if n > 1 && i >= 1 && i <= n => Lifetime(reuse = i > 1, keep = i < n)
          case _ => Fresh
        }
      case _ => Fresh
    }

  /** Scala 2.12, which the meta-build compiles this file with, has no `toIntOption`. */
  private def number(s: String): Option[Int] =
    if (s.nonEmpty && s.length <= 4 && s.forall(_.isDigit)) Some(s.toInt) else None

  /** Opens `lane` for a fork that will `reuse` it when it is there, or clones it afresh otherwise:
    * the seconds the clone took, or None when the lane was reused.
    */
  def open(c: Connection, template: String, lane: String, reuse: Boolean): Option[Double] =
    if (reuse && exists(c, lane)) {
      refusal(template, lane).foreach(reason => sys.error(s"Refusing to open a test database: $reason."))
      None
    } else Some(reset(c, template, lane))

  private def exists(c: Connection, db: String): Boolean = {
    val stmt = c.createStatement()
    try {
      val rs = stmt.executeQuery(s"select 1 from pg_database where datname = '${literal(db)}'")
      try rs.next()
      finally rs.close()
    } finally stmt.close()
  }

  /** Drops `lane` and clones it afresh from `template`, returning the seconds the clone took.
    *
    * Dropping first rather than reusing is the point: the suite is not idempotent against its own
    * database (ISS-801), and a lane a crashed run left behind would carry its rows into this one.
    * `FORCE` terminates connections a previous run left behind; it is only ever aimed at a lane,
    * never at the session database being cloned.
    */
  def reset(c: Connection, template: String, lane: String): Double = {
    refusal(template, lane).foreach(reason => sys.error(s"Refusing to clone a test database: $reason."))
    exec(c, s"select pg_advisory_lock(hashtext('${literal(template)}'))")
    try {
      val owner = ownerOf(c, template)
      exec(c, s"""DROP DATABASE IF EXISTS "$lane" WITH (FORCE)""")
      val started = System.nanoTime()
      clone(c, template, lane, owner)
      (System.nanoTime() - started) / 1e9
    } finally exec(c, s"select pg_advisory_unlock(hashtext('${literal(template)}'))")
  }

  /** Drops `lane`. `FORCE` for the same reason [[reset]] uses it: a fork that exited can leave its
    * connections closing.
    */
  def drop(c: Connection, lane: String): Unit = {
    if (!lane.contains(LaneSeparator)) sys.error(s"Refusing to drop '$lane': it is not a lane database.")
    exec(c, s"""DROP DATABASE IF EXISTS "$lane" WITH (FORCE)""")
  }

  /** `ci-db: clone <database> <seconds>`, one per clone, which the fleet's verify job sums into
    * `detail.db_setup` on the build's verdict (design DES-18577, "Database setup is measured before
    * anything is pooled"). Seconds come LAST so a reader splitting from the right cannot be broken by
    * a database name, and to the millisecond because a clone is about 0.2s and whole seconds would
    * record every one of them as nothing. `Locale.ROOT` so the decimal point is a point on every
    * machine.
    */
  def cloneLine(database: String, seconds: Double): String =
    "ci-db: clone " + database + " " + String.format(java.util.Locale.ROOT, "%.3f", Double.box(seconds))

  /** `CREATE DATABASE ... TEMPLATE`, and the one way it fails that is worth naming: Postgres
    * refuses while ANY session is connected to the template.
    *
    * Nothing in the run holds the session database — every fork opens its own clone — so what this
    * catches is something outside it: a `dev scala run run` left up in another terminal, a psql, or
    * the brief per-database connection `dev db session status` opens to report migration drift.
    *
    * Neither forced nor retried. Forcing means `pg_terminate_backend` on a connection this build
    * did not open, and a test run is not entitled to kill somebody's psql. Retrying means sleeping,
    * which this codebase does not do, to shave an already-small window — while the honest failure
    * costs one re-run and says exactly what to close.
    */
  private def clone(c: Connection, from: String, to: String, owner: String): Unit =
    try exec(c, s"""CREATE DATABASE "$to" TEMPLATE "$from" OWNER "$owner"""")
    catch {
      case e: SQLException if Option(e.getMessage).exists(_.contains("being accessed by other users")) =>
        throw new RuntimeException(
          s"Could not clone $from into $to: something is connected to $from. Each test shard runs " +
            "against its own clone of it, so nothing in this run holds it — look for a " +
            "`dev scala run run` still up, or a psql session. `dev db session status` lists what is " +
            "connected (and briefly connects itself, so do not run it and this at the same time).",
          e
        )
    }

  /** Whoever owns the session database owns its clones. Read rather than hardcoded to `api`: that
    * is `dev db session`'s choice (`ALTER DATABASE ... OWNER TO`), and a clone owned by someone else
    * would differ from the database it is standing in for.
    */
  private def ownerOf(c: Connection, db: String): String = {
    val stmt = c.createStatement()
    try {
      val rs = stmt.executeQuery(
        s"select pg_catalog.pg_get_userbyid(datdba) from pg_database where datname = '${literal(db)}'"
      )
      try {
        if (rs.next()) rs.getString(1)
        else sys.error(s"No database named $db — expected this session's database to exist.")
      } finally rs.close()
    } finally stmt.close()
  }

  private def literal(s: String): String = s.replace("'", "''")

  private def exec(c: Connection, sql: String): Unit = {
    val stmt = c.createStatement()
    try {
      val _ = stmt.execute(sql)
    } finally stmt.close()
  }
}
