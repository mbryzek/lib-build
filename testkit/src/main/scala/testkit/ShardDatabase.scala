package testkit

import java.sql.{DriverManager, SQLException}
import java.util.Properties

import testshards.LaneClone

/** Clones this forked test JVM's own lane database, once, before its first suite runs, and drops it
  * as the JVM exits (ISS-25743).
  *
  * `TestDatabasePlugin` hands every forked test JVM the url of its lane in `CONF_DB_DEV_URL` and
  * the session database to clone it from in [[LaneClone.TemplateEnvVar]]. Cloning HERE rather than
  * in the sbt JVM is what bounds the lanes on disk to the shards running: a fork exists only once
  * `concurrentRestrictions` has admitted it, while the `Tests.Setup` that used to clone ran as an
  * untagged task long before, for nearly every shard of a full run at once. [[LaneClone]] has the
  * measurement.
  *
  * Called from [[ForkSafeScalaTestFramework.runner]], the one hook sbt's `ForkMain` reaches in the
  * fork before any suite, through the one framework every subproject is guaranteed to load. A
  * failure is thrown from there, which `ForkMain` reports as the group's error before a single
  * suite has run against a database that is not there. A JVM with no template in its environment
  * — the sbt JVM, an interactive run, `testkit`'s own unforked suites — clones nothing.
  */
object ShardDatabase {

  /** What a fork clones: its lane, on the server `base` names, from `template`, and whether it
    * opens the lane an earlier run of a proof kept and keeps it for the next ([[LaneClone.lifetime]]).
    */
  final case class Plan(base: String, template: String, lane: String, lifetime: LaneClone.Lifetime = LaneClone.Fresh)

  private var done = false

  def ensure(): Unit = ensure(name => sys.env.get(name))

  /** Clones on the first call that has a [[Plan]] and on no later one: `ForkMain` builds a runner
    * per framework, and a second clone would drop, `WITH (FORCE)`, the lane the first suite is on.
    */
  private[testkit] def ensure(env: String => Option[String]): Unit = synchronized {
    if (!done) {
      plan(env).foreach(clone)
      done = true
    }
  }

  /** The clone this environment asks for, or None when it asks for none. An environment that names
    * a template but no lane this can clone is the build's mistake, and is thrown rather than run
    * past: suites would otherwise open whatever `CONF_DB_DEV_URL` names, cloned or not.
    */
  def plan(env: String => Option[String]): Option[Plan] =
    env(LaneClone.TemplateEnvVar).map(_.trim).filter(_.nonEmpty).map { template =>
      val url = env("CONF_DB_DEV_URL").getOrElse("")
      val (base, lane) = LaneClone
        .split(url)
        .getOrElse(
          sys.error(s"${LaneClone.TemplateEnvVar} is set but CONF_DB_DEV_URL ('$url') is not a postgresql url")
        )
      LaneClone
        .refusal(template, lane)
        .foreach(reason => sys.error(s"Refusing to clone this fork's database: $reason."))
      Plan(base, template, lane, LaneClone.lifetime(env(LaneClone.ProofRunEnvVar)))
    }

  /** Clones the lane, and drops it again as this JVM exits.
    *
    * The drop is what makes the lanes on disk the shards RUNNING: a subproject's last group drops
    * every lane it had (`TestDatabases.drop`), but its first shard can finish many minutes before its
    * last, and until then a finished shard's lane is a whole database nobody will open again. The
    * fork is the one place that knows which lane is finished, so it drops its own. A fork that dies
    * without running its shutdown hooks leaves its lane to that last group's drop.
    */
  private def clone(plan: Plan): Unit = {
    withMaintenanceConnection(plan, "clone") { c =>
      LaneClone.open(c, plan.template, plan.lane, plan.lifetime.reuse) match {
        case Some(seconds) => println(LaneClone.cloneLine(plan.lane, seconds))
        case None => println(s"[shard-database] reusing ${plan.lane}, which the proof's previous run left")
      }
    }
    // A proof's alone run before its last keeps the lane for the next one; the last drops it.
    if (!plan.lifetime.keep)
      Runtime.getRuntime.addShutdownHook(new Thread(() => dropOnExit(plan), "shard-database-drop"))
  }

  /** Never fails the exit: by now the shard's results have reached sbt, and a lane left behind is
    * the last group's to drop.
    */
  private def dropOnExit(plan: Plan): Unit =
    try withMaintenanceConnection(plan, "drop")(c => LaneClone.drop(c, plan.lane))
    catch {
      case e: Exception =>
        System.err.println(s"[shard-database] could not drop ${plan.lane} as this fork exited: $e")
    }

  /** Through `DriverManager`, which in the fork can see the driver: the test classpath is this JVM's
    * own classpath, unlike the sbt JVM's child loader `TestDatabases` has to work around.
    */
  private def withMaintenanceConnection(plan: Plan, act: String)(f: java.sql.Connection => Unit): Unit = {
    val url = plan.base + LaneClone.MaintenanceDb
    val props = new Properties()
    props.setProperty("user", LaneClone.SuperUser)
    val c =
      try DriverManager.getConnection(url, props)
      catch {
        case e: SQLException =>
          throw new RuntimeException(
            s"Could not connect to $url as ${LaneClone.SuperUser} to $act ${plan.lane}, this test " +
              "shard's own database. Check the session container is up: dev db session status",
            e
          )
      }
    try f(c)
    finally c.close()
  }
}
