package cilists

/** What `ciTest`'s arguments ask for: which subprojects' tests run, in which phases, and whether a
  * spec that fails its rerun stops the build (ISS-25565, ISS-103214).
  *
  * {{{
  *   ciTest [--stop] [<subproject>...] [-- <subproject>...]
  * }}}
  *
  * No subproject is every subproject, in one phase. The subprojects before `--` are the first phase
  * and those after it the second: a build puts the subprojects its change touched first, so a change
  * that breaks its own subproject is red without waiting for the rest of the suite. It is an ORDER and
  * never a scope: the two phases together are exactly the subprojects the build was handed, and which
  * ones go first is the caller's to say, since it is a question about the diff and not about the
  * build.
  *
  * A FLAKE never stops anything: a phase whose failed specs all pass their rerun is followed by the
  * next. A spec that fails its rerun, or a phase that failed in a way no rerun clears, leaves the build
  * red; with `--stop` the phases after it do not run, and each is named on a `ci-stopped:` line, which
  * is how a batch knows a green rerun of what failed did not measure them. Without `--stop` the later
  * phases still run, so every spec that failed gets its verdict; a phase no rerun can clear stops the
  * build either way, since what follows it was never what failed.
  */
object CiTestPhases {

  val Usage: String = "[--stop] [<subproject>...] [-- <subproject>...]"

  /** `phases` empty is every subproject, in one phase. */
  final case class Request(stop: Boolean, phases: Seq[Seq[String]])

  private val Subproject = "[A-Za-z0-9_][A-Za-z0-9_-]*".r

  def parse(args: Seq[String]): Either[String, Request] = {
    val stop = args.headOption.contains("--stop")
    val rest = if (stop) args.drop(1) else args
    val separators = rest.count(_ == "--")
    val (first, second) = rest.span(_ != "--")
    val phases = Seq(first, second.drop(1)).filter(_.nonEmpty)
    val named = phases.flatten
    val invalid = named.find(a => !Subproject.pattern.matcher(a).matches)
    val repeated = named.diff(named.distinct).distinct
    if (separators > 1) Left(s"more than one '--'; usage: ciTest $Usage")
    else if (separators == 1 && (first.isEmpty || second.size < 2))
      Left(s"'--' needs subprojects on both sides; usage: ciTest $Usage")
    else if (invalid.nonEmpty) Left(s"'${invalid.get}' is not a subproject; usage: ciTest $Usage")
    else if (repeated.nonEmpty) Left(s"${repeated.mkString(", ")} named twice; a phase is an order, never a repeat")
    else Right(Request(stop, phases))
  }

  /** The named subprojects this build does not define, in the order named. */
  def unknown(request: Request, defined: Set[String]): Seq[String] =
    request.phases.flatten.filterNot(defined)

  /** How one phase ended. */
  sealed trait Outcome
  object Outcome {

    /** Every suite passed the first time. */
    case object Green extends Outcome

    /** Some suites failed and every one of them passed its rerun. */
    case object Flaked extends Outcome

    /** A spec failed its rerun too. */
    case object FailedTwice extends Outcome

    /** The phase failed in a way no rerun reaches: a compile error, a task that is not a spec. */
    case object NotRerunnable extends Outcome
  }

  /** Whether the build is red after this outcome. */
  def red(outcome: Outcome): Boolean = outcome == Outcome.FailedTwice || outcome == Outcome.NotRerunnable

  /** Whether the next phase runs after this one ended so. */
  def proceeds(stop: Boolean, outcome: Outcome): Boolean = outcome match {
    case Outcome.Green | Outcome.Flaked => true
    case Outcome.FailedTwice => !stop
    case Outcome.NotRerunnable => false
  }

  /** The line naming a phase the build never started. */
  def stoppedLine(phase: Seq[String]): String = s"ci-stopped: ${phase.map(p => s"$p/test").mkString(" ")}"

  /** Runs `phases` in order with `run`, stopping as [[proceeds]] says, and answers the last state, every
    * outcome of a phase that ran, and the phases that never started.
    */
  def runAll[S, P](state: S, phases: Seq[P], stop: Boolean)(run: (S, P) => (S, Outcome)): (S, Seq[Outcome], Seq[P]) = {
    @annotation.tailrec
    def loop(current: S, left: Seq[P], outcomes: Seq[Outcome]): (S, Seq[Outcome], Seq[P]) =
      left match {
        case Seq() => (current, outcomes, Nil)
        case _ if outcomes.lastOption.exists(o => !proceeds(stop, o)) => (current, outcomes, left)
        case phase +: tail =>
          val (next, outcome) = run(current, phase)
          loop(next, tail, outcomes :+ outcome)
      }
    loop(state, phases, Nil)
  }
}
