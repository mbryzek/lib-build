package testkit

import java.io.ObjectOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

import org.scalatest.Tracker
import org.scalatest.events.RunCompleted
import sbt.testing.{Fingerprint, Framework, Runner, Task, TaskDef}

import scala.util.control.NonFatal

/** ScalaTest, with the ONE liveness bug that wedges this build's CI taken out of it (ISS-1380).
  *
  * ===The bug===
  *
  * With `Test / fork := true`, sbt runs each subproject's suites in a forked JVM and keeps a MASTER
  * ScalaTest runner in the sbt JVM to collect the events. The two are wired by a socket that
  * ScalaTest — not sbt — owns: the master's `remoteArgs` opens a `ServerSocket`, starts an
  * acceptor thread on it, and returns `Array("127.0.0.1", port)`; sbt ships that pair to the fork,
  * whose `SocketReporter` dials back and streams the run's events over it. When the fork is
  * finished, sbt calls `done()` on the master, and `done()` ends with:
  *
  * {{{
  *   serverThread.get match {
  *     case Some(thread) => thread.join()   // Framework.scala:771, no timeout
  *     case None =>
  *   }
  * }}}
  *
  * The acceptor thread it joins exits on exactly one event: a `RunCompleted` arriving over that
  * socket. If the fork never dials back — or dials back, delivers, and dies without sending
  * `RunCompleted` — the acceptor blocks in `ServerSocket.accept()` forever, `done()` blocks on the
  * join forever, the test task never reports complete, and sbt's task engine waits on the task.
  * There is no timeout anywhere on that path and the `ServerSocket` is never closed to break it.
  *
  * That is not a hypothesis. It is the thread dump `ci/stall-watchdog` collected from a wedged run
  * on unmodified `main`, kept at `~/code/claude/plans/data/iss-761-run04-wedge-jstack.txt`: sbt's
  * test task in `Framework$ScalaTestRunner.done` -> `Thread.join`, and beside it one RUNNABLE
  * thread in `Framework$Skeleton$1.socket` -> `ServerSocket.accept`, with no `sbt.ForkMain` process
  * left to connect to it. The measured rate is one warm full-suite run in ten, which at ~65 merged
  * pull requests a day is six or seven red `ci` runs a day on branches whose suites were green —
  * the largest single source of red in the merge lane, with zero failing tests behind it.
  *
  * ===Why this is fixed here and not upstream===
  *
  * `done` and `Skeleton` — the two pieces above — are byte-identical on 3.2.17, on 3.2.18, on
  * 3.2.19, on 3.2.20 (the latest release, published 2026-03-26) and on ScalaTest's `main` as of
  * 2026-08-09, so there is no version to upgrade to. The FILE does differ on `main`, in `tasks` and
  * in an `Args` signature; neither goes near this, and the bug reproduces first-hand against 3.2.20
  * in fifteen seconds. Reported upstream from ISS-1400.
  *
  * Overriding the framework sbt loads is the only seam that reaches the join, and every other
  * approach considered is worse: `Test / fork := false` gives up the per-subproject
  * `config.resource` and database isolation the build depends on, and retrying the whole suite when
  * the watchdog fires still pays the eight-minute detection each time and fixes nothing.
  *
  * ===When ScalaTest fixes it===
  *
  * Delete this subproject, scala-build-app's `ForkSafeTestFrameworkPlugin.scala` and the two
  * `dependsOn(testkit % Test)` lines in `build.sbt`, and keep the scalatest bump.
  * `ci/stall-watchdog` stays either way. Nothing here polls upstream, so the thing that will
  * NOTICE is `ForkSafeScalaTestFrameworkSpec`'s last test: it asserts the bug is still present, so
  * it goes red on the first build that has the fix, and its failure message is that checklist.
  *
  * ===What this does===
  *
  * Everything delegates to `org.scalatest.tools.Framework`. The only behaviour added is around
  * `done()`, and only on a runner whose `remoteArgs` was actually called — that is, only on the
  * master runner of a forked test task, never in the fork and never for an in-process run:
  *
  *   1. run `done()` on a worker thread and wait `doneTimeoutMillis` for it;
  *   2. if it is still blocked, dial the acceptor's own socket and hand it the `RunCompleted` it is
  *      waiting for. That is the documented end of ScalaTest's own protocol — the acceptor returns,
  *      the join returns, and `done()` finishes normally, printing the summary and disposing its
  *      reporters;
  *   3. if even that does not free it, return a diagnostic in place of the summary and let the
  *      build continue. The diagnostic names why: what the unwedge's dial hit, and which processes
  *      are listening on the acceptor's port.
  *
  * One more behaviour is added on the other end of that socket, in the FORK's runner (ISS-24271):
  * the fork's `runner(args, remoteArgs, ...)` is where ScalaTest's `SocketReporter` dials the
  * acceptor, in its constructor, and a dial that fails there — measured once as a loopback connect
  * timing out on a loaded runner, against a listener this JVM still held two minutes later — throws
  * out of `runner` and fails the whole forked test group before a single suite has run. So a dial
  * failure is caught, said on stderr, and the fork's runner is built again with no remote args:
  * its suites run, their results reach sbt over sbt's own socket as always, and ScalaTest's report
  * of them prints from the fork instead of travelling to the master. The master's acceptor for that
  * fork then never hears from it, which is exactly the wedge `done()` already releases.
  *
  * The pass/fail of the subproject is NOT affected by any of this. Test results reach sbt over
  * sbt's own fork socket (`sbt.ForkTests`), which has already delivered them by the time `done()`
  * is called; this socket carries ScalaTest's reporting only.
  *
  * `ci/stall-watchdog` stays exactly as it was. This removes the only wedge we have an artifact
  * for; the watchdog is what bounds the ones we do not.
  */
class ForkSafeScalaTestFramework extends Framework {

  private val underlying: Framework = new org.scalatest.tools.Framework

  /** Overridable so a test can drive the timeout path in seconds rather than minutes. */
  protected def doneTimeoutMillis: Long = ForkSafeScalaTestFramework.configuredDoneTimeoutMillis

  /** Overridable for the same reason, in the other direction: a test that asserts the unwedge
    * WORKED needs a grace it cannot be starved out of, since the shipped 30s is sized against
    * `ci/stall-watchdog` rather than against how long a released join can take on a loaded box.
    */
  protected def unwedgeGraceMillis: Long = ForkSafeScalaTestFramework.UnwedgeGraceMillis

  override def name(): String = underlying.name()

  override def fingerprints(): Array[Fingerprint] = underlying.fingerprints()

  /** In a fork, also where the fork's own lane database is cloned, before any suite can open it
    * ([[ShardDatabase]], ISS-25743), and where a shard's start seconds are written once its suites
    * are about to run ([[PartStart]]).
    */
  override def runner(args: Array[String], remoteArgs: Array[String], testClassLoader: ClassLoader): Runner = {
    ShardDatabase.ensure()
    val runner = dialingRunner(args, remoteArgs, testClassLoader)
    PartStart.record()
    new ForkSafeRunner(runner, doneTimeoutMillis, unwedgeGraceMillis)
  }

  /** ScalaTest's runner, falling back to one that reports locally when it cannot dial the acceptor.
    *
    * Only a FORK's runner is handed remote args, so only a fork can reach the fallback. Building the
    * runner a second time is safe: the failed dial is the first thing with an effect outside the
    * object being built, and nothing reached the acceptor for it to have to forget.
    */
  private def dialingRunner(args: Array[String], remoteArgs: Array[String], testClassLoader: ClassLoader): Runner =
    try underlying.runner(args, remoteArgs, testClassLoader)
    catch {
      case NonFatal(e) if remoteArgs.nonEmpty && ForkSafeScalaTestFramework.isDialFailure(e) =>
        System.err.println(ForkSafeScalaTestFramework.acceptorUnreachable(remoteArgs, e))
        underlying.runner(args, Array.empty, testClassLoader)
    }
}

object ForkSafeScalaTestFramework {

  /** Set `-Dplatform.test.done.timeout.millis=...` on the SBT JVM to change it; this code runs in
    * the sbt process, not in the fork.
    */
  val DoneTimeoutProperty: String = "platform.test.done.timeout.millis"

  /** Two minutes. `done()` is called only after sbt has already reaped the forked JVM and joined
    * its own acceptor, so the legitimate remaining wait is however long it takes to drain events
    * already in flight — milliseconds. Two minutes is far past any plausible drain and still well
    * inside `ci/stall-watchdog`'s 480s, so this releases the build before the watchdog kills it.
    */
  val DefaultDoneTimeoutMillis: Long = 120000L

  /** How long to wait for the join to release after the acceptor has been handed its `RunCompleted`.
    *
    * The join normally returns in milliseconds, so this is not a budget for the release — it is the
    * point at which we would rather hand the build a diagnostic than keep holding it. 30s keeps
    * that decision well inside `ci/stall-watchdog`'s 480s.
    */
  val UnwedgeGraceMillis: Long = 30000L

  def configuredDoneTimeoutMillis: Long =
    sys.props.get(DoneTimeoutProperty).flatMap(_.toLongOption).getOrElse(DefaultDoneTimeoutMillis)

  /** Whether building a fork's runner failed on its socket rather than on anything else. A bad
    * argument is still thrown: running the suites under a runner that misread its arguments is
    * worse than failing the group.
    */
  def isDialFailure(e: Throwable): Boolean =
    Iterator.iterate(e)(_.getCause).takeWhile(_ != null).exists(_.isInstanceOf[java.io.IOException])

  def acceptorUnreachable(remoteArgs: Array[String], e: Throwable): String =
    s"[fork-safe-scalatest] this forked JVM could not dial ScalaTest's acceptor on ${remoteArgs.mkString(":")}: $e. " +
      "Running its suites with ScalaTest's report printed here instead (ISS-24271). Test results are " +
      "unaffected — they reach sbt over sbt's own fork socket."
}

/** Delegates every method of `sbt.testing.Runner`, bounding only `done()`. See
  * [[ForkSafeScalaTestFramework]] for what is wrong and why it is fixed here.
  */
private[testkit] final class ForkSafeRunner(underlying: Runner, doneTimeoutMillis: Long, unwedgeGraceMillis: Long)
  extends Runner {

  /** The acceptor this runner will block on in `done()`, learned from the value `remoteArgs`
    * returned. Empty on a runner sbt never asked for remote args — the fork's own runner, and any
    * in-process run — and those are exactly the runners whose `done()` has nothing to join.
    */
  private val acceptor = new AtomicReference[Option[(String, Int)]](None)

  override def args(): Array[String] = underlying.args()

  override def tasks(taskDefs: Array[TaskDef]): Array[Task] = underlying.tasks(taskDefs)

  /** Called ONCE per forked test group, by `sbt.ForkTests`, and it has a side effect in ScalaTest:
    * it is what opens the `ServerSocket` and starts the acceptor thread. Calling it a second time
    * would open a second one, so this delegates exactly once and only records what came back.
    */
  override def remoteArgs(): Array[String] = {
    val remote = underlying.remoteArgs()
    remote.toList match {
      case host :: port :: _ => port.toIntOption.foreach(p => acceptor.set(Some((host, p))))
      case _ => ()
    }
    remote
  }

  override def done(): String = acceptor.get match {
    case None => underlying.done()
    case Some((host, port)) => doneWithinDeadline(host, port)
  }

  private def doneWithinDeadline(host: String, port: Int): String = {
    val outcome = new AtomicReference[Option[Either[Throwable, String]]](None)
    val worker = new Thread(
      () => {
        try outcome.set(Some(Right(underlying.done())))
        catch { case t: Throwable => outcome.set(Some(Left(t))) }
      },
      s"fork-safe-scalatest-done-$port"
    )
    worker.setDaemon(true)
    worker.start()
    worker.join(doneTimeoutMillis)
    val attempt = Option.when(worker.isAlive)(unwedge(host, port, worker))

    outcome.get match {
      case Some(Right(summary)) => summary
      case Some(Left(t)) => throw t
      case None =>
        // Only reachable through `unwedge`: a worker that has exited has already set its outcome.
        val diagnostic = ForkSafeRunner.stillWedgedSummary(port, attempt.mkString + ForkSafeRunner.portOwnership(port))
        System.err.println(diagnostic)
        diagnostic
    }
  }

  /** Hands the stuck acceptor the one event that ends its loop, and says what became of the attempt.
    *
    * This is ScalaTest's own protocol and nothing more: `Framework$Skeleton`'s reader returns on
    * `RunCompleted` and lets its thread terminate. Connecting is what releases an acceptor stuck in
    * `accept()`; the event is what releases one stuck in `readObject`. Both shapes end the same
    * way, so this does not need to know which one it hit.
    *
    * The sentence it returns is read only when `done()` stayed blocked, and it goes INTO the
    * diagnostic rather than only onto stderr ahead of it: a build log's failure excerpt carries the
    * lines under a failure, not the ones before it, so a cause printed separately is the half that
    * gets cut (ISS-10517).
    */
  private def unwedge(host: String, port: Int, worker: Thread): String = {
    System.err.println(ForkSafeRunner.wedgeDetected(port, doneTimeoutMillis))
    try {
      val socket = new Socket(host, port)
      try {
        ForkSafeRunner.sendRunCompleted(socket)
        worker.join(unwedgeGraceMillis)
      } finally socket.close()
      ForkSafeRunner.delivered(host, port, unwedgeGraceMillis)
    } catch {
      case NonFatal(e) =>
        System.err.println(s"[fork-safe-scalatest] could not reach the acceptor on $host:$port: $e")
        ForkSafeRunner.unreachable(host, port, e)
    }
  }
}

private[testkit] object ForkSafeRunner {

  /** Writes the `RunCompleted` that ends `Framework$Skeleton`'s read loop.
    *
    * The flush after constructing the stream is load-bearing and not defensive: the acceptor builds
    * an `ObjectInputStream` around its end of this socket, and that constructor BLOCKS until the
    * stream header arrives. sbt's own fork plumbing carries the same flush, with the same comment.
    */
  def sendRunCompleted(socket: Socket): Unit = {
    val out = new ObjectOutputStream(socket.getOutputStream)
    out.flush()
    out.writeObject(RunCompleted(new Tracker().nextOrdinal()))
    out.flush()
  }

  def wedgeDetected(port: Int, timeoutMillis: Long): String =
    s"[fork-safe-scalatest] ScalaTest's master runner has been blocked in done() for ${timeoutMillis}ms " +
      s"waiting for a forked JVM to dial 127.0.0.1:$port that never will (ISS-1380). " +
      "Test results are unaffected — they arrive over sbt's own fork socket. Releasing it."

  /** The fixed start of every still-wedged diagnostic, so a reader — and the spec — can recognise
    * one whatever cause follows it.
    */
  def stillWedgedHeadline(port: Int): String =
    s"[fork-safe-scalatest] ScalaTest's acceptor on port $port did not release (ISS-1380); " +
      "no run summary for this subproject. Its test results are unaffected."

  def stillWedgedSummary(port: Int, cause: String): String = s"${stillWedgedHeadline(port)} $cause"

  def unreachable(host: String, port: Int, e: Throwable): String =
    s"The unwedge could not reach the acceptor on $host:$port: $e."

  def delivered(host: String, port: Int, graceMillis: Long): String =
    s"The unwedge delivered RunCompleted to $host:$port and done() had still not returned ${graceMillis}ms later."

  /** WHO IS LISTENING on the acceptor's port, asked of the machine once `done()` has refused to
    * release. The failure this tells apart is the one `ForkSafeExitAgent` names from the fork's side
    * of sbt's socket, reached here through ScalaTest's: the acceptor binds the WILDCARD, which does
    * not reserve the port, so a process holding `127.0.0.1` on it takes every dial of that address —
    * the unwedge's as well as the fork's. Paid only on a `done()` that is already wedged.
    */
  def portOwnership(port: Int): String =
    try
      ownershipVerdict(
        ForkSafeExitAgent.listeners(port, ForkSafeExitAgent.LsofBinary, ForkSafeExitAgent.OwnerTimeoutMillis),
        ProcessHandle.current().pid(),
        port
      )
    catch {
      case NonFatal(t) =>
        s"${ForkSafeExitAgent.Undetermined} ${ForkSafeExitAgent.LookupUnanswered}: ${ForkSafeExitAgent.because(t)}."
    }

  /** The verdict, kept pure so it is asserted against fixed `lsof` text. Here the rightful holder
    * is the JVM asking — ScalaTest's acceptor lives in it — rather than a fork's parent.
    */
  def ownershipVerdict(lsofOutput: String, ownPid: Long, port: Int): String = {
    val holders = ForkSafeExitAgent.holders(lsofOutput, ownPid, port)
    val foreign = holders(ForkSafeExitAgent.Foreign)
    val own = holders(ForkSafeExitAgent.Own)
    if (foreign.isEmpty && own.isEmpty) ForkSafeExitAgent.Undetermined
    else if (foreign.isEmpty)
      s" The only listener on port $port is this JVM — $own — so no other process was in the way."
    else
      s" ${ForkSafeExitAgent.HijackMarker}: port $port is held by $foreign, which is not this JVM " +
        s"(pid $ownPid)" + (if (own.isEmpty) "" else s", beside ScalaTest's acceptor on $own") +
        ". The acceptor binds the WILDCARD, which does not reserve the port, and the kernel delivers each " +
        "connection to the MOST SPECIFIC matching listener — so a dial of 127.0.0.1 reaches that process " +
        "rather than the acceptor, the unwedge's and the fork's alike. Free that port on this machine; " +
        "retrying the build only redraws the same lottery."
  }
}
