import java.nio.file.{Path, Paths}
import java.util.Optional

import sbt.internal.inc.caching.ClasspathCache
import xsbti.{FileConverter, VirtualFile, VirtualFileRef}
import xsbti.compile.{Changes, CompileAnalysis, ExternalHooks, FileHash}

/** Zinc's classpath hash with every entry inside the checkout named relative to its root (ISS-44010).
  *
  * Zinc stores the hash of the compile classpath in a subproject's analysis, path and content per entry, and reads a
  * different one as a changed classpath. A shard pulls the analysis its build's Prepare compiled in another checkout,
  * so every sibling subproject's `target/.../classes` names another directory and the hash always differs. On a
  * changed classpath zinc looks every class a library is recorded as providing up on the classpath, and Scala 3
  * records the anonymous classes play-json's `Json.reads`, `Json.writes` and `Json.format` macros expand into (an
  * `Outer$_$$anon`) as play-json's, a class no classpath holds: play-json is read as changed, and every source of the
  * subproject that uses it compiles again. Measured on a platform shard, about 320 sources across core, assistant,
  * family, integrations and idempotent before its first suite, in every shard of every build.
  *
  * Named from the root, the entries are the same in every checkout of one commit, so the hash compares equal and zinc
  * takes its unchanged-classpath path, which reads a library as changed only when its own stamp moved. An entry
  * outside the checkout (a library jar, the JDK) keeps its path, which is what it was.
  */
object PathIndependentClasspath {

  /** The name every path under the checkout root is given in place of the root. */
  val RootName: Path = Paths.get("<checkout>")

  /** `file` named relative to `root` when it lies under it, else unchanged. */
  def relativeTo(root: Path, file: Path): Path =
    if (file.startsWith(root)) RootName.resolve(root.relativize(file)) else file

  def hash(root: Path, hashes: Array[FileHash]): Array[FileHash] =
    hashes.map(h => FileHash.of(relativeTo(root, h.file), h.hash))

  /** `hooks` whose classpath hash names entries under `root` relative to it: the lookup sbt installed when there is
    * one, every other answer left to it.
    */
  def hooks(hooks: ExternalHooks, root: Path, converter: FileConverter): ExternalHooks = {
    val inner: Option[ExternalHooks.Lookup] =
      if (hooks.getExternalLookup.isPresent) Some(hooks.getExternalLookup.get) else None
    val lookup = new ExternalHooks.Lookup {
      override def getChangedSources(previous: CompileAnalysis): Optional[Changes[VirtualFileRef]] =
        inner.fold(Optional.empty[Changes[VirtualFileRef]])(_.getChangedSources(previous))
      override def getChangedBinaries(previous: CompileAnalysis): Optional[java.util.Set[VirtualFileRef]] =
        inner.fold(Optional.empty[java.util.Set[VirtualFileRef]])(_.getChangedBinaries(previous))
      override def getRemovedProducts(previous: CompileAnalysis): Optional[java.util.Set[VirtualFileRef]] =
        inner.fold(Optional.empty[java.util.Set[VirtualFileRef]])(_.getRemovedProducts(previous))
      override def shouldDoIncrementalCompilation(changed: java.util.Set[String], previous: CompileAnalysis): Boolean =
        inner.forall(_.shouldDoIncrementalCompilation(changed, previous))
      override def hashClasspath(classpath: Array[VirtualFile]): Optional[Array[FileHash]] = {
        val own = inner.map(_.hashClasspath(classpath)).filter(_.isPresent).map(_.get)
        val hashes = own.getOrElse(ClasspathCache.hashClasspath(classpath.toSeq.map(converter.toPath)))
        Optional.of(hash(root, hashes))
      }
    }
    hooks.withExternalLookup(lookup)
  }
}
