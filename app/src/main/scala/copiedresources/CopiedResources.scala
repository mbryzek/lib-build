package copiedresources

import java.io.File
import java.nio.file.{Files, StandardCopyOption}
import java.util.Arrays

/** Makes every copied resource match the checkout's file byte for byte (ISS-34899).
  *
  * sbt's `copyResources` copies a resource only when its record of the source's stamp says it
  * changed. `pullRemoteCache` overwrites the copied resources with the entry's, and an entry here is
  * an ANCESTOR commit's, while the record stays one that calls them current: the checkout's own,
  * from an earlier run over the same sources. A resource the branch changed is then the ancestor's
  * copy until its source is touched, and a spec reads a fixture the checkout no longer has.
  *
  * So after sbt's own sync, every mapped source file whose target differs in content is copied
  * again. Resources are small (a megabyte across the build), so comparing every one costs less than
  * a single stale fixture failing a build.
  *
  * No sbt type appears here on purpose. This file is compiled twice: by the meta-build, where
  * [[CompileCachePlugin]] runs it after every `copyResources`, and by `testkit` as a test source,
  * where `CopiedResourcesSpec` asserts it. It therefore stays inside the syntax Scala 2.12 and
  * Scala 3 share.
  */
object CopiedResources {

  /** Copies each `(source, target)` whose source is a file and whose target is missing or differs
    * from it, and answers the targets it copied.
    */
  def refresh(mappings: Seq[(File, File)]): Seq[File] =
    mappings.collect {
      case (source, target) if source.isFile && !sameContent(source, target) =>
        Option(target.getParentFile).foreach(dir => Files.createDirectories(dir.toPath))
        Files.copy(source.toPath, target.toPath, StandardCopyOption.REPLACE_EXISTING)
        target
    }

  private def sameContent(a: File, b: File): Boolean =
    b.isFile && a.length == b.length && Arrays.equals(Files.readAllBytes(a.toPath), Files.readAllBytes(b.toPath))
}
