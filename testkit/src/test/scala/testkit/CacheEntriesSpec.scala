package testkit

import java.io.{File, FileOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.{ZipEntry, ZipOutputStream}

import cacheentries.CacheEntries
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.must.Matchers

/** How a compile-cache entry is found and extracted. scala-build-app's `CacheEntries.scala` is compiled into this
  * subproject's tests as well as into the meta-build, so what is asserted here is the code
  * `CompileCachePlugin`'s `pullRemoteCache` runs.
  */
class CacheEntriesSpec extends AnyFunSpec with Matchers {

  private val modified = 1700000000000L

  private def withDir(test: File => Unit): Unit = {
    val root = Files.createTempDirectory("cache-entries").toFile
    try test(root)
    finally delete(root)
  }

  private def delete(file: File): Unit = {
    Option(file.listFiles).foreach(_.foreach(delete))
    val _ = file.delete()
  }

  private def read(file: File): String = new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)

  private def write(file: File, content: String): File = {
    file.getParentFile.mkdirs()
    Files.write(file.toPath, content.getBytes(StandardCharsets.UTF_8))
    file
  }

  private def jar(file: File, entries: (String, String)*): File = {
    file.getParentFile.mkdirs()
    val out = new ZipOutputStream(new FileOutputStream(file))
    try
      entries.foreach { case (name, content) =>
        val entry = new ZipEntry(name)
        entry.setTime(modified)
        out.putNextEntry(entry)
        out.write(content.getBytes(StandardCharsets.UTF_8))
        out.closeEntry()
      }
    finally out.close()
    file
  }

  describe("find") {
    it("answers the first candidate whose jar the cache holds, at the Maven path pushRemoteCache writes") {
      withDir { cache =>
        val expected =
          new File(cache, "com/bryzek/platform_3/0.0.0-key-bbb/platform_3-0.0.0-key-bbb-cached-compile.jar")
        jar(expected, "a.txt" -> "a")
        CacheEntries.find(
          cache,
          "com.bryzek",
          "platform_3",
          "cached-compile",
          Seq("key-aaa", "key-bbb", "key-ccc")
        ) mustBe
          Some("key-bbb" -> expected)
      }
    }

    it("answers nothing when no candidate has a jar of that classifier") {
      withDir { cache =>
        jar(new File(cache, "core/core_3/0.0.0-key-aaa/core_3-0.0.0-key-aaa-cached-test.jar"), "a.txt" -> "a")
        CacheEntries.find(cache, "core", "core_3", "cached-compile", Seq("key-aaa")) mustBe None
      }
    }
  }

  describe("publish") {
    it("writes the jar at the path find reads, with the md5 and sha1 ivy writes beside it") {
      withDir { root =>
        val cache = new File(root, "cache")
        val packaged = jar(new File(root, "core_3-cached-compile.jar"), "a/B.class" -> "bytes")
        val written = CacheEntries.publish(packaged, cache, "core", "core_3", "cached-compile", "key-aaa")
        CacheEntries.find(cache, "core", "core_3", "cached-compile", Seq("key-aaa")) mustBe Some("key-aaa" -> written)
        written.getPath mustBe new File(
          cache,
          "core/core_3/0.0.0-key-aaa/core_3-0.0.0-key-aaa-cached-compile.jar"
        ).getPath
        Files.readAllBytes(written.toPath).toSeq mustBe Files.readAllBytes(packaged.toPath).toSeq
        val (md5, sha1) = CacheEntries.checksums(packaged)
        read(new File(written.getPath + ".md5")) mustBe md5
        read(new File(written.getPath + ".sha1")) mustBe sha1
        written.getParentFile.list.toSeq.sorted mustBe Seq(
          written.getName,
          written.getName + ".md5",
          written.getName + ".sha1"
        )
      }
    }

    it("answers the digests as lower-case hex, the digest alone") {
      withDir { root =>
        val file = write(new File(root, "abc.txt"), "abc")
        CacheEntries.checksums(file) mustBe (
          "900150983cd24fb0d6963f7d28e17f72",
          "a9993e364706816aba3e25717850c26c9cd0d89d"
        )
      }
    }

    it("replaces an entry already published under the id") {
      withDir { root =>
        val cache = new File(root, "cache")
        val first = jar(new File(root, "first.jar"), "a.txt" -> "first")
        val second = jar(new File(root, "second.jar"), "a.txt" -> "second")
        CacheEntries.publish(first, cache, "core", "core_3", "cached-test", "key-aaa")
        val written = CacheEntries.publish(second, cache, "core", "core_3", "cached-test", "key-aaa")
        Files.readAllBytes(written.toPath).toSeq mustBe Files.readAllBytes(second.toPath).toSeq
        read(new File(written.getPath + ".sha1")) mustBe CacheEntries.checksums(second)._2
      }
    }
  }

  describe("extract") {
    it("replaces the output with the jar's classes, keeping each entry's modification time") {
      withDir { root =>
        val classes = new File(root, "classes")
        write(new File(classes, "stale/Old.class"), "old")
        val entry = jar(new File(root, "entry.jar"), "a/B.class" -> "bytes", "reference.conf" -> "x = 1")
        CacheEntries.extract(entry, classes, new File(root, "zinc/inc_compile_3.zip"), None)
        read(new File(classes, "a/B.class")) mustBe "bytes"
        read(new File(classes, "reference.conf")) mustBe "x = 1"
        new File(classes, "a/B.class").lastModified mustBe modified
        new File(classes, "stale").exists mustBe false
      }
    }

    it("moves the analysis and the copied-resources record out, and keeps only semanticdb under META-INF") {
      withDir { root =>
        val classes = new File(root, "classes")
        val analysis = new File(root, "zinc/inc_compile_3.zip")
        val copyResources = new File(root, "sync/copy-resource")
        val entry = jar(
          new File(root, "entry.jar"),
          "a/B.class" -> "bytes",
          CacheEntries.AnalysisEntry -> "analysis",
          CacheEntries.CopyResourcesEntry -> "resources",
          "META-INF/MANIFEST.MF" -> "Manifest-Version: 1.0",
          "META-INF/semanticdb/a/B.scala.semanticdb" -> "db",
        )
        CacheEntries.extract(entry, classes, analysis, Some(copyResources))
        read(analysis) mustBe "analysis"
        read(copyResources) mustBe "resources"
        new File(classes, "META-INF").list.toSeq mustBe Seq("semanticdb")
        read(new File(classes, "META-INF/semanticdb/a/B.scala.semanticdb")) mustBe "db"
      }
    }

    it("leaves the copied-resources record in place for a test entry, which names none") {
      withDir { root =>
        val classes = new File(root, "test-classes")
        val entry = jar(new File(root, "entry.jar"), CacheEntries.CopyResourcesEntry -> "resources")
        CacheEntries.extract(entry, classes, new File(root, "zinc/test.zip"), None)
        new File(classes, "META-INF").list.toSeq mustBe Nil
      }
    }

    it("writes every entry of a jar larger than one batch, across threads") {
      withDir { root =>
        val classes = new File(root, "classes")
        val count = CacheEntries.Batch * 2 + 7
        val entry = jar(new File(root, "entry.jar"), (0 until count).map(i => s"p${i % 13}/C$i.class" -> s"c$i")*)
        CacheEntries.extract(entry, classes, new File(root, "zinc/inc_compile_3.zip"), None)
        (0 until count).forall(i => read(new File(classes, s"p${i % 13}/C$i.class")) == s"c$i") mustBe true
        new File(classes, s"p0/C0.class").lastModified mustBe modified
      }
    }

    it("refuses an entry that would be written outside the output") {
      withDir { root =>
        val entry = jar(new File(root, "entry.jar"), "../escaped.txt" -> "x")
        an[java.io.IOException] must be thrownBy CacheEntries.extract(
          entry,
          new File(root, "classes"),
          new File(root, "a.zip"),
          None
        )
        new File(root, "escaped.txt").exists mustBe false
      }
    }
  }
}
