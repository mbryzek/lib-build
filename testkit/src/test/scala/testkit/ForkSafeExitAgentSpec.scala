package testkit

import java.io.{File, ObjectInputStream, ObjectOutputStream}
import java.net.{BindException, InetAddress, ServerSocket, Socket}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.jar.{Attributes, JarEntry, JarOutputStream, Manifest as JarManifest}

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.must.Matchers

import scala.util.control.NonFatal

/** Reproduces the ISS-3439 wedge against upstream's own code, and proves the agent closes it.
  *
  * These run `sbt.ForkMain` — the real forked-test agent, from the `test-agent` artifact of the sbt
  * running this build — as a real subprocess, because the claim being tested is entirely about what
  * exit status that program reports. A model of it would only restate the belief it exists to
  * check. The sbt SIDE is modelled: it is four writes and a read
  * ([[ForkSafeExitAgentSpec.serveConfiguration]]), and the state that matters — a server socket
  * parked in `accept()` — is what you get by not writing them.
  */
class ForkSafeExitAgentSpec extends AnyFunSpec with Matchers {

  import ForkSafeExitAgentSpec.*

  /** Everything a stall report GUARANTEES about the position it sampled: a stack came out, and it
    * reaches `ForkMain`'s own frame.
    *
    * Which statement sits above that frame is a property of how loaded the machine is, not of the
    * mechanism — a JVM start slow enough to reach the stall threshold first has the sample land in
    * the connect that precedes `new ObjectInputStream` — and every position it can land in is the
    * same fact: the fork was parked before sbt configured it. An assertion naming one of them has
    * zero slack against the speed of the box it runs on.
    */
  private def mustNameAStalledFork(report: String, header: String): Unit = {
    val frames = renderedStack(report, header)
    withClue(s"stack under '$header' in:\n$report\n") {
      frames must not be empty
      frames.exists(_.startsWith("at sbt.ForkMain")) mustBe true
    }
  }

  describe("ForkSafeExitAgent") {

    it("is inert in a JVM that is not one of sbt's test forks") {
      // The one guard on the whole agent. `sun.java.command` for a forked test JVM is these two
      // words and nothing else; anything else must leave the JVM exactly as it was.
      ForkSafeExitAgent.forkMainPort("sbt.ForkMain 54321") mustBe 54321
      ForkSafeExitAgent.forkMainPort("play.core.server.ProdServerStart") mustBe -1
      ForkSafeExitAgent.forkMainPort("sbt.ForkMain") mustBe -1
      ForkSafeExitAgent.forkMainPort("sbt.ForkMain not-a-port") mustBe -1
      ForkSafeExitAgent.forkMainPort("sbt.ForkMain 0") mustBe -1
      ForkSafeExitAgent.forkMainPort(null) mustBe -1
    }

    it("makes a fork that could not dial sbt back exit non-zero, naming why") {
      val run = runForkMain(port = closedPort(), withAgent = true)

      run.status mustBe ForkSafeExitAgent.ExitStatus
      run.output must include("[fork-safe-exit-agent]")
      run.output must include("ISS-3439")
    }

    // The other half of the same claim, and the tripwire for deleting all of this. Nothing here
    // polls sbt, so this assertion — that upstream still reports a fork that ran nothing as a
    // success — is the only thing that will notice the day it stops being true.
    it("is load-bearing: stock sbt.ForkMain reports that same failure as success") {
      val run = runForkMain(port = closedPort(), withAgent = false)

      withClue(UpstreamMayBeFixed)(run.status mustBe 0)
    }

    it("reads its stall threshold off the agent argument, and survives anything else") {
      ForkSafeExitAgent.stallMillis("stall-millis=1500") mustBe 1500L
      ForkSafeExitAgent.stallMillis(null) mustBe ForkSafeExitAgent.DefaultStallMillis
      ForkSafeExitAgent.stallMillis("") mustBe ForkSafeExitAgent.DefaultStallMillis
      ForkSafeExitAgent.stallMillis("stall-millis=nope") mustBe ForkSafeExitAgent.DefaultStallMillis
      ForkSafeExitAgent.stallMillis("stall-millis=0") mustBe ForkSafeExitAgent.DefaultStallMillis
    }

    it("says so when it died too fast to have sampled a position") {
      ForkSafeExitAgent.lastSeen(null) must include("did not survive its first")
    }

    // The state ISS-3887's build was in, and the reason the agent alone was not enough to explain
    // it: sbt held an OPEN server socket it had not returned from `accept()` on, so the fork's
    // connect completed into the backlog and its `new ObjectInputStream` blocked on a stream header
    // nothing was going to write. Every one of `ForkMain`'s three pre-run statements exits 86 the
    // same way, so the exit status cannot say which — the sampled stack can.
    it("names the statement a fork is stuck in, while it is stuck and again when it dies") {
      val server = sbtSideSocket()
      try {
        val run = runForkMain(
          port = server.getLocalPort,
          withAgent = true,
          agentArgs = s"${ForkSafeExitAgent.StallMillisArg}$StallMillis",
          // Accepting the connection and closing THAT socket is the EOF `ForkMain`'s
          // `new ObjectInputStream` throws on, and is what turns the stall into the silent throw
          // its `finally { System.exit(0); }` discards. Closing the SERVER socket instead would
          // rest on a listener close aborting a connection still sitting unaccepted in its
          // backlog, which nothing requires it to do; where that abort does not arrive the fork
          // stays parked and only this suite's own timeout ends it. Done once the stall has been
          // reported, so the two halves of this assertion are not racing each other.
          whileRunning = output => {
            await(output, StalledMarker)
            hangUpOnEveryConnection(server)
          }
        )

        run.status mustBe ForkSafeExitAgent.ExitStatus
        run.output must include(StalledMarker)
        // The socket the configuration never arrived on, which is the other half of the diagnosis.
        run.output must include(s"127.0.0.1:${server.getLocalPort}")

        // Both reports carry the same sample, so both answer for it.
        mustNameAStalledFork(run.output, StalledHeader)
        mustNameAStalledFork(run.output, DiedHeader)
      } finally server.close()
    }

    // The same claim without a fork, and why the check above is allowed to be structural: a
    // position is SAMPLED and then carried verbatim, so neither renderer knows — and nothing
    // downstream of them can promise — which statement it describes. Driven by the position a
    // loaded runner really sampled, which is the one an assertion on `ObjectInputStream` refused.
    it("carries whatever position was sampled into both reports, verbatim") {
      val stalled = ForkSafeExitAgent.stallDiagnostic(Port, StallMillis, ConnectPosition)
      val died = ForkSafeExitAgent.lastSeen(ConnectPosition)

      stalled must include(ConnectPosition)
      died must include(ConnectPosition)
      mustNameAStalledFork(stalled, StalledHeader)
      mustNameAStalledFork(died, DiedHeader)
    }

    // THE OTHER WAY A FORK BLOCKS IN `new ObjectInputStream`, and the one that is not sbt's fault.
    // On macOS a wildcard bind does not reserve a port, so this is the OS behaviour the whole hijack
    // rests on: it must stay true for the diagnostic to be worth printing, and if it ever stops being
    // true this is what says so. Linux refuses the wildcard bind instead, so there the hijack cannot
    // happen at all and sbt fails loudly at its own bind rather than parking a fork.
    it("is load-bearing: a wildcard bind does not reserve the port, and the specific bind wins") {
      val thief = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
      try {
        val port = thief.getLocalPort
        if (WildcardBindYields) {
          // Exactly `ForkTests.mainTestTask`: sbt takes the wildcard, and the kernel lets it, even
          // though 127.0.0.1 on this port is already spoken for.
          val sbtSide = new ServerSocket(port)
          try {
            sbtSide.getLocalPort mustBe port

            // Exactly `ForkMain`: `InetAddress.getByName(null)` is 127.0.0.1.
            val fork = new Socket(InetAddress.getByName(null), port)
            try {
              thief.setSoTimeout(ProcessTimeoutMillis.toInt)
              // Delivered to the MOST SPECIFIC listener. sbt's acceptor gets nothing, which is the
              // whole failure: it is not late, it is unreachable.
              val accepted = thief.accept()
              accepted.close()
            } finally fork.close()
          } finally sbtSide.close()
        } else {
          a[BindException] must be thrownBy new ServerSocket(port).close()
        }
      } finally thief.close()
    }

    it("renders a foreign holder of the port as a hijack, naming it") {
      val verdict = ForkSafeExitAgent.hijackVerdict(ForeignListener, ParentPid, Port)

      verdict must include(ForkSafeExitAgent.HijackMarker)
      verdict must include("workerd")
      verdict must include("127.0.0.1:" + Port)
      verdict must include(ParentPid.toString)
    }

    it("says plainly when the parent IS the only listener, which is the other diagnosis") {
      val verdict = ForkSafeExitAgent.hijackVerdict(ParentListener, ParentPid, Port)

      verdict must include("this fork's parent")
      verdict must not include ForkSafeExitAgent.HijackMarker
    }

    it("names sbt's own listener alongside the thief when both hold the port") {
      val verdict = ForkSafeExitAgent.hijackVerdict(ForeignListener + ParentListener, ParentPid, Port)

      verdict must include(ForkSafeExitAgent.HijackMarker)
      verdict must include("workerd")
      verdict must include("and by sbt itself")
    }

    // Every failure mode of the lookup has to read as "not known", never as an accusation: a
    // machine with no `lsof`, a truncated read, and an unknown parent all reach this.
    it("never accuses anyone on evidence it does not have") {
      ForkSafeExitAgent.hijackVerdict(null, ParentPid, Port) mustBe ForkSafeExitAgent.Undetermined
      ForkSafeExitAgent.hijackVerdict("", ParentPid, Port) mustBe ForkSafeExitAgent.Undetermined
      ForkSafeExitAgent.hijackVerdict(HeaderOnly, ParentPid, Port) mustBe ForkSafeExitAgent.Undetermined
      ForkSafeExitAgent.hijackVerdict("garbage\nlines\n", ParentPid, Port) mustBe ForkSafeExitAgent.Undetermined

      // An unidentifiable parent makes every listener look foreign, so it must decide nothing.
      val blind = ForkSafeExitAgent.hijackVerdict(ForeignListener, -1L, Port)
      blind must not include ForkSafeExitAgent.HijackMarker
      blind must include("could not be identified")
    }

    // The two ways the lookup can fail to produce an answer AT ALL, driven for real rather than
    // waited for. Both are facts about the MACHINE — it has no `lsof`, or it was too slow to answer
    // inside the bound — and each report has to say so in as many words, because the other reading
    // of a bare "could not be determined" is that the machine WAS asked and named nobody, which
    // sends a reader to look at the port rather than at the runner. The real-fork case below is
    // what has to tell the two apart, and it can only do that if the agent states which it was.
    it("says the lookup itself did not answer when the machine has no lsof") {
      val verdict =
        ForkSafeExitAgent.portOwnership(Port, MissingBinary, ForkSafeExitAgent.OwnerTimeoutMillis)

      verdict must startWith(ForkSafeExitAgent.Undetermined)
      verdict must include(ForkSafeExitAgent.LookupUnanswered)
      verdict must include(MissingBinary)
      verdict must not include ForkSafeExitAgent.HijackMarker
    }

    // The path a loaded runner really takes (ISS-9099), on a bound no spawn-and-run of lsof beats,
    // so it is exercised here rather than only on the machine slow enough to produce it. The bound
    // covers the spawn too, so a lookup that finished while start() was still returning reads as
    // outrun rather than as an empty answer (ISS-11636).
    it("says the lookup itself did not answer when it outran its bound") {
      assume(new File(ForkSafeExitAgent.LsofBinary).canExecute())
      val verdict = ForkSafeExitAgent.portOwnership(Port, ForkSafeExitAgent.LsofBinary, 1L)

      verdict must startWith(ForkSafeExitAgent.Undetermined)
      verdict must include(ForkSafeExitAgent.LookupUnanswered)
      verdict must include("1ms")
      verdict must not include ForkSafeExitAgent.HijackMarker
    }

    // The plumbing, in a real forked JVM: that the lookup runs, reads the machine, and matches the
    // pid that actually forked it. Asserted on the branch this spec can produce — the spec JVM IS
    // the parent and IS the only listener — because the hijack branch needs a second process
    // holding the port, which the pure cases above cover instead.
    //
    // The ownership lookup reads the machine at the moment it runs, so this case is only decidable
    // while the parent still holds the port: a fork killed by CLOSING sbt's server socket asks
    // `lsof` about a port nobody is listening on any more, and gets the equally correct "could not
    // be determined" instead. So the fork is killed the other way — the connection is ACCEPTED and
    // that accepted socket closed, which is the EOF `ForkMain`'s `new ObjectInputStream` throws on
    // — and the server socket stays bound and listening until the fork has exited and its
    // diagnostic is written.
    //
    // What that leaves is the lookup's OWN cost. `lsof` walks every process on the machine, so on a
    // runner carrying several sessions and a build it can outrun the bound the agent gives it, and
    // the fork then reports — correctly, and exactly as the two cases above require — that it could
    // not be asked. That is a statement about the runner rather than about this branch, and it is
    // the shape `ci/build.sh` already declines to call a red: this CANCELS for the same reason that
    // exits 75 there. Every other outcome still fails, including an accusation, a holder that is
    // not the parent, and no verdict at all.
    it("reports who held the port, from inside a real fork") {
      assume(new File(ForkSafeExitAgent.LsofBinary).canExecute())
      val server = sbtSideSocket()
      try {
        val run = runForkMain(
          port = server.getLocalPort,
          withAgent = true,
          whileRunning = _ => hangUpOnEveryConnection(server)
        )

        run.status mustBe ForkSafeExitAgent.ExitStatus
        run.output must not include ForkSafeExitAgent.HijackMarker
        if (run.output.contains(ForkSafeExitAgent.LookupUnanswered)) {
          cancel(
            s"${ForkSafeExitAgent.LsofBinary} did not answer within the agent's " +
              s"${ForkSafeExitAgent.OwnerTimeoutMillis}ms bound on this machine, so this run has no " +
              s"ownership verdict to check. Output:\n${run.output}"
          )
        }
        run.output must include("this fork's parent")
        // The pid AND the address it was found on, rather than the pid alone: the diagnostic also
        // prints an uptime and three heap figures, any of which can carry those digits.
        run.output must include(
          s"(pid ${ProcessHandle.current().pid}) on 127.0.0.1:${server.getLocalPort}"
        )
      } finally server.close()
    }

    it("leaves a fork that received its configuration alone") {
      // The marker the agent reads is `sbt.ForkConfiguration` having been loaded, and this is what
      // asserts that it is loaded LAZILY on the real JDK — that nothing on ForkMain's path to the
      // socket drags it in early. If a future JDK verified `Run.runTests` eagerly, the test above
      // would go red rather than this one: the agent would stop acting, which is the safe
      // direction, and this pair is what says which of the two happened.
      val server = sbtSideSocket()
      val served = serveConfiguration(server)
      try {
        val run = runForkMain(port = server.getLocalPort, withAgent = true)

        run.status mustBe 0
        // Covers the watchdog as well as the hook: a fork that is configured in milliseconds ends
        // the sampling loop and prints nothing, which is what makes this cheap enough to run in
        // every forked test JVM in the build.
        run.output must not include "[fork-safe-exit-agent]"
      } finally {
        served.join(ProcessTimeoutMillis)
        server.close()
      }
    }
  }
}

private object ForkSafeExitAgentSpec {

  val ProcessTimeoutMillis: Long = 60000L

  val DrainTimeoutMillis: Long = 5000L

  /** What the spec drives the agent's stall report with, in place of its 30s default. */
  val StallMillis: Long = 1000L

  /** Fixed `lsof` text, so the verdict is asserted against known input and not against whatever
    * happens to be listening on the machine running this suite.
    */
  val Port: Int = 54774

  val ParentPid: Long = 4242L

  /** A path no machine has, so the "this runner cannot be asked" branch is reached deliberately. */
  val MissingBinary: String = "/nonexistent/lsof"

  val HeaderOnly: String = "COMMAND     PID   USER   FD   TYPE  DEVICE SIZE/OFF NODE NAME\n"

  val ForeignListener: String =
    HeaderOnly + s"workerd   88616 athena   23u  IPv4 0x1a2b      0t0  TCP 127.0.0.1:$Port (LISTEN)\n"

  val ParentListener: String =
    s"java      $ParentPid athena   45u  IPv6 0x3c4d      0t0  TCP *:$Port (LISTEN)\n"

  /** The distinctive half of that report, so the two assertions on it cannot drift apart. */
  val StalledMarker: String = s"has gone ${StallMillis}ms without receiving"

  /** A position a stalled fork was really sampled at on a loaded runner (ISS-6825): still in the
    * connect that precedes `new ObjectInputStream`, rather than in it. Nothing about that report is
    * wrong — the fork genuinely was parked there — so it is the case the assertions above have to
    * accept, and the fixture that says so without needing a machine slow enough to produce it.
    */
  val ConnectPosition: String =
    "thread \"main\" at uptime 1187ms:" +
      "\n    at java.base/sun.nio.ch.Net.connect0(Native Method)" +
      "\n    at java.base/sun.nio.ch.NioSocketImpl.connect(NioSocketImpl.java:589)" +
      "\n    at java.base/java.net.Socket.<init>(Socket.java:507)" +
      "\n    at sbt.ForkMain.main(ForkMain.java:171)"

  /** Where each of the two reports starts rendering the sampled stack. */
  val StalledHeader: String = "It is parked in thread \"main\""

  val DiedHeader: String = "Last seen in thread \"main\""

  /** The stack one report rendered, innermost frame first, `main` last.
    *
    * Anchored on that report's own header rather than read out of the whole output, because a run
    * prints two of them and each has to answer for its own stack. The agent appends its port
    * ownership verdict to the last frame's line rather than on a line of its own, so a frame is
    * matched by its prefix; empty when the header is absent, which the assertion reports as the
    * missing stack it is.
    */
  def renderedStack(output: String, header: String): Seq[String] = {
    val at = output.indexOf(header)
    if (at < 0) Nil
    else
      output
        .substring(at)
        .linesIterator
        .drop(1) // the rest of the header's own line
        .map(_.trim)
        .takeWhile(_.startsWith("at "))
        .toSeq
  }

  /** Polls for a subprocess's output rather than blocking on it, because the subject is a program
    * that does not exit: the marker arrives while the fork is still parked, and reading to EOF
    * first would mean waiting for the very thing the marker is announcing has not happened.
    */
  def await(output: () => String, marker: String): Unit = {
    val deadline = System.currentTimeMillis() + ProcessTimeoutMillis
    while (!output().contains(marker)) {
      if (System.currentTimeMillis() > deadline) {
        sys.error(s"sbt.ForkMain never printed '$marker'. Output:\n${output()}")
      }
      Thread.sleep(50)
    }
  }

  /** Printed when upstream stops reporting a fork that never connected as a success. There is only
    * one thing that means, and it is the whole reason that assertion is worth keeping.
    */
  val UpstreamMayBeFixed: String =
    "sbt.ForkMain reported a NON-ZERO exit for a fork that never reached sbt's socket, so the bug " +
      "`testkit.ForkSafeExitAgent` exists to work around (ISS-3439) looks FIXED upstream — either " +
      "`ForkMain.main`'s `finally { System.exit(0); }` is gone, or `ForkTests` no longer joins its " +
      "acceptor unconditionally. If it is: delete `ForkSafeExitAgent`, this spec, the `-javaagent` " +
      "block in scala-build-app's `ForkSafeTestFrameworkPlugin.scala`, and testkit's `Premain-Class` and " +
      "`test-agent` lines in build.sbt. `ci/stall-watchdog` stays either way."

  final case class Run(status: Int, output: String)

  /** Whether this kernel grants a wildcard bind on a port another socket holds on the loopback
    * address. macOS does, which is the hijack; Linux refuses it with `EADDRINUSE`.
    */
  val WildcardBindYields: Boolean = sys.props.getOrElse("os.name", "").toLowerCase.contains("mac")

  /** sbt's side of the handshake, bound to the address `ForkMain` actually dials.
    *
    * `ForkTests.mainTestTask` binds the WILDCARD, and a wildcard bind does not reserve the port:
    * a process holding the loopback address on it takes delivery of the fork's connection instead,
    * because a connection goes to the most specific matching listener. That is a real production
    * failure — it is what `ForkSafeExitAgent.hijackVerdict` exists to name, and the load-bearing
    * test above is what keeps the OS behaviour behind it honest — but in a test that runs a real
    * fork it is an unrelated process silently substituting itself for the fixture, on a machine
    * that runs many builds at once. The fork then parks on a stream header that process will never
    * write, and every way this spec has of ending a fork acts on the socket it did NOT reach.
    *
    * Binding the loopback address SPECIFICALLY closes that window: such a bind is refused on a
    * port already held that way rather than granted alongside it, so the port this returns is one
    * no other process can be listening on. `InetAddress.getByName(null)` is the same expression
    * `ForkMain` dials, so the two cannot disagree about which address that is.
    */
  def sbtSideSocket(): ServerSocket = new ServerSocket(0, 50, InetAddress.getByName(null))

  /** Accepts every connection `server` receives and closes it unread, until `server` is closed.
    *
    * The fork's own connection is the first: closing it is the EOF `ForkMain`'s
    * `new ObjectInputStream` throws on. The agent's exit probe then dials the same port, and it has
    * to be hung up on too: left in the backlog it waits out the probe's whole `ProbeTimeoutMillis`
    * for a byte nothing will write, which was most of this suite's run time. A hang-up answers it at
    * once, and no assertion here reads what the probe concluded. The server socket itself stays
    * bound and listening, which the ownership lookup in the exit diagnostic depends on.
    */
  def hangUpOnEveryConnection(server: ServerSocket): Unit = {
    val hangUp: Runnable = () =>
      try {
        while (!server.isClosed) server.accept().close()
      } catch {
        // `accept` throws once the test closes the server socket, which is how this ends.
        case NonFatal(_) => ()
      }
    val thread = new Thread(hangUp, "fork-safe-exit-agent-spec-hang-up")
    thread.setDaemon(true)
    thread.start()
  }

  /** A port nothing is listening on, which is the state a fork cannot connect to. Bound and
    * released rather than picked, so it is one the OS was willing to hand out on this machine, and
    * bound the way [[sbtSideSocket]] binds so that a port another process holds on the loopback
    * address is never handed out here — a fork dialling one of those CONNECTS, to that process,
    * and parks on a stream header instead of failing to connect at all.
    */
  def closedPort(): Int = {
    val socket = sbtSideSocket()
    val port = socket.getLocalPort
    socket.close()
    port
  }

  /** `java [-javaagent:...] -cp <ForkMain and its interfaces> sbt.ForkMain <port>` — the command
    * line sbt itself builds, minus the test classpath there is nothing here to run.
    */
  def runForkMain(
    port: Int,
    withAgent: Boolean,
    agentArgs: String = "",
    whileRunning: (() => String) => Unit = _ => (),
  ): Run = {
    val javaBinary = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val args = if (agentArgs.isEmpty) "" else s"=$agentArgs"
    val agent = if (withAgent) Seq(s"-javaagent:${agentJar().getAbsolutePath}$args") else Nil
    val command =
      Seq(javaBinary) ++ agent ++ Seq("-classpath", forkMainClasspath, "sbt.ForkMain", port.toString)

    val process = new ProcessBuilder(command*).redirectErrorStream(true).start()
    process.getOutputStream.close()

    // Drained on its own thread, and the wait is bounded, because the whole subject here is a
    // program that hangs. Reading the pipe to EOF on this thread would hang the SUITE for exactly
    // the failure the spec exists to catch, and report it as nothing at all.
    //
    // Appended as it arrives rather than read whole at EOF, because `whileRunning` below reads it
    // WHILE the fork is still parked — a report that only exists once the process has ended cannot
    // say anything about a process that has not.
    val captured = new AtomicReference("")
    val drain = new Thread(
      () => {
        val buffer = new Array[Byte](4096)
        val in = process.getInputStream
        var read = in.read(buffer)
        while (read >= 0) {
          val chunk = new String(buffer, 0, read, UTF_8)
          captured.updateAndGet(_ + chunk)
          read = in.read(buffer)
        }
      },
      "fork-safe-exit-agent-spec-drain"
    )
    drain.setDaemon(true)
    drain.start()

    whileRunning(() => captured.get)

    val exited = process.waitFor(ProcessTimeoutMillis, TimeUnit.MILLISECONDS)
    if (!exited) {
      process.destroyForcibly().waitFor()
      drain.join(DrainTimeoutMillis)
      sys.error(s"sbt.ForkMain did not exit within ${ProcessTimeoutMillis}ms. Output:\n${captured.get}")
    }
    drain.join(DrainTimeoutMillis)
    Run(process.exitValue(), captured.get)
  }

  /** Exactly what sbt appends for a fork, and all a ForkMain that runs no tests needs: its own
    * artifact and the `sbt.testing` interfaces it is compiled against.
    */
  def forkMainClasspath: String =
    Seq(Class.forName("sbt.ForkMain"), classOf[sbt.testing.Framework])
      .map(_.getProtectionDomain.getCodeSource.getLocation.toURI)
      .map(new File(_).getAbsolutePath)
      .distinct
      .mkString(File.pathSeparator)

  /** The shipped agent class in a jar the JVM will accept as `-javaagent`, built here rather than
    * taken from `packageBin` so this spec depends on the class under test and not on the packaging.
    */
  def agentJar(): File = {
    val jar = File.createTempFile("fork-safe-exit-agent", ".jar")
    jar.deleteOnExit()

    val manifest = new JarManifest
    manifest.getMainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0")
    manifest.getMainAttributes.putValue("Premain-Class", classOf[ForkSafeExitAgent].getName)

    val entry = classOf[ForkSafeExitAgent].getName.replace('.', '/') + ".class"
    val out = new JarOutputStream(Files.newOutputStream(jar.toPath), manifest)
    try {
      out.putNextEntry(new JarEntry(entry))
      out.write(getClass.getClassLoader.getResourceAsStream(entry).readAllBytes())
      out.closeEntry()
    } finally out.close()
    jar
  }

  /** The sbt side of the protocol, far enough for `ForkMain` to receive its `ForkConfiguration` and
    * finish: the handshake, a configuration, no tests, no frameworks, and the `Done` its last
    * `readObject` waits for. Mirrors `sbt.ForkTests$Acceptor`, including the flush that has to come
    * before the input stream is opened or both ends block on each other's header.
    */
  def serveConfiguration(server: ServerSocket): Thread = {
    val acceptor: Runnable = () =>
      try {
        val socket = server.accept()
        try {
          val os = new ObjectOutputStream(socket.getOutputStream)
          os.flush()
          val is = new ObjectInputStream(socket.getInputStream)
          os.writeObject(new sbt.ForkConfiguration(false, false))
          os.writeObject(Array.empty[sbt.testing.TaskDef])
          os.writeInt(0)
          os.flush()

          while (is.readObject() != sbt.ForkTags.Done) ()
          os.writeObject(sbt.ForkTags.Done)
          os.flush()
        } finally socket.close()
      } catch {
        // The assertion on the subprocess's exit status is what reports this failing; throwing
        // out of a daemon thread would only add noise to it.
        case NonFatal(_) => ()
      }
    val thread = new Thread(acceptor, "fork-safe-exit-agent-spec-acceptor")
    thread.setDaemon(true)
    thread.start()
    thread
  }
}
