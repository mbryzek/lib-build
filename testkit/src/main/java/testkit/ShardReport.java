package testkit;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Prints what one test shard cost, as its forked JVM exits:
 *
 * <pre>ci-shard: integrations__3 wall 81.204 cpu 140.531 rss_mb 1712</pre>
 *
 * <p>A shard is one forked test JVM with its own database, running one suite at a time (design
 * DES-18577, "Test isolation"), and the runner's shard count is derived from what a shard is
 * assumed to cost: about a core and 1.7 GB resident. This line is what corrects that assumption.
 * Wall and CPU seconds are this JVM's own, from start to exit; {@code rss_mb} is its resident set
 * as it exits, which for a JVM that never hands heap back is its peak.
 *
 * <p>Silent unless {@code DEV_TEST_SHARD} names the shard, which only `TestDatabasePlugin` sets, so
 * an unsharded subproject's output is unchanged. Installed by {@link ForkSafeExitAgent#premain},
 * which every forked test JVM already runs under.
 */
public final class ShardReport {

  static final String ShardEnvVar = "DEV_TEST_SHARD";

  /** `ps` by absolute path: a bare name could be anything PATH says. */
  static final String PsBinary = "/bin/ps";

  private ShardReport() {}

  static void install() {
    final String shard = System.getenv(ShardEnvVar);
    if (shard == null || shard.trim().isEmpty()) return;
    Runtime.getRuntime()
        .addShutdownHook(new Thread(() -> System.out.println(line(shard.trim())), "shard-report"));
  }

  static String line(final String shard) {
    final double wall = ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0;
    final double cpu = cpuSeconds();
    final long rss = residentMegabytes(ProcessHandle.current().pid());
    return format(shard, wall, cpu, rss);
  }

  /** Seconds to the millisecond, `Locale.ROOT` so the decimal point is a point on every machine. */
  static String format(final String shard, final double wall, final double cpu, final long rssMb) {
    return "ci-shard: "
        + shard
        + " wall "
        + String.format(Locale.ROOT, "%.3f", wall)
        + " cpu "
        + (cpu < 0 ? "?" : String.format(Locale.ROOT, "%.3f", cpu))
        + " rss_mb "
        + (rssMb < 0 ? "?" : Long.toString(rssMb));
  }

  private static double cpuSeconds() {
    return ProcessHandle.current()
        .info()
        .totalCpuDuration()
        .map(d -> d.toNanos() / 1e9)
        .orElse(-1.0);
  }

  /** Kilobytes from {@code ps -o rss=}, in megabytes; -1 when it cannot be read. */
  static long residentMegabytes(final long pid) {
    try {
      final Process p =
          new ProcessBuilder(PsBinary, "-o", "rss=", "-p", Long.toString(pid))
              .redirectErrorStream(true)
              .start();
      final String out;
      try (InputStream in = p.getInputStream()) {
        out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
      }
      if (!p.waitFor(5, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return -1;
      }
      return Long.parseLong(out) / 1024;
    } catch (final Exception e) {
      return -1;
    }
  }
}
