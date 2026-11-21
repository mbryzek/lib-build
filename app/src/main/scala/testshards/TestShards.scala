package testshards

import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/** How a sharded subproject's suites are split across its shards.
  *
  * A SHARD is one forked test JVM with its own clone of the session database, running one suite at
  * a time (design DES-18577, "Test isolation"). Two suites never share a database at the same
  * moment, so a cleanup in one cannot race a read in another.
  *
  * WHICH SUITE GOES WHERE has two answers, and the file decides between them:
  *
  *   - The shard timings list suites with their usual seconds, one `<suite> <seconds>` per line.
  *     When they list any suite, the suites are placed longest first, each onto the least-loaded
  *     shard so far, and a suite they do not list is weighted at their median. That is what keeps
  *     one shard from finishing a minute after the rest.
  *   - When there are no timings, or they list no suite, a suite goes to the shard a stable hash of
  *     its name picks. Stable is the point: a suite's neighbours do not change from build to build,
  *     so a failure that depends on a neighbour reproduces on the next run instead of moving.
  *
  * THE TIMINGS are the repository file `test-shards.txt` platform rewrites from what shards took
  * (design DES-94295, "How a build reads them"). A build's Prepare fetches the newest revision and
  * every shard the revision its build named, each into a file outside the checkout named by
  * [[TimingsEnvVar]]; with that unset, there are no timings and suites are placed by hash.
  *
  * The same file carries each subproject's median START seconds, `start:<subproject> <seconds>`
  * ([[StartPrefix]]): what a forked test JVM of that subproject spends from its launch to its first
  * suite, which every part a shard holds pays once. A planner that predates them reads such a line
  * as a suite no subproject defines, and so is unaffected by it.
  *
  * No sbt type appears here on purpose. This file is compiled twice: by the meta-build, where
  * [[TestDatabasePlugin]] turns it into `Test / testGrouping`, and by `testkit` as a test source,
  * where `TestShardsSpec` asserts every rule below. It therefore stays inside the syntax Scala 2.12
  * and Scala 3 share.
  */
object TestShards {

  /** What the runner exports to every build: its cores and memory over its build slots. */
  val CountEnvVar: String = "CI_BUILD_SHARDS"

  /** The fetched shard timings a build hands its planner and its shards: a file outside the checkout. */
  val TimingsEnvVar: String = "CI_SHARD_TIMINGS"

  /** Where a build reads its timings: the file [[TimingsEnvVar]] names, or none, when the build plans by
    * hash. Pure, so the meta-build and a spec agree on it.
    */
  def timingsFile(env: Map[String, String]): Option[String] =
    env.get(TimingsEnvVar).map(_.trim).filter(_.nonEmpty)

  /** The key a subproject's start seconds are listed under: `start:integrations`. */
  val StartPrefix: String = "start:"

  /** The listed suites alone, without the start lines. */
  def suiteSeconds(timings: Map[String, Double]): Map[String, Double] =
    timings.filter { case (name, _) => !name.startsWith(StartPrefix) }

  /** Each subproject's measured start seconds, from its `start:<subproject>` line. */
  def startSeconds(timings: Map[String, Double]): Map[String, Double] =
    timings.collect { case (name, s) if name.startsWith(StartPrefix) => name.stripPrefix(StartPrefix) -> s }

  /** `CI_BUILD_SHARDS` when it is a positive integer, otherwise `max(2, logical cores / 3)`: the
    * runner formula with three build slots and memory not binding, which is what an interactive
    * run, or a runner predating the export, has no better figure than.
    */
  def count(env: Map[String, String], logicalCores: Int): Int =
    env.get(CountEnvVar).map(_.trim).flatMap(parsePositive).getOrElse(math.max(2, logicalCores / 3))

  /** What a runner may export to set [[ceiling]] itself. */
  val CeilingEnvVar: String = "CI_BUILD_SHARDS_MAX"

  /** The most shards a build runs AT ONCE while the machine has room for them: `CI_BUILD_SHARDS_MAX`
    * when it is a positive integer, otherwise a third of the logical cores, and never below `floor`.
    *
    * [[count]] is the runner's budget for one build when every slot is building, so on a session
    * machine sized for eight builds at once it is 2 — and a build on an otherwise idle machine ran two
    * suites at a time with most of the cores asleep (ISS-25548). The shards past the floor are what
    * [[admit]] hands out while the load says nobody else is using them.
    */
  def ceiling(env: Map[String, String], logicalCores: Int, floor: Int): Int =
    math.max(floor, env.get(CeilingEnvVar).map(_.trim).flatMap(parsePositive).getOrElse(logicalCores / 3))

  /** The share of the logical cores the one-minute load average must stay under for a shard past the
    * floor to start.
    */
  val LoadShare: Double = 0.5

  /** Whether a shard may start when `running` shards, itself included, would then be running.
    *
    * Up to `floor` always: that is what the runner budgeted for this build. Past it, up to `ceiling`,
    * only while the one-minute load average is under [[LoadShare]] of the logical cores. A load the
    * JVM cannot read is negative and admits nothing past the floor. sbt asks again whenever a task
    * finishes, so a shard refused on a busy machine starts as soon as one finishes on a quieter one.
    */
  def admit(running: Int, floor: Int, ceiling: Int, load: Double, logicalCores: Int): Boolean =
    running <= floor || (running <= ceiling && load >= 0 && load < logicalCores * LoadShare)

  /** The measured suite seconds one shard is sized to hold. A shard costs a forked Play JVM's boot,
    * so a shard much shorter than this spends a large share of its life starting.
    */
  val ShardSeconds: Double = 60.0

  /** How many shards a subproject's suites are split into.
    *
    * With no timings, `floor`, each suite placed by its hash, as before timings existed. With timings,
    * one shard per [[ShardSeconds]] of the subproject's measured time — a suite the file does not list
    * weighted at its median — between 1 and `ceiling`. So a subproject of a few seconds is one fork
    * rather than `floor` forks each booting Play for a handful of suites, and the largest is split as
    * wide as the build can ever run it, which is what bounds the slowest shard (ISS-25548).
    */
  def shardsFor(suites: Seq[String], timings: Map[String, Double], floor: Int, ceiling: Int): Int =
    median(timings) match {
      case None => floor
      case Some(unlisted) =>
        val weight = suites.distinct.map(s => timings.getOrElse(s, unlisted)).sum
        math.max(1, math.min(ceiling, math.ceil(weight / ShardSeconds).toInt))
    }

  private def parsePositive(value: String): Option[Int] =
    if (value.nonEmpty && value.forall(_.isDigit) && value.length <= 4) Some(value.toInt).filter(_ > 0)
    else None

  /** The suites and their median seconds. Blank lines and `#` lines are skipped; any other line
    * that is not `<suite> <seconds>` refuses the file whole, naming the line, because a placement
    * read from half a file is a balance nobody measured.
    */
  def parse(text: String): Either[String, Map[String, Double]] = {
    val lines = text.split("\n", -1).toList.map(_.stripSuffix("\r").trim).zipWithIndex
    lines.foldLeft[Either[String, Map[String, Double]]](Right(Map.empty)) {
      case (Right(acc), (line, index)) =>
        if (line.isEmpty || line.startsWith("#")) Right(acc)
        else
          line.split("\\s+").toList match {
            case suite :: seconds :: Nil =>
              parseSeconds(seconds) match {
                case Some(s) => Right(acc + (suite -> s))
                case None => Left(s"line ${index + 1} of the shard timings has no seconds: $line")
              }
            case _ => Left(s"line ${index + 1} of the shard timings is not `<suite> <seconds>`: $line")
          }
      case (left, _) => left
    }
  }

  private def parseSeconds(value: String): Option[Double] =
    try {
      val d = value.toDouble
      if (d >= 0 && !d.isNaN && !d.isInfinite) Some(d) else None
    } catch { case _: NumberFormatException => None }

  /** The median of the listed suites' seconds, start lines left out; None for a file that lists no suite. */
  def median(timings: Map[String, Double]): Option[Double] = {
    val sorted = suiteSeconds(timings).values.toVector.sorted
    if (sorted.isEmpty) None
    else if (sorted.length % 2 == 1) Some(sorted(sorted.length / 2))
    else Some((sorted(sorted.length / 2 - 1) + sorted(sorted.length / 2)) / 2)
  }

  /** The shard a suite's name hashes to. CRC32 of the UTF-8 bytes, so it is the same on every JVM,
    * in the meta-build and in a test alike.
    */
  def hashShard(suite: String, shards: Int): Int = {
    val crc = new CRC32
    crc.update(suite.getBytes(StandardCharsets.UTF_8))
    (crc.getValue % shards.toLong).toInt
  }

  /** `shards` lists of suites, every suite in exactly one, each list in name order. */
  def assign(suites: Seq[String], timings: Map[String, Double], shards: Int): Vector[Vector[String]] = {
    require(shards > 0, s"a subproject needs at least one shard, not $shards")
    val distinct = suites.distinct
    val placed: Map[Int, Seq[String]] = median(timings) match {
      case None => distinct.groupBy(hashShard(_, shards))
      case Some(unlisted) =>
        val weighted = distinct
          .map(s => (s, timings.getOrElse(s, unlisted)))
          .sortBy { case (s, w) => (-w, s) }
        val loads = Array.fill(shards)(0.0)
        weighted
          .map { case (s, w) =>
            val target = loads.indices.minBy(i => (loads(i), i))
            loads(target) += w
            (target, s)
          }
          .groupBy(_._1)
          .map { case (shard, pairs) => shard -> pairs.map(_._2) }
    }
    Vector.tabulate(shards)(i => placed.getOrElse(i, Nil).toVector.sorted)
  }

  /** The shards a build's Prepare plans, exactly: the shard count a person set on the repository's row (ISS-75612). */
  val ShardCountEnvVar: String = "CI_SHARD_COUNT"

  /** How a build's Prepare sizes its plan: exactly the repository's shard count. */
  final case class Exactly(shards: Int)

  /** The sizing a Prepare exported: a positive [[ShardCountEnvVar]], else none, which plans as a build run by hand
    * ([[plan]]).
    */
  def sizing(env: Map[String, String]): Option[Exactly] =
    env.get(ShardCountEnvVar).flatMap(v => scala.util.Try(v.trim.toInt).toOption).filter(_ > 0).map(Exactly(_))

  /** A build splits across runners when its suites add up to at least this many usual seconds
    * (design DES-25567, "When a build splits"). Under it, the upload of the compiled classes and a
    * second runner's boot cost more than the shards save.
    */
  val SplitAtSeconds: Double = 600.0

  /** The usual seconds one runner's shard of a split build is sized to hold. */
  val SplitShardSeconds: Double = 300.0

  /** The fewest and the most shards a split build files. */
  val SplitMinShards: Int = 2
  val SplitMaxShards: Int = 6

  /** Part of one subproject's suites, placed whole onto one shard of a split build. A subproject
    * that fits in its piece of a shard is one part labelled with its name; a larger one is cut into
    * equal parts, `integrations 1 of 3`. `seconds` is its suites' usual seconds plus its
    * subproject's start seconds, which every part pays once, since each starts its own forked JVM.
    */
  final case class Part(subproject: String, label: String, suites: Vector[String], seconds: Double)

  /** One shard of a split build: the parts it runs, longest first. */
  final case class Shard(number: Int, parts: Vector[Part]) {
    def seconds: Double = parts.map(_.seconds).sum

    /** `playbook`, `playbook, smrt` or `playbook, smrt, 9 more`. */
    def label: String = parts.map(_.label).toList match {
      case one :: Nil => one
      case one :: two :: Nil => s"$one, $two"
      case one :: two :: rest => s"$one, $two, ${rest.size} more"
      case Nil => ""
    }

    /** `<subproject>/<suite>`, the form `ci/shard.sh` takes. */
    def suites: Vector[String] = parts.flatMap(p => p.suites.map(s => s"${p.subproject}/$s"))
  }

  /** What a build does with its test phase: run it itself, or split it across runners. */
  sealed trait Plan {
    def seconds: Double
  }

  /** Runs its suites itself; `reason` says why, for the build log. */
  final case class Alone(seconds: Double, reason: String) extends Plan

  /** How finely a split build cut its subproject: each measured one into pieces of a share divided
    * by `measured`, each unmeasured one by [[UnmeasuredCut]]. `measured` is None when no subproject
    * of the build has a start measured, and there was no choice to make.
    */
  final case class Cut(measured: Option[Int], unmeasured: Int)

  /** Files one shard per entry, numbered from 1, longest first. `seconds` is the shards' planned
    * seconds summed, starts included.
    */
  final case class Split(seconds: Double, shards: Vector[Shard], cut: Cut = Cut(None, UnmeasuredCut)) extends Plan

  /** The cuts a split build chooses among for a subproject whose start is measured: pieces of a whole
    * share, a half, a quarter and an eighth (design DES-94295, "How finely a subproject is cut").
    */
  val Cuts: Vector[Int] = Vector(1, 2, 4, 8)

  /** The cut of a subproject with no measured start: half a share, the smallest the measured suite
    * seconds support with a start cost up to a quarter of a share. Weighing an unknown start as zero
    * would always choose the eighth, which nothing measured vouches for.
    */
  val UnmeasuredCut: Int = 2

  /** The shard plan for a build whose suites are `suites`, by subproject, weighted by `timings`
    * and, for a suite `timings` does not list, by its median.
    *
    * A build splits at [[SplitAtSeconds]] into one shard per [[SplitShardSeconds]], between
    * [[SplitMinShards]] and [[SplitMaxShards]]. With a Prepare's `sizing` a build always splits, [[Exactly]] into the
    * repository's shard count (ISS-75612), each holding an equal share of the measured total, and only fewer when the
    * suites are too few to fill them, one shard per suite.
    *
    * Suites are kept with their subproject, because each part a shard holds starts its own forked Play JVM: a
    * subproject that fits in its piece of a share is one part, and a larger one is cut into equal parts by [[assign]].
    * The parts are then placed longest first, each onto the least-loaded shard so far, every part weighed as its
    * suites plus its subproject's start seconds. A subproject whose start is measured is cut at each of [[Cuts]] and
    * the plan whose slowest shard is lowest is kept, the coarser on a tie: a finer cut wins exactly while the suite
    * seconds it moves off the slowest shard exceed the starts it adds. One with no start measured is fixed at
    * [[UnmeasuredCut]] and takes no part in the choice. With nothing measured there is no weight to split by, and the
    * build runs alone.
    */
  def plan(
    suites: Map[String, Seq[String]],
    timings: Map[String, Double],
    sizing: Option[Exactly] = None,
  ): Plan =
    median(timings) match {
      case None => Alone(0.0, "nothing measured in the shard timings")
      case Some(unlisted) =>
        val bySubproject =
          suites.toVector.map { case (sub, names) => sub -> names.distinct.toVector }.filter(_._2.nonEmpty).sortBy(_._1)
        def weigh(names: Seq[String]): Double = names.map(s => timings.getOrElse(s, unlisted)).sum
        val total = bySubproject.map { case (_, names) => weigh(names) }.sum
        if (sizing.isEmpty && total < SplitAtSeconds) Alone(total, s"under ${SplitAtSeconds.toInt} usual seconds")
        else {
          val count = sizing match {
            case Some(Exactly(shards)) => math.max(1, shards)
            case None => math.max(SplitMinShards, math.min(SplitMaxShards, math.ceil(total / SplitShardSeconds).toInt))
          }
          val share = total / count
          val starts = startSeconds(timings)
          def partsAt(measuredCut: Int): Vector[Part] = bySubproject.flatMap { case (sub, names) =>
            val (piece, start) = starts.get(sub) match {
              case Some(s) => (share / measuredCut, s)
              case None => (share / UnmeasuredCut, 0.0)
            }
            val weight = weigh(names)
            if (weight <= piece) Vector(Part(sub, sub, names.sorted, weight + start))
            else {
              val cut = assign(names, timings, math.ceil(weight / piece).toInt).filter(_.nonEmpty)
              cut.zipWithIndex.map { case (placed, i) =>
                Part(sub, s"$sub ${i + 1} of ${cut.size}", placed, weigh(placed) + start)
              }
            }
          }
          val anyMeasured = bySubproject.exists { case (sub, _) => starts.contains(sub) }
          val candidates =
            if (anyMeasured) Cuts.map(c => Some(c) -> place(partsAt(c), count))
            else Vector(None -> place(partsAt(UnmeasuredCut), count))
          // `minBy` keeps the first of equals, and the cuts run coarsest first.
          val (chosen, shards) = candidates.minBy { case (_, shards) => slowest(shards) }
          if (shards.size < SplitMinShards && sizing.isEmpty) Alone(total, "its suites fit one shard")
          else Split(shards.map(_.seconds).sum, shards, Cut(chosen, UnmeasuredCut))
        }
    }

  /** The planned seconds of the slowest of `shards`; 0 for none. */
  private def slowest(shards: Vector[Shard]): Double = if (shards.isEmpty) 0.0 else shards.map(_.seconds).max

  /** `parts` onto at most `count` shards by [[pack]], numbered from 1. */
  private def place(parts: Vector[Part], count: Int): Vector[Shard] =
    pack(parts, count)(_.seconds, _.label).zipWithIndex.map { case (ps, i) => Shard(i + 1, ps) }

  /** THE PACKING a split build places its parts with: `items` onto at most `count` shards, longest first (equal
    * seconds in label order), each onto the least-loaded shard so far (equal loads onto the earliest). Answers the
    * shards that hold any, longest first (equal totals in the order of their first labels), each holding its items
    * longest first. It is greedy, not optimal: `3 3 2 2 2` into two packs `7` and `5`, not `6` and `6`.
    *
    * Platform re-splits past builds with a reimplementation of it to estimate a shard count (design ISS-119228,
    * "The estimate"), so both are held to one case file, the `ci/shard-packing-cases` dry-copy region of
    * `testkit/src/test/resources/shard_packing.cases`: a change here fails `TestShardsSpec` until the cases change,
    * and `dev repo copies` reports every copy that has not followed.
    */
  def pack[A](items: Vector[A], count: Int)(seconds: A => Double, label: A => String): Vector[Vector[A]] = {
    def total(as: Vector[A]): Double = as.map(seconds).sum
    val placed = Array.fill(count)(Vector.empty[A])
    items.sortBy(a => (-seconds(a), label(a))).foreach { item =>
      val target = placed.indices.minBy(i => (total(placed(i)), i))
      placed(target) = placed(target) :+ item
    }
    placed.toVector
      .filter(_.nonEmpty)
      .map(as => as.sortBy(a => (-seconds(a), label(a))))
      .sortBy(as => (-total(as), label(as.head)))
  }

  /** What a build's scope names to plan over (ISS-36226): `ciShardPlan`'s arguments after the plan file, as
    * `scripts/affected-subprojects --format shard-scope` writes them. `<subproject>` plans every suite of the
    * subproject; `<subproject>:<package>` only its suites in the Scala package `<subproject>.<package>`, which is how
    * integrations is narrowed by package. A subproject named whole and by package is whole. Answers each subproject
    * with the packages it is narrowed to, `None` for all of it.
    */
  def scope(tokens: Seq[String]): Map[String, Option[Set[String]]] =
    tokens.map(_.trim).filter(_.nonEmpty).foldLeft(Map.empty[String, Option[Set[String]]]) { (acc, token) =>
      token.split(":", 2).toList match {
        case sub :: pkg :: Nil if pkg.nonEmpty =>
          acc.get(sub) match {
            case Some(None) => acc
            case Some(Some(pkgs)) => acc + (sub -> Some(pkgs + pkg))
            case None => acc + (sub -> Some(Set(pkg)))
          }
        case sub :: _ => acc + (sub -> None)
        case Nil => acc
      }
    }

  /** The suites of `suites` a scope ([[scope]]) plans: a subproject it does not name has none, and a narrowed one only
    * those in its packages.
    */
  def scoped(suites: Map[String, Seq[String]], scope: Map[String, Option[Set[String]]]): Map[String, Seq[String]] =
    suites.flatMap { case (sub, names) =>
      scope.get(sub) match {
        case None => None
        case Some(None) => Some(sub -> names)
        case Some(Some(pkgs)) => Some(sub -> names.filter(n => pkgs.exists(p => n.startsWith(s"$sub.$p."))))
      }
    }

  /** The plan file a split build hands `dev ci shards run`: a `#` header, then one line per shard,
    * `<number> TAB <usual seconds> TAB <label> TAB <subproject>/<suite> ...`, the suites separated by
    * single spaces. A line is all a runner needs to run its shard: `ci/shard.sh` takes the fourth
    * field as its arguments.
    */
  def render(split: Split): String = {
    val header = f"# ci-shard-plan 1: ${split.shards.size} shards, ${split.seconds}%.0f usual seconds"
    val lines = split.shards.map { s =>
      f"${s.number}\t${s.seconds}%.0f\t${s.label}\t${s.suites.mkString(" ")}"
    }
    (header +: lines).mkString("", "\n", "\n")
  }

  /** The lines the build log carries for a plan, which is how a person reads what it decided. */
  def summary(plan: Plan): Vector[String] = plan match {
    case Alone(seconds, reason) => Vector(f"ci-shard-plan: alone ${seconds}%.0f usual seconds, $reason")
    case Split(seconds, shards, cut) =>
      (f"ci-shard-plan: split ${shards.size} shards, ${seconds}%.0f usual seconds" +: describe(cut) +:
        shards.map(sh =>
          f"ci-shard-plan: shard ${sh.number} ${sh.seconds}%.0fs ${sh.label} (${sh.suites.size} suites)"
        ))
  }

  /** The cut a split chose, for the build log. */
  def describe(cut: Cut): String = cut.measured match {
    case None => s"ci-shard-plan: cut every subproject at 1/${cut.unmeasured} of a share, no start measured"
    case Some(c) =>
      s"ci-shard-plan: cut at 1/$c of a share where a start is measured, 1/${cut.unmeasured} where none is"
  }

  /** The lane a shard's database is named for: `<subproject>__<n>`, which `TestDatabases` turns into
    * `<session database>__<subproject>__<n>`.
    */
  def lane(subproject: String, shard: Int): String = s"${subproject}__$shard"
}
