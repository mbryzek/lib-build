package testkit;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Makes a forked test JVM that died before it could report anything exit NON-ZERO, which is the one
 * thing sbt needs in order not to hang (ISS-3439).
 *
 * <h2>The bug</h2>
 *
 * With {@code Test / fork := true}, sbt opens a {@link java.net.ServerSocket}, starts an acceptor
 * thread on it, and runs {@code java ... sbt.ForkMain <port>}; the fork dials that port back and
 * streams its results over it. {@code sbt.ForkTests} then does, and these five lines are the whole
 * bug:
 *
 * <pre>
 *   val ec = Fork.java(fork, options)                 // ForkTests.scala:162
 *   val result =
 *     if (ec != 0) TestOutput(TestResult.Error, ...)  // "failed with exit code N"
 *     else {
 *       acceptorThread.join()                         // ForkTests.scala:175, no timeout
 *       Acceptor.result
 *     }
 * </pre>
 *
 * sbt reads {@code ec == 0} as "the fork ran", so it joins the acceptor. But {@code ForkMain.main}
 * is
 *
 * <pre>
 *   public static void main(final String[] args) throws Exception {
 *     ClassLoader classLoader = new Run().getClass().getClassLoader();
 *     try {
 *       main(args, classLoader);                      // first statement: new Socket(loopback, port)
 *     } finally {
 *       System.exit(0);                               // ForkMain.java:166
 *     }
 *   }
 * </pre>
 *
 * so a fork that throws on its way to that socket exits 0 all the same. sbt then joins an acceptor
 * parked in {@code ServerSocket.accept()} that nothing will ever connect to. Neither the accept nor
 * the join has a timeout, and the socket is closed only in a {@code finally} the join never reaches
 * — so the test task never reports complete and sbt's task engine waits on it forever, at 0% CPU,
 * with every other subproject already green.
 *
 * <p>The silence is the same {@code finally}: {@code System.exit} does not return, so it runs while
 * the throwable is still unwinding and the JVM dies before the default handler can print the stack
 * trace. The fork therefore explains nothing, sbt reports nothing, and the build simply stops.
 *
 * <h2>What this does</h2>
 *
 * Attached to every forked test JVM as a {@code -javaagent}, this registers a shutdown hook — which
 * {@code System.exit(0)} does run — that asks one question: <em>did this JVM ever receive its
 * {@code ForkConfiguration}?</em> That object is the first thing {@code ForkMain} reads off the
 * socket ({@code ForkMain.java:305}), before it can run a single test, so its class being loaded is
 * exactly equivalent to "the connection was made and the run began". If it was never loaded, the
 * fork ran nothing, the group has no results either way, and the only useful exit status is a
 * failing one — so this prints what it can see and {@link Runtime#halt}s with {@link #ExitStatus}.
 *
 * <p>sbt then takes its own {@code ec != 0} branch: it does not join, it reports
 * {@code "Running java with options ... failed with exit code 86"}, and its {@code finally} closes
 * the server socket, which releases the acceptor through the {@code SocketException} path sbt
 * already handles. A wedge that cost 480 seconds of watchdog plus a red pull request becomes a
 * named failure in the second it happens.
 *
 * <h2>Why here and not upstream</h2>
 *
 * Both halves are unchanged across sbt 1.x, up to and including 1.13.1. sbt 2 replaced fork
 * testing wholesale (a worker CLI with no acceptor thread and no join), so there is no 1.x release
 * to upgrade to and no backport to wait for. Reported upstream from ISS-3439.
 *
 * <h2>Where it was, since the throwable itself is unreachable</h2>
 *
 * The exit status says the fork ran nothing; it does not say which of {@code ForkMain}'s three
 * pre-run statements it died in, and those fail for different reasons — the {@code new Socket} for
 * a machine-level one, the {@code new ObjectInputStream} for a peer that accepted the connection and
 * never wrote sbt's stream header, the {@code new ObjectOutputStream} for one that went away
 * mid-handshake. The throwable that would name it is discarded unprinted by the {@code finally}
 * above, and nothing in this process can recover it.
 *
 * <p>So a daemon thread SAMPLES the fork's main thread instead, every {@link #SampleIntervalMillis}
 * while the fork is still unconfigured, and keeps the last sample. The stack of a thread blocked in
 * {@code readStreamHeader} names the statement as exactly as the throwable would have, and it is
 * taken while the fork is still in that state, not after it has unwound. The diagnostic below
 * prints the sample and the uptime it was taken at, beside the uptime the fork died at, so how
 * stale it is reads off the two numbers. A healthy fork is configured within milliseconds, so the
 * loop ends after a sample or two and prints nothing at all.
 *
 * <p>The sampling also covers the case the shutdown hook cannot reach: a fork that never exits.
 * That one runs no hook, so the exit-status trick above has nothing to act on and the fork is
 * silent for as long as the build waits on it. After {@link #DefaultStallMillis} unconfigured the
 * watchdog prints where it is parked, once, and stops.
 *
 * <h2>Who actually held the port</h2>
 *
 * A fork blocked in {@code new ObjectInputStream} has connected to SOMETHING; it cannot see what.
 * Two states look identical from inside it, and only one is sbt's to fix.
 *
 * <p>{@code ForkTests.mainTestTask} binds the WILDCARD — {@code new ServerSocket(0)} — and a
 * wildcard bind does not reserve a port. Another process may hold {@code 127.0.0.1} on that same
 * port; the kernel's wildcard ephemeral allocator will hand out a port already held that way; and
 * every connection goes to the MOST SPECIFIC matching listener. {@code ForkMain} dials
 * {@code InetAddress.getByName(null)}, which is {@code 127.0.0.1}, so when a loopback-bound process
 * holds the port the fork reaches IT, sbt's acceptor stays in {@code accept()} with nothing to
 * accept, and the fork blocks on a stream header that process is never going to write. Whatever
 * idle timeout that process keeps is then what ends the fork, and the build reports a test failure
 * for a port collision.
 *
 * <p>So {@link #portOwnership} asks the machine who is listening and compares it with this fork's
 * parent, which for a forked test JVM is sbt. A holder that is not the parent is named in the
 * report as {@link #HijackMarker}, because that occurrence is not about the suite, the branch or
 * the machine's load, and re-running the build only redraws the same lottery. The same line says so
 * the other way when sbt is the only listener, which is the state that IS sbt's acceptor being late.
 *
 * <p>The prime source of such holders is an abandoned local server: a dev server or test server that
 * binds a loopback ephemeral port and outlives the session that started it holds that port until it
 * is killed, and every one of them is a port sbt's forks can be handed.
 *
 * <h2>What it is not</h2>
 *
 * This does not stop forks dying, and it is not a diagnosis of why they do. It makes the failure
 * ARRIVE, with a probe of sbt's socket and the fork's own last position attached, so an occurrence
 * is evidence instead of a stall. {@code ci/stall-watchdog} stays either way: it bounds the wedges
 * we have no artifact for.
 *
 * <p>Nor does it make the failure SURVIVABLE. What sbt reports for exit 86 is
 * {@code sbt.TestsFailedException: Tests unsuccessful}, which the merge lane refuses like any
 * other red — so an occurrence parked every open pull request in the repo over a fork that ran
 * nothing. {@code ci/build.sh}'s {@code exit_if_fork_never_configured} is the other end of that:
 * it greps the build log for the sentence both diagnostics below carry, and when that is the only
 * failure in the run it exits 75 so the fleet records an infrastructure fault instead of a red on
 * the branch. The two are a contract across a Java file and a shell script, so
 * {@code scripts/test/ci_fork_fault_test.rb} asserts the sentence the script greps for is in both
 * of them (ISS-5584).
 *
 * <p>Everything here is defensive to the point of rudeness. It runs in every forked test JVM in the
 * build, so an agent that threw, hung, or halted a healthy fork would be far worse than the bug it
 * closes: every path catches {@link Throwable} and falls through to doing nothing, and the halt is
 * reachable only from the one state in which the fork has provably run no tests at all.
 */
public final class ForkSafeExitAgent {

  /** The main class sbt forks. This agent does nothing in a JVM that is not one. */
  static final String ForkMainClass = "sbt.ForkMain";

  /**
   * The first object {@code ForkMain} reads off sbt's socket, and therefore the marker for "this
   * fork was configured and began its run". Loaded lazily: nothing on the path to the connect
   * mentions it, which {@code ForkSafeExitAgentSpec} asserts against the real JDK rather than
   * assuming.
   */
  static final String ConfiguredMarkerClass = "sbt.ForkConfiguration";

  /**
   * Distinctive so that sbt's own one-line report of it ("failed with exit code 86") identifies
   * this without needing the diagnostic printed alongside it to have survived interleaving.
   */
  public static final int ExitStatus = 86;

  /** Bounds the probe below. It runs only on a fork that is already failing, never on a good one. */
  static final int ProbeTimeoutMillis = 2000;

  /**
   * Absolute, because {@code ~/code/devops/bin} precedes {@code /usr/bin} on this fleet's PATH and a
   * bare name there is not reliably this binary. Absent on a machine that has no {@code lsof} at
   * all, which {@link #portOwnership} reports as {@link #LookupUnanswered} rather than failing.
   */
  static final String LsofBinary = "/usr/sbin/lsof";

  /**
   * Bounds the ownership lookup. Paid only by a fork that is already failing, on top of
   * {@link #ProbeTimeoutMillis}, and never by a healthy one.
   *
   * <p>Sized for a LOADED machine rather than an idle one, because that is the only kind this ever
   * runs on: a fork goes unconfigured when sbt's acceptor was not there in time, or when another
   * process took the port, and both are what a machine running several builds at once produces.
   * {@code lsof} walks every process on that machine, so its cost rises with exactly the thing that
   * summoned it — a bound tuned against an idle runner expires precisely when the answer is wanted,
   * and the report then says nothing at the one moment it had something to say.
   */
  static final long OwnerTimeoutMillis = 10000L;

  /** The token {@link #hijackVerdict} keys the answer on, so the report and its spec cannot drift. */
  static final String HijackMarker = "PORT HIJACKED";

  /**
   * Said when nothing could be read about the port, which is not evidence that sbt held it. On its
   * own it means the machine ANSWERED and named no listener; followed by {@link #LookupUnanswered}
   * it means the machine could not be asked.
   */
  static final String Undetermined = " Port ownership could not be determined.";

  /**
   * The second half of {@link #Undetermined} when the lookup never ran to an answer at all — no
   * {@code lsof} on the machine, or one that outran {@link #OwnerTimeoutMillis}. Those two are
   * facts about the MACHINE; {@link #Undetermined} on its own is the different fact that the lookup
   * DID answer and named no listener on the port. Both refuse to accuse anyone, so the exit status
   * and the rest of the diagnostic are unaffected either way — but only a reader who can tell them
   * apart knows whether to go and look at the port or at the runner, and this is what tells them.
   *
   * <p>{@code ForkSafeExitAgentSpec} reads this sentence back for the same reason, so the two must
   * not drift.
   */
  static final String LookupUnanswered = "The lookup itself did not answer";

  /**
   * How often the watchdog samples the main thread while the fork is still unconfigured. Short
   * enough that the last sample is a fresh account of where the fork was when it died, and paid
   * only until the fork IS configured — which for a healthy one is its first sample or two.
   */
  static final long SampleIntervalMillis = 250L;

  /**
   * How long a fork may go unconfigured before the watchdog reports where it is parked and stops.
   * Two orders of magnitude past a healthy handshake, which completes in milliseconds, so this
   * fires only on a fork that is not coming back.
   */
  static final long DefaultStallMillis = 30000L;

  /** {@code -javaagent:<jar>=stall-millis=<n>}, which is how the spec drives the stall report. */
  static final String StallMillisArg = "stall-millis=";

  /**
   * Where the fork's main thread was at the last sample, already rendered. Written only by the
   * watchdog and read only by the shutdown hook, both of which tolerate it being absent: a fork
   * that died before the first sample simply has no position to report. Text rather than a
   * {@code Thread} so that nothing here holds a thread past its life, and one field rather than a
   * nested type so that the whole agent stays the single class {@code ForkSafeExitAgentSpec}
   * packages into a jar by hand.
   */
  private static final AtomicReference<String> LastPosition = new AtomicReference<>();

  /**
   * Set by the shutdown hook before it reads {@link #LastPosition}, and the reason the position it
   * prints is the one from BEFORE the fork died. Shutdown runs the hook while {@code ForkMain.main}
   * is still on the stack inside {@code System.exit}, so a watchdog left sampling would overwrite
   * the useful stack with {@code Shutdown.runHooks} — the fork's death reported as the fork dying.
   */
  private static final AtomicBoolean Frozen = new AtomicBoolean();

  private ForkSafeExitAgent() {}

  public static void premain(final String agentArgs, final Instrumentation instrumentation) {
    try {
      final int port = forkMainPort(System.getProperty("sun.java.command"));
      if (port < 0) return;
      final Thread hook = new Thread(() -> atExit(instrumentation, port), "fork-safe-exit-agent");
      Runtime.getRuntime().addShutdownHook(hook);

      // `premain` runs on the thread that goes on to call `ForkMain.main`, so this needs no search
      // and cannot pick the wrong one. The name is carried into the report anyway, because a JVM
      // that ever stops honouring that is one whose samples are about something else.
      final Thread main = Thread.currentThread();
      final long stallMillis = stallMillis(agentArgs);
      final Thread watchdog =
          new Thread(() -> watch(instrumentation, main, stallMillis, port), "fork-safe-exit-watch");
      watchdog.setDaemon(true);
      watchdog.start();
    } catch (final Throwable t) {
      // A test JVM that runs is worth more than this agent. Leave it exactly as it was.
      return;
    }
    // LAST, and on its own: a shard's cost line is a measurement, and nothing about it may cost a
    // fork the exit status above. A jar that carries this class alone (as ForkSafeExitAgentSpec
    // builds one) fails to link `ShardReport`, which must leave the hook installed.
    try {
      ShardReport.install();
    } catch (final Throwable t) {
      // No report, and the fork otherwise exactly as it was.
    }
  }

  /** {@link #DefaultStallMillis} unless the agent argument overrides it; never zero or negative. */
  static long stallMillis(final String agentArgs) {
    if (agentArgs == null) return DefaultStallMillis;
    for (final String arg : agentArgs.split(",")) {
      if (!arg.trim().startsWith(StallMillisArg)) continue;
      try {
        final long millis = Long.parseLong(arg.trim().substring(StallMillisArg.length()));
        if (millis > 0) return millis;
      } catch (final NumberFormatException e) {
        // An unreadable argument is not worth failing a whole test run over. Take the default.
      }
    }
    return DefaultStallMillis;
  }

  /**
   * Samples {@code main} until the fork is configured, which for a healthy one is immediately. The
   * loop is the only thing that ever writes {@link #LastPosition}, and it ends on its own either way
   * — configured, or reported as stalled — so nothing here outlives the handshake it is watching.
   */
  private static void watch(
      final Instrumentation instrumentation,
      final Thread main,
      final long stallMillis,
      final int port) {
    try {
      while (!Frozen.get() && !wasConfigured(instrumentation)) {
        // Sleeps BEFORE the first sample, deliberately. This thread starts in `premain`, so a
        // sample taken immediately would catch the main thread inside JVM startup and report a
        // position the fork was never really at. A fork that dies inside one interval reports no
        // position at all, which is the honest answer and is itself informative.
        Thread.sleep(SampleIntervalMillis);
        final long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        final String position = position(main, uptime);
        if (Frozen.get()) return;
        LastPosition.set(position);
        if (uptime >= stallMillis) {
          System.err.println(stallDiagnostic(port, stallMillis, position));
          System.err.flush();
          return;
        }
      }
    } catch (final Throwable t) {
      // Same bargain as premain: a sample is worth strictly less than the fork it is watching.
    }
  }

  /**
   * One sample, as the line the reports below print. The uptime is IN the text rather than beside
   * it so that a sample survives being handed around as one value, and so that a reader can place
   * it against the uptime the exit diagnostic states for itself.
   */
  static String position(final Thread thread, final long uptimeMillis) {
    final StringBuilder rendered =
        new StringBuilder("thread \"")
            .append(thread.getName())
            .append("\" at uptime ")
            .append(uptimeMillis)
            .append("ms:");
    final StackTraceElement[] frames = thread.getStackTrace();
    if (frames.length == 0) {
      return rendered.append("\n    (no frames — the thread had already finished)").toString();
    }
    for (final StackTraceElement frame : frames) {
      rendered.append("\n    at ").append(frame);
    }
    return rendered.toString();
  }

  static String stallDiagnostic(final int port, final long stallMillis, final String position) {
    return "[fork-safe-exit-agent] This forked test JVM has gone "
        + stallMillis
        + "ms without receiving its "
        + ConfiguredMarkerClass
        + " from sbt on 127.0.0.1:"
        + port
        + ", where a healthy fork takes milliseconds, and it has not exited — so no exit status "
        + "reports it and sbt is joining an acceptor for as long as this lasts (ISS-3439). It is "
        + "parked in "
        + position
        + portOwnership(port);
  }

  /**
   * The port sbt is waiting on, read out of {@code sun.java.command} — which for a forked test JVM
   * is the two words {@code "sbt.ForkMain <port>"}. Negative for anything else, which is the guard
   * that keeps this agent inert in every JVM that is not one of sbt's forks.
   */
  static int forkMainPort(final String javaCommand) {
    if (javaCommand == null) return -1;
    final String[] words = javaCommand.trim().split("\\s+");
    if (words.length < 2 || !ForkMainClass.equals(words[0])) return -1;
    try {
      final int port = Integer.parseInt(words[1]);
      return port > 0 && port <= 65535 ? port : -1;
    } catch (final NumberFormatException e) {
      return -1;
    }
  }

  private static void atExit(final Instrumentation instrumentation, final int port) {
    try {
      Frozen.set(true);
      if (wasConfigured(instrumentation)) return;
      System.err.println(diagnostic(port, probe(port), LastPosition.get()));
      System.err.flush();
      Runtime.getRuntime().halt(ExitStatus);
    } catch (final Throwable t) {
      // Same bargain as premain: never take a fork down over a failure in this agent.
    }
  }

  static boolean wasConfigured(final Instrumentation instrumentation) {
    for (final Class<?> loaded : instrumentation.getAllLoadedClasses()) {
      if (ConfiguredMarkerClass.equals(loaded.getName())) return true;
    }
    return false;
  }

  /**
   * Dials sbt's socket the way {@code ForkMain} was supposed to, and says what happened. This is
   * the only evidence about the cause that anything in this process can gather: bytes coming back
   * mean sbt's acceptor was still parked in {@code accept()} — the wedge, confirmed from the inside
   * — and a connect that fails names the machine-level reason the real one probably failed too.
   *
   * <p>It is also a second, independent release of the acceptor: connecting is what ends its
   * {@code accept()}, so sbt is freed even in the case where the halt below somehow is not what
   * reaches it.
   */
  static String probe(final int port) {
    final Socket socket = new Socket();
    try {
      socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), ProbeTimeoutMillis);
      socket.setSoTimeout(ProbeTimeoutMillis);
      final InputStream in = socket.getInputStream();
      final int first = in.read();
      return first >= 0
          ? "something accepted it and started writing, which is sbt's acceptor still in accept() — so the real fork never connected"
          : "something accepted it and closed it without writing";
    } catch (final SocketTimeoutException e) {
      // Deliberately not "sbt accepted it". At TCP level an accept by sbt is indistinguishable from
      // a connection sitting in its backlog AND from one accepted by a different process holding
      // the same port; only the ownership line below tells the three apart.
      return "something accepted it but wrote nothing within " + ProbeTimeoutMillis + "ms";
    } catch (final Throwable t) {
      return "could not be reached: " + t;
    } finally {
      try {
        socket.close();
      } catch (final Throwable ignored) {
        // Nothing left to do about it; the JVM is one statement from halting.
      }
    }
  }

  /**
   * The pid of the JVM that forked this one, which for a forked test JVM is sbt's own. Negative when
   * the platform will not say, which {@link #hijackVerdict} treats as "cannot judge" rather than as
   * evidence either way — a verdict from an unknown parent would call every listener foreign.
   */
  static long parentPid() {
    try {
      return ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(-1L);
    } catch (final Throwable t) {
      return -1L;
    }
  }

  /**
   * WHO IS ACTUALLY LISTENING on the port this fork was told to dial. It is the one question
   * {@link #probe} cannot answer, and it separates the two ways this fails.
   *
   * <p>sbt binds the WILDCARD — {@code new ServerSocket(0)} in {@code ForkTests.mainTestTask} — and
   * a wildcard bind does not reserve a port. Another process may hold {@code 127.0.0.1} on the same
   * port, the kernel's wildcard ephemeral allocator will hand out a port already held that way, and
   * every connection is delivered to the MOST SPECIFIC matching listener. So a fork dialling
   * {@code 127.0.0.1} reaches that process instead of sbt: sbt's acceptor stays in {@code accept()}
   * with nothing to accept, and the fork blocks on a stream header nothing is going to write.
   *
   * <p>That is indistinguishable, from inside this JVM, from sbt simply being slow to accept — both
   * present as a connect that succeeded and a read that never returns. Naming the holder is what
   * tells them apart, and it turns an occurrence somebody has to reproduce into one line.
   */
  static String portOwnership(final int port) {
    return portOwnership(port, LsofBinary, OwnerTimeoutMillis);
  }

  /**
   * The binary and the bound are parameters so that the two ways the lookup can fail to answer —
   * a machine with no {@code lsof}, and one too slow to produce one inside the bound — are driven
   * for real by {@code ForkSafeExitAgentSpec} rather than waited for. Nothing but that spec passes
   * anything other than {@link #LsofBinary} and {@link #OwnerTimeoutMillis}.
   */
  static String portOwnership(final int port, final String lsof, final long timeoutMillis) {
    try {
      return hijackVerdict(listeners(port, lsof, timeoutMillis), parentPid(), port);
    } catch (final Throwable t) {
      return Undetermined + " " + LookupUnanswered + ": " + because(t) + ".";
    }
  }

  /** A throwable as one clause of a sentence: its own message where it has one, else its type. */
  static String because(final Throwable t) {
    final String message = t.getMessage();
    return message == null || message.isBlank() ? t.toString() : message;
  }

  /**
   * The listeners on one port, as {@code lsof} reports them. Its default columns are command, pid,
   * user, fd, type, device, size, node and address — no arguments and no environment, which is the
   * property that makes this safe to print: the report below lands in a build log that outlives the
   * machine, and this fleet runs every session as one uid.
   *
   * <p>Bounded and killed rather than waited on, because this runs on a fork that is already dying
   * and must not be what stops it. Every way of not getting an answer THROWS, saying which, rather
   * than returning empty: a caller handed nothing cannot tell "the machine says no one is listening"
   * from "the machine could not be asked", and those send a reader to opposite places.
   */
  static String listeners(final int port, final String lsof, final long timeoutMillis)
      throws Exception {
    if (!new File(lsof).canExecute()) {
      throw new IllegalStateException(lsof + " is not executable on this machine");
    }
    final long started = System.nanoTime();
    final Process process =
        new ProcessBuilder(lsof, "-nP", "-w", "-iTCP:" + port, "-sTCP:LISTEN")
            .redirectErrorStream(true)
            .start();
    process.getOutputStream().close();
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    final Thread drain =
        new Thread(
            () -> {
              try (InputStream in = process.getInputStream()) {
                final byte[] buffer = new byte[4096];
                int read = in.read(buffer);
                while (read >= 0) {
                  captured.write(buffer, 0, read);
                  read = in.read(buffer);
                }
              } catch (final Throwable ignored) {
                // Partial output still names a holder; nothing here is worth a fork over.
              }
            },
            "fork-safe-exit-owner");
    drain.setDaemon(true);
    drain.start();
    // The bound is measured from BEFORE the spawn, not from its return: on a loaded machine
    // start() alone can outlast it, and a process that has already exited by then would otherwise
    // be read as an answer that arrived in time.
    final long remainingNanos =
        TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - (System.nanoTime() - started);
    final boolean finished = process.waitFor(Math.max(0L, remainingNanos), TimeUnit.NANOSECONDS);
    if (!finished || System.nanoTime() - started > TimeUnit.MILLISECONDS.toNanos(timeoutMillis)) {
      process.destroyForcibly();
      throw new IllegalStateException(
          lsof + " did not finish within " + timeoutMillis + "ms"
              + (finished ? "" : " and was killed"));
    }
    drain.join(timeoutMillis);
    final String answer = captured.toString(StandardCharsets.UTF_8);
    if (answer.isEmpty() && drain.isAlive()) {
      throw new IllegalStateException(
          lsof + " exited, but nothing it wrote could be read within " + timeoutMillis + "ms");
    }
    return answer;
  }

  /**
   * The verdict, kept pure so it is asserted against fixed {@code lsof} text rather than against
   * whatever happens to be listening on the machine running the suite.
   *
   * <p>Only the command, pid and bound address are carried into the report. The rest of the line is
   * dropped rather than echoed, so a future {@code lsof} column cannot put anything into a build log
   * that was not asked for.
   */
  static String hijackVerdict(final String lsofOutput, final long parentPid, final int port) {
    final String[] holders = holders(lsofOutput, parentPid, port);
    final String foreign = holders[Foreign];
    final String own = holders[Own];
    if (foreign.isEmpty() && own.isEmpty()) return Undetermined;
    if (parentPid < 0) {
      return " Listening on port " + port + ": " + foreign + ". This fork's parent could not be "
          + "identified, so whether that is sbt is not decided here.";
    }
    if (foreign.isEmpty()) {
      return " The only listener on port " + port + " is this fork's parent — " + own
          + " — so the connection did reach sbt, which had not returned from accept().";
    }
    return " " + HijackMarker + ": port " + port + " is held by " + foreign
        + ", which is not this fork's parent (pid " + parentPid + ")"
        + (own.isEmpty() ? "" : ", and by sbt itself on " + own)
        + ". sbt binds the WILDCARD, which does not reserve the port, and the kernel delivers each "
        + "connection to the MOST SPECIFIC matching listener — so this fork's connect reached that "
        + "process rather than sbt, and sbt's acceptor is still in accept() with nothing to accept. "
        + "The fork is blocked on a stream header that process will never write. Free that port on "
        + "this machine; retrying the build only redraws the same lottery.";
  }

  /** Where {@link #holders} puts the listeners {@code ownerPid} does NOT hold. */
  static final int Foreign = 0;

  /** Where {@link #holders} puts the listeners {@code ownerPid} holds. */
  static final int Own = 1;

  /**
   * The listeners {@code lsof} names on one port, split by whether {@code ownerPid} is the process
   * holding them: indexed by {@link #Foreign} and {@link #Own}, each {@code "command (pid N) on
   * address"}, comma-separated, and empty when there are none. Shared with {@code ForkSafeRunner},
   * which asks the same question from the other end of a different socket: there the rightful
   * holder is the JVM asking, not its parent.
   *
   * <p>An array rather than a small result type, because this agent is ONE class:
   * {@code ForkSafeExitAgentSpec} runs it from a jar holding that class alone, and a nested class
   * missing from the jar a fork runs under is a lookup that dies without saying why.
   */
  static String[] holders(final String lsofOutput, final long ownerPid, final int port) {
    final StringBuilder foreign = new StringBuilder();
    final StringBuilder own = new StringBuilder();
    if (lsofOutput != null) {
      final String suffix = ":" + port;
      for (final String line : lsofOutput.split("\\R")) {
        final String[] words = line.trim().split("\\s+");
        if (words.length < 3 || "COMMAND".equals(words[0])) continue;
        final long pid;
        try {
          pid = Long.parseLong(words[1]);
        } catch (final NumberFormatException e) {
          continue;
        }
        String address = null;
        for (final String word : words) {
          if (word.endsWith(suffix)) address = word;
        }
        if (address == null) continue;
        final StringBuilder into = pid == ownerPid ? own : foreign;
        if (into.length() > 0) into.append(", ");
        into.append(words[0]).append(" (pid ").append(pid).append(") on ").append(address);
      }
    }
    return new String[] {foreign.toString(), own.toString()};
  }

  static String diagnostic(final int port, final String probeResult, final String lastPosition) {
    final Runtime runtime = Runtime.getRuntime();
    final long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
    return "[fork-safe-exit-agent] This forked test JVM is exiting without ever having received its "
        + ConfiguredMarkerClass
        + " from sbt on 127.0.0.1:"
        + port
        + ", so it ran no tests. sbt.ForkMain would have reported that as exit 0 and sbt would have "
        + "joined an acceptor nothing can connect to, hanging the whole build (ISS-3439). Exiting "
        + ExitStatus
        + " instead. Probe of that port: "
        + probeResult
        + ". JVM uptime "
        + uptime
        + "ms; heap max="
        + (runtime.maxMemory() >> 20)
        + "M total="
        + (runtime.totalMemory() >> 20)
        + "M free="
        + (runtime.freeMemory() >> 20)
        + "M."
        + lastSeen(lastPosition)
        + portOwnership(port);
  }

  /**
   * The statement the fork died in, as far as anything here can name it: the last stack the
   * watchdog sampled before the JVM began shutting down. Absent only for a fork that did not
   * survive its first {@link #SampleIntervalMillis}, which is itself the answer.
   */
  static String lastSeen(final String lastPosition) {
    if (lastPosition == null) {
      return " No position was sampled, so it did not survive its first "
          + SampleIntervalMillis
          + "ms.";
    }
    return " Last seen in " + lastPosition;
  }
}
