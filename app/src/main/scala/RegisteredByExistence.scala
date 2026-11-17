import sbt.*
import sbt.Keys.*

import scala.util.matching.Regex

/** A registry the build writes from the classes (or objects) that declare its marker trait, so adding a member is
  * writing its class and removing one is deleting it (ISS-21839, ISS-24350, ISS-25155).
  *
  * A hand-kept list is a shared file every addition appends to, so two additions in flight always conflict on it
  * and a merge that resolves the conflict can drop one silently. A generated list has no such file: each member is
  * the one file that declares it, and the build reads the set back out of the sources on every compile.
  *
  * A class is registered when its header names the marker directly after `extends` or `with`, optionally qualified
  * by the marker's package. The scan reads source text rather than compiled classes because the list has to exist
  * before the subproject compiles. Comments are stripped first, so prose naming a class registers nothing, and an
  * `abstract` class is skipped. A registry of [[Objects]] reads `object` headers the same way, for members that hold
  * declarations rather than need injecting. What it cannot see is a class extending an intermediate base class; such a class is
  * not registered, which is why a member names the marker itself.
  *
  * Registered classes are handed to [[Registry.render]] sorted by fully-qualified name, so the generated order is
  * stable and independent of which file the build happened to read first.
  */
object RegisteredByExistence {

  /** Which definitions a registry's members are. */
  sealed trait MemberKind { def keyword: String }

  /** Concrete classes, which a generated registry injects. */
  case object Classes extends MemberKind { val keyword = "class" }

  /** Objects, which a generated registry references directly. */
  case object Objects extends MemberKind { val keyword = "object" }

  /** One generated registry.
    *
    * @param marker
    *   the fully-qualified name of the trait a member extends
    * @param file
    *   the generated source, relative to `Compile / sourceManaged`
    * @param render
    *   the generated source for the registered classes, given sorted and distinct
    * @param members
    *   whether members are classes or objects
    */
  final case class Registry(
    marker: String,
    file: String,
    render: Seq[String] => String,
    members: MemberKind = Classes,
  ) {

    private val (markerPackage, markerName) = marker.lastIndexOf('.') match {
      case -1 => ("", marker)
      case i => (marker.substring(0, i), marker.substring(i + 1))
    }

    private val qualifier: String =
      if (markerPackage.isEmpty) "" else s"(?:${Regex.quote(markerPackage + ".")})?"

    /** A class header up to the first brace, naming the marker as a parent, without crossing into the next
      * definition.
      */
    private val MemberClass: Regex =
      ("""\b""" + members.keyword + """\s+([A-Z]\w*)((?:(?!\b(?:class|object|trait)\b)[^{}])*?)\b(?:extends|with)\s+(?:_root_\.)?""" +
        qualifier + Regex.quote(markerName) + """\b(?![\w.])""").r

    /** The fully-qualified name of every concrete member declared in `source`. A source that never spells the
      * marker's simple name cannot match [[MemberClass]], so it is answered without stripping its comments or running
      * the header regex, which is nearly every source a scan reads.
      */
    def discover(source: String): Seq[String] = if (!source.contains(markerName)) Nil
    else {
      val code = stripComments(source)
      val pkg = Package.findAllMatchIn(code).map(_.group(1)).mkString(".")
      MemberClass
        .findAllMatchIn(code)
        .filterNot(m => code.substring(0, m.start).trim.endsWith("abstract"))
        .map(m => if (pkg.isEmpty) m.group(1) else s"$pkg.${m.group(1)}")
        .toList
    }
  }

  private val BlockComment: Regex = """(?s)/\*.*?\*/""".r
  private val LineComment: Regex = """(?m)//.*$""".r
  private val Package: Regex = """(?m)^\s*package\s+([\w.]+)""".r

  private def stripComments(source: String): String =
    LineComment.replaceAllIn(BlockComment.replaceAllIn(source, " "), "")

  /** Bumped whenever [[Registry.discover]] changes what it finds in a source, so a cached scan from an older build
    * definition is never reused.
    */
  private val ScanVersion: Int = 1

  /** What one scan of a source found: its stamp (modified time and length) and, for each registry in order, its
    * members.
    */
  private type Scanned = (Long, Long, Seq[Seq[String]])

  /** The source generator for `registries`: scans every `Compile` source directory of the subproject (and, with
    * `includeDependencies`, of the subprojects it depends on) and rewrites each generated file only when its content
    * changes, so an unchanged list costs no recompile.
    *
    * A source whose stamp is unchanged since the last scan is answered from that scan without being read, so an
    * unchanged tree costs a directory walk and a stat per source rather than reading and matching every one of them
    * (`api` scans every subproject it depends on, thousands of sources, on every command that reaches
    * `managedSources`; ISS-74655). The scans are kept in this task's cache store under a name derived from the
    * registries and [[ScanVersion]]; a missing or unreadable store rescans every source.
    */
  def generator(includeDependencies: Boolean, registries: Registry*): Def.Initialize[Task[Seq[File]]] = {
    val projects = if (includeDependencies) inDependencies(ThisProject) else inProjects(ThisProject)
    val filter = ScopeFilter(projects, inConfigurations(Compile))
    val cacheName = s"registered-by-existence-v$ScanVersion-" +
      Hash.toHex(Hash(registries.map(r => s"${r.members.keyword} ${r.marker}").mkString("\n"))).take(16)
    Def.task {
      import sjsonnew.BasicJsonProtocol.*
      val dirs = unmanagedSourceDirectories.all(filter).value.flatten.distinct
      val files = dirs.flatMap(dir => (dir ** "*.scala").get).distinct
      val root = (Compile / sourceManaged).value
      val s = streams.value
      val log = s.log
      val store = s.cacheStoreFactory.make(cacheName)
      val previous: Map[String, Scanned] =
        try store.read[Map[String, Scanned]](Map.empty[String, Scanned])
        catch { case _: Exception => Map.empty }
      var rescanned = 0
      val scans: Seq[(String, Scanned)] = files.map { file =>
        val path = file.getAbsolutePath
        val modified = IO.getModifiedTimeOrZero(file)
        val length = file.length
        previous.get(path) match {
          case Some(hit @ (m, l, _)) if m == modified && l == length => path -> hit
          case _ =>
            rescanned += 1
            val source = IO.read(file)
            path -> ((modified, length, registries.map(_.discover(source))))
        }
      }
      val current = scans.toMap
      if (current != previous) store.write(current)
      log.debug(s"$cacheName: rescanned $rescanned of ${files.size} sources")
      registries.zipWithIndex.map { case (registry, i) =>
        val classes = scans.flatMap(_._2._3(i)).distinct.sorted
        val out = root / registry.file
        val content = registry.render(classes)
        if (!out.exists || IO.read(out) != content) IO.write(out, content)
        log.debug(s"${registry.marker}: ${classes.size} registered from ${files.size} sources")
        out
      }
    }
  }
}
