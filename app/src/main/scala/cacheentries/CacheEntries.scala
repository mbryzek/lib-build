package cacheentries

import java.io.{File, IOException}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest
import java.util.concurrent.{Callable, ExecutionException, Executors}
import java.util.zip.ZipFile

/** One subproject configuration's compile-cache entry, read straight off the cache directory (ISS-44012).
  *
  * sbt's own `pullRemoteCache` resolves every entry through ivy and unzips it inside ivy's lock, so a
  * build's pulls run one after another however many cores the box has: 56 entries, 28 seconds, in
  * every Prepare, every shard and every warm build. The cache is a directory in Maven layout that
  * `pushRemoteCache` wrote, so an entry's jar has one path, `<organization as a path>/<name>/<version>/
  * <name>-<version>-<classifier>.jar`, and needs no resolver to find. [[CompileCachePlugin]] reads it
  * here, and sbt runs each configuration's pull as a task of its own, in parallel. The push is the same
  * path written instead of read ([[publish]], ISS-74569): sbt's `pushRemoteCache` publishes each
  * configuration's jar through ivy inside the same lock, one after another, where a copy needs none.
  *
  * The extraction is sbt's `RemoteCache.extractCache`, step for step: the output directory replaced by
  * the jar's contents with each entry's modification time (zinc stamps products by it), the analysis
  * moved from `META-INF/inc_compile.zip` to the configuration's analysis file, a compile entry's
  * `META-INF/copy-resources.txt` moved to the record of the copied resources, and the rest of
  * `META-INF` removed apart from `semanticdb`.
  *
  * No sbt type appears here on purpose: like `CopiedResources.scala`, this file is compiled by the
  * meta-build and by `testkit` as a test source, where `CacheEntriesSpec` asserts it, so it stays
  * inside the syntax Scala 2.12 and Scala 3 share.
  */
object CacheEntries {

  val AnalysisEntry = "META-INF/inc_compile.zip"
  val CopyResourcesEntry = "META-INF/copy-resources.txt"

  /** sbt's version for a remote cache id. */
  def version(id: String): String = s"0.0.0-$id"

  /** The jar of the first id in `ids` whose entry the cache holds, with that id. */
  def find(
    cache: File,
    organization: String,
    name: String,
    classifier: String,
    ids: Seq[String],
  ): Option[(String, File)] =
    ids.iterator.map(id => id -> jar(cache, organization, name, classifier, id)).find(_._2.isFile)

  def jar(cache: File, organization: String, name: String, classifier: String, id: String): File = {
    val v = version(id)
    val dir = organization.split('.').foldLeft(cache)((d, part) => new File(d, part))
    new File(new File(new File(dir, name), v), s"$name-$v-$classifier.jar")
  }

  /** Writes `packaged` as the entry `id` of `cache` (ISS-74569): the jar at the path [[jar]] names, with
    * the `.md5` and `.sha1` beside it that sbt's ivy publish writes and an ivy pull checks. Nothing else:
    * a remote cache entry publishes no pom. Each file is written under a temporary name in its own
    * directory and moved into place, the jar last, so a reader never sees half of one; an entry already
    * there is replaced, where ivy refused a release version it held. Answers the jar's path.
    */
  def publish(packaged: File, cache: File, organization: String, name: String, classifier: String, id: String): File = {
    val target = jar(cache, organization, name, classifier, id)
    val dir = target.getParentFile.toPath
    Files.createDirectories(dir)
    def place(suffix: String)(write: Path => Unit): Unit = {
      val tmp = Files.createTempFile(dir, s".${target.getName}$suffix.", ".tmp")
      try {
        write(tmp)
        val _ = Files.move(
          tmp,
          dir.resolve(target.getName + suffix),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE
        )
      } finally { val _ = Files.deleteIfExists(tmp) }
    }
    val (md5, sha1) = checksums(packaged)
    place(".md5")(p => { val _ = Files.write(p, md5.getBytes(StandardCharsets.US_ASCII)) })
    place(".sha1")(p => { val _ = Files.write(p, sha1.getBytes(StandardCharsets.US_ASCII)) })
    place("")(p => { val _ = Files.copy(packaged.toPath, p, StandardCopyOption.REPLACE_EXISTING) })
    target
  }

  /** The file's md5 and sha1 as lower-case hex, the way ivy writes a checksum file: the digest alone. */
  def checksums(file: File): (String, String) = {
    val md5 = MessageDigest.getInstance("MD5")
    val sha1 = MessageDigest.getInstance("SHA-1")
    val in = Files.newInputStream(file.toPath)
    try {
      val buffer = new Array[Byte](1 << 16)
      var read = in.read(buffer)
      while (read >= 0) {
        md5.update(buffer, 0, read)
        sha1.update(buffer, 0, read)
        read = in.read(buffer)
      }
    } finally in.close()
    def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString
    (hex(md5.digest), hex(sha1.digest))
  }

  /** Replaces `output` with the contents of `jar`, as sbt's `extractCache` does, moving the analysis to
    * `analysisFile` and, for a compile entry, the copied-resources record to `copyResources`.
    */
  def extract(jar: File, output: File, analysisFile: File, copyResources: Option[File]): Unit = {
    delete(output)
    unzip(jar, output)
    val meta = new File(output, "META-INF")
    moveIfPresent(new File(output, AnalysisEntry), analysisFile)
    copyResources.foreach(target => moveIfPresent(new File(output, CopyResourcesEntry), target))
    Option(meta.listFiles).foreach(_.filterNot(_.getName == "semanticdb").foreach(delete))
  }

  /** Entries one extraction thread takes at a time. A small jar is one batch, so one thread. */
  val Batch = 2000

  /** The jar's entries written under `output`, in batches across up to `threads` threads, each with a
    * `ZipFile` of its own: the generated models' entry alone is 50,000 classes, and one thread writing
    * them was the whole pull's floor.
    */
  private def unzip(jar: File, output: File, threads: Int = Runtime.getRuntime.availableProcessors.min(8)): Unit = {
    val root = output.toPath.toAbsolutePath.normalize
    val names = {
      val zip = new ZipFile(jar)
      try {
        val all = Vector.newBuilder[String]
        val entries = zip.entries
        while (entries.hasMoreElements) all += entries.nextElement.getName
        all.result()
      } finally zip.close()
    }
    val batches = names.grouped(Batch).toVector
    if (batches.size <= 1) batches.foreach(write(jar, root, _))
    else {
      val pool = Executors.newFixedThreadPool(threads.max(1).min(batches.size))
      try {
        val pending =
          batches.map(batch => pool.submit(new Callable[Unit] { def call(): Unit = write(jar, root, batch) }))
        pending.foreach { f =>
          try f.get()
          catch { case e: ExecutionException => throw e.getCause }
        }
      } finally { val _ = pool.shutdownNow() }
    }
  }

  private def write(jar: File, root: Path, names: Seq[String]): Unit = {
    val zip = new ZipFile(jar)
    try
      names.foreach { name =>
        val entry = zip.getEntry(name)
        val target = root.resolve(name).normalize
        if (!target.startsWith(root))
          throw new IOException(s"$jar: entry $name is outside the directory it is extracted to")
        if (entry.isDirectory) Files.createDirectories(target)
        else {
          Files.createDirectories(target.getParent)
          val in = zip.getInputStream(entry)
          try Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING)
          finally in.close()
          if (entry.getTime >= 0) { val _ = target.toFile.setLastModified(entry.getTime) }
        }
      }
    finally zip.close()
  }

  private def moveIfPresent(from: File, to: File): Unit =
    if (from.isFile) {
      Option(to.getParentFile).foreach(dir => Files.createDirectories(dir.toPath))
      val _ = Files.move(from.toPath, to.toPath, StandardCopyOption.REPLACE_EXISTING)
    }

  private def delete(file: File): Unit = {
    if (file.isDirectory && !Files.isSymbolicLink(file.toPath)) Option(file.listFiles).foreach(_.foreach(delete))
    val _ = file.delete()
  }
}
