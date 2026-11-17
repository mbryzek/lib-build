package testkit

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import copiedresources.CopiedResources
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.must.Matchers

/** Which copied resources are copied again. scala-build-app's `CopiedResources.scala` is compiled into this
  * subproject's tests as well as into the meta-build, so what is asserted here is the code
  * `CompileCachePlugin` runs after every `copyResources`.
  */
class CopiedResourcesSpec extends AnyFunSpec with Matchers {

  private def withDirs(test: (File, File) => Unit): Unit = {
    val root = Files.createTempDirectory("copied-resources").toFile
    try test(new File(root, "resources"), new File(root, "test-classes"))
    finally delete(root)
  }

  private def delete(file: File): Unit = {
    Option(file.listFiles).foreach(_.foreach(delete))
    val _ = file.delete()
  }

  private def write(file: File, content: String): File = {
    file.getParentFile.mkdirs()
    Files.write(file.toPath, content.getBytes(StandardCharsets.UTF_8))
    file
  }

  private def read(file: File): String = new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)

  it("copies a target that differs from its source even when the target is newer") {
    withDirs { (resources, classes) =>
      val source = write(new File(resources, "square/catalog-list-page-2.json"), """{"items":["VAR_SHIPPING"]}""")
      val target = write(new File(classes, "square/catalog-list-page-2.json"), """{"items":[]}""")
      source.setLastModified(1000000000000L)
      target.setLastModified(2000000000000L)
      CopiedResources.refresh(Seq(source -> target)) mustBe Seq(target)
      read(target) mustBe """{"items":["VAR_SHIPPING"]}"""
    }
  }

  it("leaves a target with the same content alone") {
    withDirs { (resources, classes) =>
      val source = write(new File(resources, "a.json"), "same")
      val target = write(new File(classes, "a.json"), "same")
      target.setLastModified(1000000000000L)
      CopiedResources.refresh(Seq(source -> target)) mustBe Nil
      target.lastModified mustBe 1000000000000L
    }
  }

  it("copies a missing target, making its directory") {
    withDirs { (resources, classes) =>
      val source = write(new File(resources, "deep/dir/b.txt"), "b")
      val target = new File(classes, "deep/dir/b.txt")
      CopiedResources.refresh(Seq(source -> target)) mustBe Seq(target)
      read(target) mustBe "b"
    }
  }

  it("copies a target of the same length with different bytes") {
    withDirs { (resources, classes) =>
      val source = write(new File(resources, "c.txt"), "abc")
      val target = write(new File(classes, "c.txt"), "abd")
      CopiedResources.refresh(Seq(source -> target)) mustBe Seq(target)
      read(target) mustBe "abc"
    }
  }

  it("skips a directory mapping") {
    withDirs { (resources, classes) =>
      val source = new File(resources, "dir")
      source.mkdirs()
      CopiedResources.refresh(Seq(source -> new File(classes, "dir"))) mustBe Nil
    }
  }
}
