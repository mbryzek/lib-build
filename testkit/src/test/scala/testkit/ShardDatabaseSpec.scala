package testkit

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.must.Matchers
import testshards.LaneClone

/** What a forked test JVM decides to clone, from the environment `TestDatabasePlugin` hands it.
  * scala-build-app's `LaneClone.scala` is compiled into `testkit` itself, so the refusals asserted here are
  * the ones the fork and the meta-build both run.
  */
class ShardDatabaseSpec extends AnyFunSpec with Matchers {

  private val Session = "platformdb_sess_i25743"
  private val Lane = s"${Session}__core__2"
  private val Base = "jdbc:postgresql://localhost:5501/"

  private def env(pairs: (String, String)*): String => Option[String] = pairs.toMap.get

  describe("plan") {
    it("clones nothing when no template is named, whatever the url") {
      ShardDatabase.plan(env("CONF_DB_DEV_URL" -> s"$Base$Lane")) mustBe None
      ShardDatabase.plan(env()) mustBe None
      ShardDatabase.plan(env(LaneClone.TemplateEnvVar -> "  ")) mustBe None
    }

    it("clones the database the url names, from the template, on the url's server") {
      ShardDatabase.plan(
        env(LaneClone.TemplateEnvVar -> Session, "CONF_DB_DEV_URL" -> s"$Base$Lane?sslmode=disable")
      ) mustBe Some(ShardDatabase.Plan(Base, Session, Lane))
    }

    it("refuses a template with no postgresql url to clone into") {
      val e = intercept[RuntimeException](ShardDatabase.plan(env(LaneClone.TemplateEnvVar -> Session)))
      e.getMessage must include("CONF_DB_DEV_URL")
    }

    it("refuses to clone over the session database itself") {
      val e = intercept[RuntimeException](
        ShardDatabase.plan(env(LaneClone.TemplateEnvVar -> Session, "CONF_DB_DEV_URL" -> s"$Base$Session"))
      )
      e.getMessage must include("not a lane database")
    }
  }

  describe("plan, on a proof's run") {
    it("carries the lifetime of the run ciProof names") {
      ShardDatabase
        .plan(
          env(
            LaneClone.TemplateEnvVar -> Session,
            "CONF_DB_DEV_URL" -> s"$Base$Lane",
            LaneClone.ProofRunEnvVar -> LaneClone.proofRun("alone", 2, 5),
          )
        )
        .map(_.lifetime) mustBe Some(LaneClone.Lifetime(reuse = true, keep = true))
    }

    it("is fresh on every run that is not a proof's") {
      ShardDatabase
        .plan(env(LaneClone.TemplateEnvVar -> Session, "CONF_DB_DEV_URL" -> s"$Base$Lane"))
        .map(
          _.lifetime
        ) mustBe Some(LaneClone.Fresh)
    }
  }

  describe("LaneClone.lifetime") {
    def of(mode: String, index: Int, runs: Int) =
      LaneClone.lifetime(Some(LaneClone.proofRun(mode, index, runs)))

    it("keeps one lane across the alone runs: the first clones and keeps it, the last reuses and drops it") {
      of("alone", 1, 5) mustBe LaneClone.Lifetime(reuse = false, keep = true)
      of("alone", 3, 5) mustBe LaneClone.Lifetime(reuse = true, keep = true)
      of("alone", 5, 5) mustBe LaneClone.Lifetime(reuse = true, keep = false)
    }

    it("gives a proof of one alone run, and every beside run, a fresh lane") {
      of("alone", 1, 1) mustBe LaneClone.Fresh
      of("load", 1, 3) mustBe LaneClone.Fresh
      of("load", 3, 3) mustBe LaneClone.Fresh
    }

    it("is fresh when no run is named, or the name is not one ciProof writes") {
      LaneClone.lifetime(None) mustBe LaneClone.Fresh
      Seq("", "alone", "alone 2", "alone x 5", "alone 6 5", "alone 0 5", "alone 2 5 extra").foreach { value =>
        LaneClone.lifetime(Some(value)) mustBe LaneClone.Fresh
      }
    }
  }

  describe("LaneClone.refusal") {
    it("admits a lane cloned from a session database") {
      LaneClone.refusal(Session, Lane) mustBe None
    }

    it("refuses a lane cloned from a lane") {
      LaneClone.refusal(s"${Session}__api", Lane).get must include("itself a lane")
    }

    it("refuses an empty name") {
      LaneClone.refusal("", Lane) mustBe defined
      LaneClone.refusal(Session, "") mustBe defined
    }
  }

  describe("LaneClone.cloneLine") {
    it("puts the seconds last, to the millisecond, with a point") {
      LaneClone.cloneLine(Lane, 0.2345) mustBe s"ci-db: clone $Lane 0.235"
    }
  }
}
