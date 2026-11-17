package testkit

import java.io.File
import java.net.{ServerSocket, Socket}
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.must.Matchers

import scala.util.control.NonFatal

/** Reproduces the ISS-1380 wedge deterministically, and proves the shim releases it.
  *
  * No fork and no race are needed to get into the wedged state: it is simply a master ScalaTest
  * runner whose `remoteArgs` has been called — which is what opens the acceptor socket — and which
  * is then asked for `done()` with nothing on the other end. That is the state in the thread dump
  * at `~/code/claude/plans/data/iss-761-run04-wedge-jstack.txt`; the 1-in-10 race in CI only
  * decides how often a real run arrives here.
  */
class ForkSafeScalaTestFrameworkSpec extends AnyFunSpec with Matchers {

  import ForkSafeScalaTestFrameworkSpec.*

  private val ShortTimeoutMillis = 2000L

  /** How long a released join is given here. Deliberately far above the shipped
    * `UnwedgeGraceMillis`, and it is NOT a timing assertion: the join returns in milliseconds when
    * the unwedge worked, so this is only the point past which we would rather fail the test than
    * hang it. The shipped 30s is sized against `ci/stall-watchdog`, and this subproject is
    * `Test / fork := false` — it runs in the sbt JVM, next to whatever the build is compiling — so
    * borrowing that number is what let a stalled JVM report a working unwedge as a broken one.
    */
  private val PatientGraceMillis = 300000L

  /** The shipped framework with a timeout a test can afford to wait out, and a grace no amount of
    * load on this machine can starve it out of.
    */
  private class Impatient extends ForkSafeScalaTestFramework {
    override protected def doneTimeoutMillis: Long = ShortTimeoutMillis
    override protected def unwedgeGraceMillis: Long = PatientGraceMillis
  }

  /** A master runner, reporting to a throwaway file so the nested run prints nothing to stdout and
    * cannot be mistaken for this suite's own output.
    */
  private def masterRunner(framework: sbt.testing.Framework): sbt.testing.Runner = {
    val report = File.createTempFile("fork-safe-scalatest", ".txt")
    report.deleteOnExit()
    framework.runner(Array("-f", report.getAbsolutePath), Array.empty[String], getClass.getClassLoader)
  }

  private def timed[A](f: => A): (A, Long) = {
    val start = System.currentTimeMillis()
    val result = f
    (result, System.currentTimeMillis() - start)
  }

  describe("ForkSafeScalaTestFramework") {

    it("presents itself to sbt as ScalaTest") {
      val stock = new org.scalatest.tools.Framework
      val shim = new ForkSafeScalaTestFramework

      shim.name() mustBe stock.name
      shim.fingerprints().length mustBe stock.fingerprints.length
    }

    it("delegates done() untouched when there is no acceptor to join") {
      // The fork's own runner, and any in-process run: sbt never asks these for remote args, so
      // ScalaTest never opens a socket and done() has nothing to block on.
      val runner = masterRunner(new Impatient)
      val summary = new AtomicReference[Option[String]](None)

      val worker = new Thread(() => summary.set(Some(runner.done())), "fork-safe-scalatest-no-acceptor")
      worker.setDaemon(true)
      worker.start()
      worker.join(PatientGraceMillis)

      // What "untouched" means is the RETURN VALUE, not the clock: done() came back on its own with
      // ScalaTest's summary, never the shim's diagnostic. The join bound is only where we would
      // rather fail than hang; a wall-clock upper bound here was falsifiable by any stall of the sbt
      // JVM this subproject runs in (ISS-4624, ISS-24408).
      worker.isAlive mustBe false
      summary.get must not be empty
      summary.get.foreach(_ must not include "[fork-safe-scalatest]")
    }

    it("releases done() when the forked JVM never dials back") {
      val runner = masterRunner(new Impatient)
      val remote = runner.remoteArgs()
      remote.length mustBe 2
      val port = remote(1).toInt

      val (summary, elapsed) = timed(runner.done())

      // What "released" means is the RETURN VALUE, not the clock: a released worker hands back
      // ScalaTest's own run summary, and only the grace join expiring substitutes the diagnostic.
      // Asserting a wall-clock UPPER bound instead made the same claim in the one observable that
      // a stall in this JVM can falsify on its own (ISS-4624). Matched on the headline so that,
      // when it fails, the message prints the whole diagnostic — including what the unwedge's dial
      // hit and who holds the port, which is the only record of why (ISS-10517).
      summary must not startWith ForkSafeRunner.stillWedgedHeadline(port)

      // Lower bound only. That the shim WAITED for its timeout before unwedging is a property of
      // this code, not of how loaded the machine is.
      elapsed must be >= ShortTimeoutMillis
    }

    // The other side of the assertion above: what a done() that did NOT release looks like. This
    // is what makes "the summary is not the diagnostic" a discriminating claim rather than a
    // tautology, and it is the only coverage of the framework's third documented step — hand the
    // build a diagnostic in place of the summary rather than blocking it.
    it("returns the diagnostic, rather than blocking, when even the unwedge cannot free done()") {
      val neverReleases = new CountDownLatch(1)
      val port = unusedPort()
      // A short grace here: the connect fails immediately, so nothing waits it out, and the point
      // of this test is the value that comes back rather than how long it took.
      val runner = new ForkSafeRunner(new WedgedRunner(neverReleases, port), ShortTimeoutMillis, ShortTimeoutMillis)
      runner.remoteArgs().length mustBe 2

      try {
        val (summary, elapsed) = timed(runner.done())

        summary must startWith(ForkSafeRunner.stillWedgedHeadline(port))
        elapsed must be >= ShortTimeoutMillis

        // The cause travels IN the diagnostic, not only to stderr ahead of it, where a build log's
        // failure excerpt does not reach (ISS-10517).
        summary must include(s"could not reach the acceptor on 127.0.0.1:$port")
        summary must include(classOf[java.net.ConnectException].getName)
      } finally neverReleases.countDown()
    }

    // ISS-24271. A fork's runner is the one handed remote args, and ScalaTest dials the acceptor in
    // its constructor; nothing listening on the port is the deterministic stand-in for the loopback
    // dial that timed out under load.
    it("builds a fork's runner that reports locally when it cannot dial the acceptor") {
      val report = File.createTempFile("fork-safe-scalatest", ".txt")
      report.deleteOnExit()
      val runner = new Impatient().runner(
        Array("-f", report.getAbsolutePath),
        Array("127.0.0.1", unusedPort().toString),
        getClass.getClassLoader
      )

      runner.tasks(Array.empty) mustBe empty
      runner.done() must not include "[fork-safe-scalatest]"
    }

    it("is load-bearing: stock ScalaTest fails a fork's runner that cannot dial the acceptor") {
      val stock = new org.scalatest.tools.Framework
      a[java.net.ConnectException] must be thrownBy {
        stock.runner(Array.empty, Array("127.0.0.1", unusedPort().toString), getClass.getClassLoader)
      }
    }

    it("still throws a fork's runner that fails on its arguments rather than on its socket") {
      an[IllegalArgumentException] must be thrownBy {
        new Impatient().runner(Array("-PS"), Array("127.0.0.1", unusedPort().toString), getClass.getClassLoader)
      }
    }

    it("recognises a dial failure however deeply it is wrapped") {
      val dial = new java.net.ConnectException("Operation timed out")
      ForkSafeScalaTestFramework.isDialFailure(dial) mustBe true
      ForkSafeScalaTestFramework.isDialFailure(new RuntimeException(new RuntimeException(dial))) mustBe true
      ForkSafeScalaTestFramework.isDialFailure(new IllegalArgumentException("-PS")) mustBe false
    }

    it("names a foreign holder of the acceptor's port as a hijack") {
      val verdict = ForkSafeRunner.ownershipVerdict(ForeignListener + OwnListener, OwnPid, Port)

      verdict must include(ForkSafeExitAgent.HijackMarker)
      verdict must include(s"workerd (pid 88616) on 127.0.0.1:$Port")
      verdict must include(s"beside ScalaTest's acceptor on java (pid $OwnPid) on *:$Port")
    }

    it("says so when this JVM is the only listener on the acceptor's port") {
      val verdict = ForkSafeRunner.ownershipVerdict(OwnListener, OwnPid, Port)

      verdict must include("only listener")
      verdict must include(s"java (pid $OwnPid)")
      verdict must not include ForkSafeExitAgent.HijackMarker
    }

    it("accuses no one when lsof names no listener") {
      ForkSafeRunner.ownershipVerdict(null, OwnPid, Port) mustBe ForkSafeExitAgent.Undetermined
      ForkSafeRunner.ownershipVerdict("", OwnPid, Port) mustBe ForkSafeExitAgent.Undetermined
      ForkSafeRunner.ownershipVerdict(HeaderOnly, OwnPid, Port) mustBe ForkSafeExitAgent.Undetermined
    }

    // This is also the tripwire for deleting the whole subproject (ISS-1400). Nothing here polls
    // ScalaTest, so this assertion — that the bug is still there — is the only thing that will
    // notice the day it is fixed.
    it("is load-bearing: stock ScalaTest never releases the same done()") {
      val runner = masterRunner(new org.scalatest.tools.Framework)
      val remote = runner.remoteArgs()

      val blocked = new Thread(() => { val _ = runner.done() }, "stock-scalatest-done")
      blocked.setDaemon(true)
      blocked.start()
      blocked.join(ShortTimeoutMillis * 2)

      try {
        // still parked on Thread.join, behind ServerSocket.accept
        withClue(ForkSafeScalaTestFrameworkSpec.UpstreamMayBeFixed)(blocked.isAlive mustBe true)
      } finally {
        releaseStockAcceptor(remote, blocked)
      }

      // Only on the path where the bug is still present, so that a genuine upstream fix reports as
      // the clue above rather than as a leaked thread.
      blocked.isAlive mustBe false
    }
  }

  /** A port with nothing listening on it, so the shim's unwedge connect fails and it falls through
    * to its last resort. Bound and released — the window for something else to take it is the same
    * one every ephemeral-port test lives with, and losing it would make this test pass for the
    * wrong reason rather than fail.
    */
  private def unusedPort(): Int = {
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }

  /** A master runner whose `done()` never returns — the wedge, without needing ScalaTest to be in
    * it — reporting an acceptor address that nothing is listening on.
    */
  private class WedgedRunner(release: CountDownLatch, port: Int) extends sbt.testing.Runner {
    override def args(): Array[String] = Array.empty
    override def remoteArgs(): Array[String] = Array("127.0.0.1", port.toString)
    override def tasks(taskDefs: Array[sbt.testing.TaskDef]): Array[sbt.testing.Task] = Array.empty
    override def done(): String = {
      release.await()
      "released"
    }
  }

  /** Leave nothing behind: ScalaTest's acceptor is a NON-daemon thread and this suite may be
    * running in the sbt JVM, which does not exit at the end of a test run.
    *
    * Best effort, and deliberately not asserted here. This runs in a `finally`, so anything thrown
    * would REPLACE the assertion that sent us here and hide why it failed — and the fix we are
    * watching for is plausibly "close the ServerSocket before joining", which makes this very
    * connect throw.
    */
  private def releaseStockAcceptor(remote: Array[String], blocked: Thread): Unit =
    try {
      val socket = new Socket(remote(0), remote(1).toInt)
      try ForkSafeRunner.sendRunCompleted(socket)
      finally socket.close()
      blocked.join(ForkSafeScalaTestFramework.UnwedgeGraceMillis)
    } catch {
      case NonFatal(_) => ()
    }
}

private object ForkSafeScalaTestFrameworkSpec {

  /** Printed when stock ScalaTest's `done()` returns on its own. There is only one thing that
    * means, and this is the whole reason the assertion above is worth keeping.
    */
  val UpstreamMayBeFixed: String =
    "Stock ScalaTest released done() with no fork on the other end, so the liveness bug this " +
      "subproject exists to work around (ISS-1380) looks FIXED upstream. If it is: delete " +
      "`testkit`, scala-build-app's `ForkSafeTestFrameworkPlugin.scala` and the two " +
      "`dependsOn(testkit % Test)` lines in build.sbt, keeping the scalatest bump that fixed it. " +
      "`ci/stall-watchdog` stays either way. See ISS-1400."

  /** Fixed `lsof` text, so the ownership verdict is asserted against known input and not against
    * whatever happens to be listening on the machine running this suite.
    */
  val Port: Int = 51853

  val OwnPid: Long = 4242L

  val HeaderOnly: String = "COMMAND     PID   USER   FD   TYPE  DEVICE SIZE/OFF NODE NAME\n"

  val ForeignListener: String =
    HeaderOnly + s"workerd   88616 athena   23u  IPv4 0x1a2b      0t0  TCP 127.0.0.1:$Port (LISTEN)\n"

  val OwnListener: String =
    s"java      $OwnPid athena   45u  IPv6 0x3c4d      0t0  TCP *:$Port (LISTEN)\n"
}
