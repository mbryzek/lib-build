package testkit

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.must.Matchers

class PartStartSpec extends AnyFunSpec with Matchers {

  describe("line") {
    it("names the lane's subproject and its seconds to the millisecond") {
      PartStart.line("integrations__3", 41.2071) mustBe "integrations 41.207"
      PartStart.line("api", 2.0) mustBe "api 2.000"
    }

    it("keeps a subproject whose own name holds two underscores") {
      PartStart.subproject("a__b__0") mustBe "a__b"
    }
  }

  describe("record") {
    it("writes the seconds from launch to now into the file it is handed, its directory made") {
      val dir = Files.createTempDirectory("part-start")
      val file = dir.resolve("test-reports").resolve("PART-START-core__1.txt")
      PartStart.record(
        Map(PartStart.FileEnvVar -> file.toString, PartStart.ShardEnvVar -> "core__1"),
        launchedAtMillis = 1000L,
        nowMillis = 13500L
      )
      new String(Files.readAllBytes(file), StandardCharsets.UTF_8) mustBe "core 12.500\n"
    }

    it("writes nothing outside a forked shard") {
      val dir = Files.createTempDirectory("part-start")
      val file = dir.resolve("PART-START-core__1.txt")
      PartStart.record(Map(PartStart.FileEnvVar -> file.toString), 0L, 1000L)
      PartStart.record(Map(PartStart.ShardEnvVar -> "core__1"), 0L, 1000L)
      Files.exists(file) mustBe false
    }

    it("never fails the run when the file cannot be written") {
      val dir = Files.createTempDirectory("part-start")
      val blocker = dir.resolve("not-a-dir")
      Files.write(blocker, Array[Byte](1))
      PartStart.record(
        Map(PartStart.FileEnvVar -> blocker.resolve("x.txt").toString, PartStart.ShardEnvVar -> "core__1"),
        0L,
        1000L
      )
    }
  }
}
