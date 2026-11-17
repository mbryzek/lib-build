package testshards

import java.util.concurrent.atomic.AtomicInteger

/** Runs `body` once, when the last of a test task's `groups` test groups has finished.
  *
  * sbt runs `Tests.Cleanup` once per test GROUP, each just after that group's fork exits, and does
  * not say which group it runs for or how many are still running. A sharded subproject is several
  * groups on databases of their own, and dropping them all when the FIRST group finishes would drop,
  * `WITH (FORCE)`, the database of a shard still running its suites. So each group's cleanup
  * arrives here, and the one that brings the count to zero runs `body`.
  *
  * A group whose fork crashes skips its cleanup, and `body` then never runs; the lanes it would
  * have dropped are dropped by the next run's reset, or by `dev db session gc`.
  */
final class LastGroup(groups: Int, body: () => Unit) {

  private val remaining = new AtomicInteger(groups)

  /** Called from each group's cleanup. Runs `body` on the call that finishes the last group, and on
    * no other.
    */
  def finished(): Unit =
    if (remaining.decrementAndGet() == 0) body()
}
