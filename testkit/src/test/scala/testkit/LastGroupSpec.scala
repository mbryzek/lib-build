package testkit

import java.util.concurrent.atomic.AtomicInteger

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.must.Matchers
import testshards.LastGroup

/** When a sharded subproject's lanes are dropped. scala-build-app's `LastGroup.scala` is compiled into this
  * subproject's tests as well as into the meta-build, so what is asserted here is the code
  * `TestDatabasePlugin` runs from every test group's cleanup.
  */
class LastGroupSpec extends AnyFunSpec with Matchers {

  private def counting(groups: Int): (LastGroup, AtomicInteger) = {
    val runs = new AtomicInteger(0)
    (new LastGroup(groups, () => { val _ = runs.incrementAndGet() }), runs)
  }

  it("runs nothing until the last group finishes, then runs once") {
    val (last, runs) = counting(3)
    last.finished()
    last.finished()
    runs.get mustBe 0
    last.finished()
    runs.get mustBe 1
  }

  it("runs once for a subproject of one group") {
    val (last, runs) = counting(1)
    last.finished()
    runs.get mustBe 1
  }

  it("does not run again for a cleanup past the last") {
    val (last, runs) = counting(2)
    (1 to 4).foreach(_ => last.finished())
    runs.get mustBe 1
  }

  it("runs once when the groups finish on threads of their own at the same moment") {
    val (last, runs) = counting(64)
    val threads = (1 to 64).map(_ => new Thread(() => last.finished()))
    threads.foreach(_.start())
    threads.foreach(_.join())
    runs.get mustBe 1
  }
}
