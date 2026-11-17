package testkit

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.must.Matchers
import testshards.TestShards

/** How a sharded subproject's suites are split. scala-build-app's `TestShards.scala` is compiled into this
  * subproject's tests as well as into the meta-build, so what is asserted here is the code
  * `TestDatabasePlugin` groups every sharded test task with.
  */
class TestShardsSpec extends AnyFunSpec with Matchers {

  private val suites = (1 to 40).map(i => s"integrations.Spec$i")

  describe("count") {

    it("takes CI_BUILD_SHARDS when the runner exports a positive integer") {
      TestShards.count(Map("CI_BUILD_SHARDS" -> "6"), logicalCores = 10) mustBe 6
      TestShards.count(Map("CI_BUILD_SHARDS" -> " 8 "), logicalCores = 10) mustBe 8
    }

    it("is max(2, logical cores / 3) when it is unset or unreadable") {
      TestShards.count(Map.empty, logicalCores = 18) mustBe 6
      TestShards.count(Map.empty, logicalCores = 4) mustBe 2
      TestShards.count(Map("CI_BUILD_SHARDS" -> ""), logicalCores = 10) mustBe 3
      TestShards.count(Map("CI_BUILD_SHARDS" -> "0"), logicalCores = 10) mustBe 3
      TestShards.count(Map("CI_BUILD_SHARDS" -> "six"), logicalCores = 10) mustBe 3
    }
  }

  describe("parse") {

    it("reads an empty file as listing no suite") {
      TestShards.parse("") mustBe Right(Map.empty)
      TestShards.parse("\n# nothing measured yet\n") mustBe Right(Map.empty)
    }

    it("reads one suite and its median seconds a line") {
      TestShards.parse("a.SlowSpec 12.5\r\na.FastSpec 0.25\n") mustBe
        Right(Map("a.SlowSpec" -> 12.5, "a.FastSpec" -> 0.25))
    }

    it("refuses a line that is not `<suite> <seconds>`, naming it") {
      TestShards.parse("a.SlowSpec 12.5\na.FastSpec\n") mustBe
        Left("line 2 of the shard timings is not `<suite> <seconds>`: a.FastSpec")
      TestShards.parse("a.SlowSpec fast\n").isLeft mustBe true
      TestShards.parse("a.SlowSpec -1\n").isLeft mustBe true
    }

    it("reads a subproject's start seconds as a line of their own, apart from the suites") {
      val timings = TestShards.parse("a.SlowSpec 12.5\nstart:a 4.25\n").toOption.get
      TestShards.suiteSeconds(timings) mustBe Map("a.SlowSpec" -> 12.5)
      TestShards.startSeconds(timings) mustBe Map("a" -> 4.25)
    }
  }

  describe("timingsFile") {
    it("is the fetched file CI_SHARD_TIMINGS names, else the committed one under the root") {
      TestShards.timingsFile(Map("CI_SHARD_TIMINGS" -> "/scratch/test-shards.txt"), "/repo") mustBe
        "/scratch/test-shards.txt"
      TestShards.timingsFile(Map.empty, "/repo") mustBe "/repo/ci/test-shards.txt"
      TestShards.timingsFile(Map("CI_SHARD_TIMINGS" -> " "), "/repo") mustBe "/repo/ci/test-shards.txt"
    }
  }

  describe("ceiling") {
    it("takes CI_BUILD_SHARDS_MAX when the runner exports a positive integer") {
      TestShards.ceiling(Map("CI_BUILD_SHARDS_MAX" -> "8"), logicalCores = 18, floor = 2) mustBe 8
    }

    it("is a third of the logical cores otherwise") {
      TestShards.ceiling(Map.empty, logicalCores = 18, floor = 2) mustBe 6
      TestShards.ceiling(Map("CI_BUILD_SHARDS_MAX" -> "lots"), logicalCores = 18, floor = 2) mustBe 6
    }

    it("is never below the floor the runner budgeted") {
      TestShards.ceiling(Map.empty, logicalCores = 18, floor = 8) mustBe 8
      TestShards.ceiling(Map("CI_BUILD_SHARDS_MAX" -> "1"), logicalCores = 18, floor = 2) mustBe 2
    }
  }

  describe("admit") {
    it("always admits up to the floor, however loaded the machine") {
      TestShards.admit(running = 2, floor = 2, ceiling = 6, load = 40.0, logicalCores = 18) mustBe true
      TestShards.admit(running = 2, floor = 2, ceiling = 6, load = -1.0, logicalCores = 18) mustBe true
    }

    it("admits past the floor up to the ceiling while the load is under half the cores") {
      TestShards.admit(running = 3, floor = 2, ceiling = 6, load = 4.0, logicalCores = 18) mustBe true
      TestShards.admit(running = 6, floor = 2, ceiling = 6, load = 8.9, logicalCores = 18) mustBe true
      TestShards.admit(running = 7, floor = 2, ceiling = 6, load = 0.0, logicalCores = 18) mustBe false
    }

    it("admits nothing past the floor on a busy machine, or one whose load it cannot read") {
      TestShards.admit(running = 3, floor = 2, ceiling = 6, load = 9.0, logicalCores = 18) mustBe false
      TestShards.admit(running = 3, floor = 2, ceiling = 6, load = -1.0, logicalCores = 18) mustBe false
    }
  }

  describe("shardsFor") {
    it("is the floor when nothing is measured, so placement hashes as it did") {
      TestShards.shardsFor(suites, Map.empty, floor = 2, ceiling = 6) mustBe 2
    }

    it("is one shard per minute of measured time, between one and the ceiling") {
      val timings = suites.map(_ -> 10.0).toMap // 400 seconds
      TestShards.shardsFor(suites, timings, floor = 2, ceiling = 6) mustBe 6
      TestShards.shardsFor(suites.take(13), timings, floor = 2, ceiling = 6) mustBe 3
      TestShards.shardsFor(suites.take(2), timings, floor = 2, ceiling = 6) mustBe 1
    }

    it("weights a suite the file does not list at the file's median") {
      val timings = Map("a.A" -> 50.0, "a.B" -> 30.0, "a.C" -> 1.0)
      TestShards.shardsFor(Seq("a.A", "a.New"), timings, floor = 2, ceiling = 6) mustBe 2
      TestShards.shardsFor(Seq("a.C"), timings, floor = 2, ceiling = 6) mustBe 1
    }
  }

  describe("assign") {

    it("places every suite in exactly one shard") {
      val shards = TestShards.assign(suites, Map.empty, 6)
      shards.length mustBe 6
      shards.flatten.sorted mustBe suites.sorted
    }

    it("hashes a suite to the same shard whatever else is in the subproject") {
      val all = TestShards.assign(suites, Map.empty, 6)
      val fewer = TestShards.assign(suites.take(7), Map.empty, 6)
      suites.take(7).foreach { s =>
        all.indexWhere(_.contains(s)) mustBe fewer.indexWhere(_.contains(s))
        all.indexWhere(_.contains(s)) mustBe TestShards.hashShard(s, 6)
      }
    }

    it("places the longest listed suites first, each onto the least-loaded shard") {
      val timings = Map("a.A" -> 10.0, "a.B" -> 6.0, "a.C" -> 5.0, "a.D" -> 4.0, "a.E" -> 1.0)
      TestShards.assign(timings.keys.toSeq, timings, 2) mustBe
        Vector(Vector("a.A", "a.D"), Vector("a.B", "a.C", "a.E"))
    }

    it("weights a suite the file does not list at the file's median") {
      val timings = Map("a.A" -> 9.0, "a.B" -> 3.0, "a.C" -> 1.0)
      // a.New weighs 3.0, so it follows a.B onto the shard a.A is not on, and a.C joins them there.
      TestShards.assign(Seq("a.A", "a.B", "a.C", "a.New"), timings, 2) mustBe
        Vector(Vector("a.A"), Vector("a.B", "a.C", "a.New"))
    }

    it("leaves a shard empty when there are fewer suites than shards") {
      TestShards.assign(Seq("a.A"), Map("a.A" -> 1.0), 3).map(_.length).sorted mustBe Vector(0, 0, 1)
    }
  }

  describe("scope") {

    val found = Map(
      "hoa" -> Seq("hoa.AccountsSpec", "hoa.ZonesSpec"),
      "integrations" -> Seq(
        "integrations.mail.MailerSpec",
        "integrations.mail.threads.ThreadsSpec",
        "integrations.issues.IssuesSpec",
        "integrations.mailbox.MailboxSpec",
      ),
      "playbook" -> Seq("playbook.ClubsSpec"),
    )

    it("plans hoa and integrations:mail as hoa's suites and integrations.mail's, and no playbook suite") {
      val planned = TestShards.scoped(found, TestShards.scope(Seq("hoa", "integrations:mail")))
      planned mustBe Map(
        "hoa" -> Seq("hoa.AccountsSpec", "hoa.ZonesSpec"),
        "integrations" -> Seq("integrations.mail.MailerSpec", "integrations.mail.threads.ThreadsSpec"),
      )
      planned.contains("playbook") mustBe false
    }

    it("reads a subproject named whole and by package as whole, and several packages as their union") {
      TestShards.scope(Seq("integrations:mail", "integrations")) mustBe Map("integrations" -> None)
      TestShards.scope(Seq("integrations", "integrations:mail")) mustBe Map("integrations" -> None)
      TestShards.scope(Seq("integrations:mail", "integrations:issues")) mustBe
        Map("integrations" -> Some(Set("mail", "issues")))
      TestShards.scope(Nil) mustBe Map.empty
    }
  }

  describe("sizing") {

    it("takes CI_SHARD_COUNT only when it is a positive integer") {
      TestShards.sizing(Map("CI_SHARD_COUNT" -> " 7 ")) mustBe Some(TestShards.Exactly(7))
      TestShards.sizing(Map("CI_SHARD_COUNT" -> "0")) mustBe None
      TestShards.sizing(Map("CI_SHARD_COUNT" -> "seven")) mustBe None
      TestShards.sizing(Map("CI_SHARD_SECONDS" -> "139")) mustBe None
      TestShards.sizing(Map.empty) mustBe None
    }
  }

  describe("plan") {
    // Ten suites of 30 usual seconds in each named subproject.
    def subprojects(names: Seq[String]): Map[String, Seq[String]] =
      names.map(n => n -> (1 to 10).map(i => s"$n.Spec$i")).toMap
    def timed(suites: Map[String, Seq[String]], seconds: Double): Map[String, Double] =
      suites.values.flatten.map(_ -> seconds).toMap
    // Each subproject's start measured, at `seconds`.
    def started(suites: Map[String, Seq[String]], seconds: Double): Map[String, Double] =
      suites.keys.map(sub => s"${TestShards.StartPrefix}$sub" -> seconds).toMap

    it("runs alone with nothing measured, since there is no weight to split by") {
      TestShards.plan(subprojects(Seq("api")), Map.empty) mustBe TestShards.Alone(
        0.0,
        "nothing measured in the shard timings"
      )
    }

    it("runs alone under 600 usual seconds") {
      val suites = subprojects(Seq("api"))
      TestShards.plan(suites, timed(suites, 59.0)) mustBe TestShards.Alone(590.0, "under 600 usual seconds")
    }

    it("splits at 600 usual seconds into one shard per 300") {
      val suites = subprojects(Seq("api", "core", "hoa"))
      TestShards.plan(suites, timed(suites, 30.0) ++ started(suites, 0.0)) match {
        case TestShards.Split(seconds, shards, _) =>
          seconds mustBe 900.0
          shards.map(_.label) mustBe Vector("api", "core", "hoa")
          shards.map(_.number) mustBe Vector(1, 2, 3)
        case alone => fail(s"expected a split, got $alone")
      }
    }

    it("plans exactly the repository's shard count, balanced by measured time, however small or large the total") {
      val suites = subprojects((1 to 12).map(i => s"p$i"))
      TestShards.plan(suites, timed(suites, 30.0), Some(TestShards.Exactly(7))) match {
        case TestShards.Split(seconds, shards, _) =>
          seconds mustBe 3600.0
          shards.size mustBe 7
          shards.flatMap(_.suites).sorted mustBe suites.toVector.flatMap { case (sub, ns) =>
            ns.map(n => s"$sub/$n")
          }.sorted
          (shards.map(_.seconds).max - shards.map(_.seconds).min) must be <= 300.0
        case alone => fail(s"expected a split, got $alone")
      }
      val small = subprojects(Seq("api"))
      TestShards.plan(small, timed(small, 1.0), Some(TestShards.Exactly(3))) match {
        case TestShards.Split(_, shards, _) => shards.size mustBe 3
        case alone => fail(s"expected a split, got $alone")
      }
      TestShards.plan(small, timed(small, 1.0), Some(TestShards.Exactly(1))) match {
        case TestShards.Split(_, shards, _) => shards.size mustBe 1
        case alone => fail(s"expected a split, got $alone")
      }
    }

    it("files one shard per suite when the suites are fewer than the shard count") {
      val few = Map("api" -> Seq("api.A", "api.B"), "core" -> Seq("core.C"))
      TestShards.plan(few, timed(few, 30.0), Some(TestShards.Exactly(7))) match {
        case TestShards.Split(_, shards, _) =>
          shards.size mustBe 3
          shards.map(_.suites.size) mustBe Vector(1, 1, 1)
        case alone => fail(s"expected a split, got $alone")
      }
    }

    it("is never more than six shards") {
      val suites = subprojects((1 to 12).map(i => s"p$i"))
      TestShards.plan(suites, timed(suites, 30.0)) match {
        case TestShards.Split(_, shards, _) => shards.size mustBe 6
        case alone => fail(s"expected a split, got $alone")
      }
    }

    it("is never fewer than two shards") {
      val suites = subprojects(Seq("api", "core"))
      TestShards.plan(suites, timed(suites, 30.0)) match {
        case TestShards.Split(_, shards, _) => shards.size mustBe 2
        case alone => fail(s"expected a split, got $alone")
      }
    }

    it("packs small subprojects together and names the shard after its largest parts") {
      val big = subprojects(Seq("integrations"))
      val small = Map("a" -> Seq("a.A"), "b" -> Seq("b.B"), "c" -> Seq("c.C"))
      val timings = timed(big, 60.0) ++ Map("a.A" -> 30.0, "b.B" -> 20.0, "c.C" -> 10.0)
      TestShards.plan(big ++ small, timings ++ started(big ++ small, 0.0)) match {
        case TestShards.Split(_, shards, _) =>
          // 660 usual seconds is 3 shards of 220: integrations is cut in three (240, 180, 180) and the
          // small subprojects fill the two lighter shards, longest first.
          shards.map(_.label) mustBe Vector(
            "integrations 1 of 3",
            "integrations 2 of 3, a",
            "integrations 3 of 3, b, 1 more"
          )
          shards.map(_.seconds) mustBe Vector(240.0, 210.0, 210.0)
        case alone => fail(s"expected a split, got $alone")
      }
    }

    it("cuts a subproject larger than a shard into equal parts, every suite in exactly one") {
      val suites = Map("integrations" -> (1 to 40).map(i => s"integrations.Spec$i"))
      TestShards.plan(suites, timed(suites, 30.0) ++ started(suites, 0.0)) match {
        case TestShards.Split(seconds, shards, _) =>
          seconds mustBe 1200.0
          shards.map(_.label) mustBe Vector(
            "integrations 1 of 4",
            "integrations 2 of 4",
            "integrations 3 of 4",
            "integrations 4 of 4"
          )
          shards.map(_.seconds) mustBe Vector(300.0, 300.0, 300.0, 300.0)
          shards.flatMap(_.suites).sorted mustBe suites("integrations").map(s => s"integrations/$s").sorted
        case alone => fail(s"expected a split, got $alone")
      }
    }

    it("weights a suite the file does not list at the file's median") {
      val suites = Map("api" -> (1 to 20).map(i => s"api.Spec$i"))
      // Only one suite listed, at 40 seconds: the other nineteen weigh 40 each too.
      TestShards.plan(suites, Map("api.Spec1" -> 40.0)).seconds mustBe 800.0
    }

    it("renders one tab-separated line per shard, the suites last") {
      val suites = subprojects(Seq("api", "core"))
      TestShards.plan(suites, timed(suites, 30.0) ++ started(suites, 0.0)) match {
        case split: TestShards.Split =>
          val lines = TestShards.render(split).split("\n").toVector
          lines.head mustBe "# ci-shard-plan 1: 2 shards, 600 usual seconds"
          lines(1).split("\t").toVector.take(3) mustBe Vector("1", "300", "api")
          lines(1).split("\t")(3).split(" ").toVector mustBe (1 to 10).map(i => s"api/api.Spec$i").sorted.toVector
          TestShards.summary(split) mustBe Vector(
            "ci-shard-plan: split 2 shards, 600 usual seconds",
            "ci-shard-plan: cut at 1/1 of a share where a start is measured, 1/2 where none is",
            "ci-shard-plan: shard 1 300s api (10 suites)",
            "ci-shard-plan: shard 2 300s core (10 suites)",
          )
        case alone => fail(s"expected a split, got $alone")
      }
    }

    it("cuts a subproject with no start measured at half a share, and says so") {
      val suites = subprojects(Seq("api", "core"))
      TestShards.plan(suites, timed(suites, 30.0)) match {
        case split @ TestShards.Split(seconds, shards, cut) =>
          cut mustBe TestShards.Cut(None, 2)
          seconds mustBe 600.0
          shards.flatMap(_.parts.map(_.label)).sorted mustBe Vector(
            "api 1 of 2",
            "api 2 of 2",
            "core 1 of 2",
            "core 2 of 2"
          )
          TestShards.summary(split)(1) mustBe "ci-shard-plan: cut every subproject at 1/2 of a share, no start measured"
        case alone => fail(s"expected a split, got $alone")
      }
    }

    // `big` is twelve suites of 50 and `x` one of 100, over two shards: a share of 350.
    val big = Map("big" -> (1 to 12).map(i => s"big.Spec$i"), "x" -> Seq("x.Spec"))
    val bigTimings = timed(big, 50.0) ++ Map("x.Spec" -> 100.0)

    it("cuts finer while the suite seconds it moves off the slowest shard exceed the starts it adds") {
      // Free to start, big is best cut at a quarter of a share: seven parts and x balance at 350 a shard.
      TestShards.plan(big, bigTimings + ("start:big" -> 0.0), Some(TestShards.Exactly(2))) match {
        case TestShards.Split(_, shards, cut) =>
          cut mustBe TestShards.Cut(Some(4), 2)
          shards.map(_.seconds) mustBe Vector(350.0, 350.0)
        case alone => fail(s"expected a split, got $alone")
      }
    }

    it("keeps the coarser cut once the starts a finer one adds outweigh what it moves") {
      // At 30 seconds a start, every further part of big costs more than it balances: two halves win.
      TestShards.plan(big, bigTimings + ("start:big" -> 30.0), Some(TestShards.Exactly(2))) match {
        case TestShards.Split(seconds, shards, cut) =>
          cut mustBe TestShards.Cut(Some(1), 2)
          shards.map(_.label) mustBe Vector("big 1 of 2, x", "big 2 of 2")
          shards.map(_.seconds) mustBe Vector(430.0, 330.0)
          seconds mustBe 760.0
        case alone => fail(s"expected a split, got $alone")
      }
    }

    it("weighs every part as its suites plus its subproject's start, and an unmeasured one without") {
      TestShards.plan(big, bigTimings + ("start:big" -> 30.0), Some(TestShards.Exactly(2))) match {
        case TestShards.Split(_, shards, _) =>
          shards.flatMap(_.parts).map(p => p.label -> p.seconds).sorted mustBe
            Vector("big 1 of 2" -> 330.0, "big 2 of 2" -> 330.0, "x" -> 100.0)
        case alone => fail(s"expected a split, got $alone")
      }
    }

    it("says why a build runs alone") {
      TestShards.summary(TestShards.Alone(420.0, "under 600 usual seconds")) mustBe
        Vector("ci-shard-plan: alone 420 usual seconds, under 600 usual seconds")
    }
  }

  describe("median") {
    it("is the middle value, or the mean of the two middle values") {
      TestShards.median(Map.empty) mustBe None
      TestShards.median(Map("a" -> 3.0, "b" -> 1.0, "c" -> 2.0)) mustBe Some(2.0)
      TestShards.median(Map("a" -> 4.0, "b" -> 1.0)) mustBe Some(2.5)
    }

    it("leaves the start lines out, so an unlisted suite never weighs a start") {
      TestShards.median(Map("a" -> 1.0, "b" -> 3.0, "start:a" -> 100.0)) mustBe Some(2.0)
      TestShards.median(Map("start:a" -> 100.0)) mustBe None
    }
  }

  describe("lane") {
    it("names the shard's database after its subproject and its index") {
      TestShards.lane("integrations", 3) mustBe "integrations__3"
    }
  }
}
