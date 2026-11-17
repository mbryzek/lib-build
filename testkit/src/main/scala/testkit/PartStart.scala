package testkit

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.Locale

import scala.util.control.NonFatal

/** Writes how long this forked shard took from its launch to its first suite, the start seconds every part a shard
  * holds pays once (design DES-94295, "How finely a subproject is cut"):
  *
  * {{{
  *   integrations 41.207
  * }}}
  *
  * one line, `<subproject> <seconds>`, into the file `DEV_TEST_PART_START` names, which `CiShardPlugin.partStartFile`
  * puts beside the subproject's suite reports. The runner sends it with the suites in the shard's report, and the
  * shard timings carry each subproject's median as its `start:<subproject>` line.
  *
  * Called by [[ForkSafeScalaTestFramework]] as the fork builds the runner its suites run under, after its lane database
  * is cloned: nothing but that runner's construction stands between this moment and the first suite. Silent unless
  * both `DEV_TEST_PART_START` and `DEV_TEST_SHARD` are set, which only `TestDatabasePlugin` does and only for a fork,
  * so sbt's own JVM and an unsharded subproject write nothing. A file that cannot be written is said on stderr and
  * never fails the run: it costs the planner one measurement, not the build its result.
  */
object PartStart {

  val FileEnvVar: String = "DEV_TEST_PART_START"

  val ShardEnvVar: String = "DEV_TEST_SHARD"

  def record(): Unit =
    record(sys.env, ManagementFactory.getRuntimeMXBean.getStartTime, System.currentTimeMillis())

  def record(env: Map[String, String], launchedAtMillis: Long, nowMillis: Long): Unit =
    for {
      path <- env.get(FileEnvVar).map(_.trim).filter(_.nonEmpty)
      lane <- env.get(ShardEnvVar).map(_.trim).filter(_.nonEmpty)
    } {
      val text = line(lane, math.max(0L, nowMillis - launchedAtMillis) / 1000.0) + "\n"
      try {
        val file = Paths.get(path)
        Option(file.getParent).foreach(Files.createDirectories(_))
        Files.write(file, text.getBytes(StandardCharsets.UTF_8))
      } catch {
        case NonFatal(e) => System.err.println(s"[part-start] could not write $path: $e")
      }
    }

  /** `integrations 41.207`: the lane's subproject and the seconds, to the millisecond, a point on every machine. */
  def line(lane: String, seconds: Double): String =
    s"${subproject(lane)} ${String.format(Locale.ROOT, "%.3f", Double.box(seconds))}"

  /** The subproject a lane (`TestShards.lane`, `integrations__3`) is a shard of. */
  def subproject(lane: String): String = lane.lastIndexOf("__") match {
    case -1 => lane
    case i => lane.substring(0, i)
  }
}
