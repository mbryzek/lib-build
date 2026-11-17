package cilists

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.must.Matchers

import CiTestPhases.{Outcome, Request}

/** What `ciTest`'s arguments ask for, and which phases run after a phase ends each way. */
class CiTestPhasesSpec extends AnyFunSpec with Matchers {

  describe("parse") {

    it("is every subproject in one phase, without a stop, for no arguments") {
      CiTestPhases.parse(Nil) mustBe Right(Request(stop = false, phases = Nil))
      CiTestPhases.parse(Seq("--stop")) mustBe Right(Request(stop = true, phases = Nil))
    }

    it("is one phase of the named subprojects") {
      CiTestPhases.parse(Seq("core", "api")) mustBe Right(Request(stop = false, phases = Seq(Seq("core", "api"))))
    }

    it("splits the phases at --, the first phase first") {
      CiTestPhases.parse(Seq("--stop", "core", "--", "api", "corespecs")) mustBe
        Right(Request(stop = true, phases = Seq(Seq("core"), Seq("api", "corespecs"))))
    }

    it("refuses a -- with nothing on one side, and a second --") {
      CiTestPhases.parse(Seq("--", "api")).isLeft mustBe true
      CiTestPhases.parse(Seq("core", "--")).isLeft mustBe true
      CiTestPhases.parse(Seq("core", "--", "api", "--", "web")).isLeft mustBe true
    }

    it("refuses what is not a subproject name, a --stop out of place included") {
      CiTestPhases.parse(Seq("core/test")).left.toOption.get must include("'core/test' is not a subproject")
      CiTestPhases.parse(Seq("core", "--stop")).isLeft mustBe true
    }

    it("refuses a subproject named twice, since a phase is an order and never a repeat") {
      CiTestPhases.parse(Seq("core", "--", "core")).left.toOption.get must include("core named twice")
    }
  }

  describe("unknown") {
    it("names every subproject the build does not define, in the order named") {
      val request = Request(stop = false, phases = Seq(Seq("web", "core"), Seq("gone")))
      CiTestPhases.unknown(request, Set("core", "api")) mustBe Seq("web", "gone")
    }
  }

  describe("runAll") {

    def run(stop: Boolean, ends: Map[String, Outcome]): (Seq[String], Seq[Outcome], Seq[String]) = {
      val (ran, outcomes, stopped) =
        CiTestPhases.runAll(Seq.empty[String], Seq("first", "second"), stop)((done, p) => (done :+ p, ends(p)))
      (ran, outcomes, stopped)
    }

    it("runs the second phase after a flake, stop or not") {
      run(stop = true, Map("first" -> Outcome.Flaked, "second" -> Outcome.Green)) mustBe
        ((Seq("first", "second"), Seq(Outcome.Flaked, Outcome.Green), Nil))
    }

    it("stops after a spec fails its rerun when asked to, naming the phase it never started") {
      run(stop = true, Map("first" -> Outcome.FailedTwice, "second" -> Outcome.Green)) mustBe
        ((Seq("first"), Seq(Outcome.FailedTwice), Seq("second")))
    }

    it("runs on after a spec fails its rerun when not asked to stop, so every failure gets its verdict") {
      run(stop = false, Map("first" -> Outcome.FailedTwice, "second" -> Outcome.Flaked)) mustBe
        ((Seq("first", "second"), Seq(Outcome.FailedTwice, Outcome.Flaked), Nil))
    }

    it("stops after a failure no rerun clears, whether or not asked to") {
      run(stop = false, Map("first" -> Outcome.NotRerunnable, "second" -> Outcome.Green)) mustBe
        ((Seq("first"), Seq(Outcome.NotRerunnable), Seq("second")))
    }
  }

  describe("red") {
    it("is a spec that failed twice or a failure no rerun clears, never a flake") {
      Seq(Outcome.Green, Outcome.Flaked, Outcome.FailedTwice, Outcome.NotRerunnable).map(CiTestPhases.red) mustBe
        Seq(false, false, true, true)
    }
  }

  describe("stoppedLine") {
    it("names the phase's test tasks") {
      CiTestPhases.stoppedLine(Seq("api", "corespecs")) mustBe "ci-stopped: api/test corespecs/test"
    }
  }
}
