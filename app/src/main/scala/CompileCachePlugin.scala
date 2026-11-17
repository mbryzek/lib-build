import sbt.*
import sbt.Keys.*
import sbt.internal.remotecache.{CompileRemoteCacheArtifact, TestRemoteCacheArtifact}

/** Points sbt's remote cache at the runner's compile cache, `CI_COMPILE_CACHE` (ISS-19093).
  *
  * The runner keeps one directory per repository and exports it to every build. `ci/build.slow.sh` decides
  * which main commits a build may pull from and whether it publishes one, and hands both to sbt in
  * the environment, because that is a fact about git history that sbt has no business computing:
  *
  *   - `CI_COMPILE_CACHE_PULL` -- the main commits `pullRemoteCache` tries, newest first: the
  *     first-parent ancestors of the merge base that have a published entry.
  *   - `CI_COMPILE_CACHE_PUSH` -- the commit `pushRemoteCache` publishes under, set only on a green
  *     build of main, and for a build's Prepare, which publishes its own commit for its shards.
  *   - `CI_COMPILE_CACHE_PUSH_TO` -- a directory `pushRemoteCache` writes into instead of
  *     `CI_COMPILE_CACHE`, which `pullRemoteCache` still reads (ISS-34056). A Prepare compiles from the
  *     runner's cache and publishes into a scratch directory it packs as its shards' archive, in one
  *     sbt start; with one directory for both, the publish needed a start of its own.
  *
  * WHAT sbt computes is the other half of the id, PER SUBPROJECT AND CONFIGURATION: [[cacheKey]], a
  * hash of what the compile reads besides the sources (ISS-21456). An entry is `<key>-<commit>`, so a
  * subproject pulls an ancestor's output only when it was compiled against the same libraries, the
  * same compiler options and the same subproject dependencies. That is the guard the whole-build
  * definition id used to be (ISS-13194: an incremental compile across a library bump invalidates
  * every source while holding the previous analysis, and runs out of heap) -- narrowed to the
  * subproject it is about, so that adding a subproject or changing a test setting in `build.sbt`
  * no longer sends every subproject of every build on every runner to a cold compile until that
  * runner next builds main green.
  *
  * A pull also overwrites the copied resources with the ancestor's, under a record of them sbt calls
  * current, so every `copyResources` ends by making each copied resource match the checkout's file
  * ([[copiedresources.CopiedResources]], ISS-34899).
  *
  * THE PULL READS THE CACHE DIRECTORY ITSELF (ISS-44012). sbt's `pullRemoteCache` resolves each
  * configuration's entry through ivy and unzips it inside ivy's lock, so every pull of a build ran one
  * after another: 28 seconds for 56 entries, paid by every Prepare, every shard and every warm build.
  * With `CI_COMPILE_CACHE` set, each configuration's `pullRemoteCache` finds its jar at the one path
  * the Maven layout gives it and extracts it as sbt does ([[cacheentries.CacheEntries]]), so sbt runs
  * the pulls in parallel. The candidates, the order they are tried in and what is extracted where are
  * sbt's; a candidate with no jar is skipped, and none at all is sbt's `not found` line.
  *
  * THE PUSH WRITES IT THE SAME WAY (ISS-74569). sbt's `pushRemoteCache` publishes each configuration's
  * jar through ivy under the same lock, so a Prepare's 56 publishes ran one after another. With a push
  * directory set, each configuration's `pushRemoteCache` copies its `packageCache` jar to the path the
  * Maven layout names, with the `.md5` and `.sha1` ivy writes, so the publishes run in parallel and an
  * older tree still pulls them through ivy. The jar and the id are sbt's.
  *
  * An unset variable leaves sbt's own defaults in place: no resolver, no remote cache, today's
  * compile. The id is not sbt's content hash of the sources, because that hash is computed from the
  * tree being built and so can never name an ANCESTOR's entry.
  *
  * Both ids are set in `Compile` and `Test`, which is where sbt scopes `remoteCacheId` and
  * `remoteCacheIdCandidates`: a `ThisBuild` value would be shadowed by sbt's own per-configuration
  * default. An AutoPlugin for the reason [[NoScaladocPlugin]] is one: a subproject that forgot to
  * opt in would compile cold forever without anything failing.
  */
object CompileCachePlugin extends AutoPlugin {

  override def trigger = allRequirements

  override def requires = plugins.JvmPlugin

  private def env(name: String): Option[String] = sys.env.get(name).map(_.trim).filter(_.nonEmpty)

  /** The commits a variable names, in order. */
  private def commits(name: String): Seq[String] =
    env(name).toSeq.flatMap(_.split("\\s+").toSeq).filter(_.nonEmpty)

  /** What a subproject's compiled output in `config` depends on besides its sources, as one hash.
    *
    *   - the libraries, as `organization:name:revision` in classpath order -- a bump is the case
    *     ISS-13194 measured running out of heap;
    *   - the subprojects it depends on, with their configuration mapping -- moving code into a new
    *     subproject changes what this one's sources compile against;
    *   - the Scala version, the compile order and the scalac and javac options, each of which zinc
    *     answers with a full recompile.
    *
    * An option that names a path inside the checkout is read relative to it: every verify build has
    * a fresh tree of its own, so an absolute path would make every key unique. Read from `update`,
    * never from a classpath of products, which would compile the dependencies before the pull.
    */
  def cacheKey(config: Configuration): Def.Initialize[Task[String]] = Def.task {
    val root = (LocalRootProject / baseDirectory).value.getAbsolutePath
    val libraries = (config / externalDependencyClasspath).value.map { entry =>
      entry.get(moduleID.key).map(m => s"${m.organization}:${m.name}:${m.revision}").getOrElse(entry.data.getName)
    }
    val projects = thisProject.value.dependencies.map(d => s"${d.project.project}->${d.configuration.getOrElse("")}")
    val options = ((config / scalacOptions).value ++ (config / javacOptions).value).map(_.replace(root, "<root>"))
    val inputs =
      Seq(s"scala ${scalaVersion.value}", s"order ${(config / compileOrder).value}") ++
        libraries.map("library " + _) ++ projects.map("project " + _) ++ options.map("option " + _)
    Hash.toHex(Hash(inputs.mkString("\n"))).take(16)
  }

  /** The runner's cache, which every pull reads. */
  private def cache: Option[Resolver] = env("CI_COMPILE_CACHE").map(dir => MavenCache("ci-compile-cache", file(dir)))

  /** Where `pushRemoteCache` writes: `CI_COMPILE_CACHE_PUSH_TO` when set, under a resolver name of its
    * own (sbt finds the push and the pull resolvers by name in one ivy settings), else the cache.
    */
  private def pushTo: Option[Resolver] =
    env("CI_COMPILE_CACHE_PUSH_TO").map(dir => MavenCache("ci-compile-cache-push", file(dir))).orElse(cache)

  /** The directory `pushRemoteCache` writes into: the one [[pushTo]] names. */
  private def pushDir: Option[String] = env("CI_COMPILE_CACHE_PUSH_TO").orElse(env("CI_COMPILE_CACHE"))

  override def buildSettings: Seq[Setting[?]] =
    pushTo.toSeq.map(to => pushRemoteCacheTo := Some(to))

  /** sbt's own `remoteCacheResolvers` is the push target, set per project, so a separate pull source is
    * set per project too.
    */
  private def pullFrom: Seq[Setting[?]] =
    env("CI_COMPILE_CACHE_PUSH_TO").toSeq.map(_ => remoteCacheResolvers := cache.toVector)

  /** sbt's `copyResources`, then every copied resource whose content differs from the checkout's copied
    * again. Unconditional: a stale record pulled once stays in the checkout for every later run.
    */
  private def refreshCopiedResources: Seq[Setting[?]] =
    Seq(Compile, Test).map { config =>
      config / copyResources := {
        val mappings = (config / copyResources).value
        val log = streams.value.log
        val refreshed = copiedresources.CopiedResources.refresh(mappings)
        if (refreshed.nonEmpty)
          log.info(s"compile cache: recopied stale resources ${refreshed.map(_.getName).mkString(", ")}")
        mappings
      }
    }

  /** Zinc's classpath hash named from the checkout root, so an analysis pulled into another checkout of the same
    * commit reads its classpath as unchanged ([[PathIndependentClasspath]], ISS-44010).
    */
  private def pathIndependentClasspath: Seq[Setting[?]] =
    Seq(Compile, Test).map { config =>
      config / SbtExternalHooks.key := PathIndependentClasspath.hooks(
        (config / SbtExternalHooks.key).value,
        (LocalRootProject / baseDirectory).value.toPath.toAbsolutePath,
        fileConverter.value,
      )
    }

  /** A configuration's entry as the Maven layout names it: the organization, the cross-versioned name
    * and the classifier, which is where sbt's ivy publish and pull put and find it.
    */
  private case class Entry(organization: String, name: String, classifier: String)

  private def entry(config: Configuration): Def.Initialize[Task[Entry]] = Def.task {
    val project = (config / remoteCacheProjectId).value
    val name = CrossVersion(project.crossVersion, scalaVersion.value, scalaBinaryVersion.value)
      .fold(project.name)(cross => cross(project.name))
    val classifier = (config / packageCache / artifact).value.classifier
      .getOrElse(sys.error(s"compile cache: the ${config.name} cache artifact of ${project.name} has no classifier"))
    Entry(project.organization, name, classifier)
  }

  /** Each configuration's pull, from the cache directory and outside ivy (ISS-44012). */
  private def directPull: Seq[Setting[?]] =
    env("CI_COMPILE_CACHE").toSeq.flatMap { dir =>
      Seq(Compile, Test).map { config =>
        config / pullRemoteCache := {
          val log = streams.value.log
          val project = (config / remoteCacheProjectId).value
          val e = entry(config).value
          val copyResources = crossTarget.value / ((if (config == Compile) "" else s"${config.name}-") + "sync") /
            "copy-resource"
          val (output, analysis, resources) = (config / packageCache / remoteCacheArtifact).value match {
            case a: CompileRemoteCacheArtifact => (a.extractDirectory, a.analysisFile, Some(copyResources))
            case a: TestRemoteCacheArtifact => (a.extractDirectory, a.analysisFile, None)
            case other => sys.error(s"compile cache: no pull for the remote cache artifact $other")
          }
          val ids = (config / remoteCacheIdCandidates).value
          cacheentries.CacheEntries.find(file(dir), e.organization, e.name, e.classifier, ids) match {
            case Some((id, jar)) =>
              cacheentries.CacheEntries.extract(jar, output, analysis, resources)
              log.info(
                s"remote cache artifact extracted for ${project.organization}:${project.name}:" +
                  s"${cacheentries.CacheEntries.version(id)} Some(${e.classifier})"
              )
            case None =>
              log.info(
                s"remote cache artifact not found for ${project.organization}:${project.name} Some(${e.classifier})"
              )
          }
        }
      }
    }

  /** Each configuration's push, into the push directory and outside ivy (ISS-74569): sbt's own jar,
    * `packageCache`, copied to the path its ivy publish would write, with the checksums beside it.
    */
  private def directPush: Seq[Setting[?]] =
    pushDir.toSeq.flatMap { dir =>
      Seq(Compile, Test).map { config =>
        config / pushRemoteCache := {
          val log = streams.value.log
          val e = entry(config).value
          val id = (config / remoteCacheId).value
          val jar = cacheentries.CacheEntries.publish(
            (config / packageCache).value,
            file(dir),
            e.organization,
            e.name,
            e.classifier,
            id
          )
          log.info(s"compile cache: published ${e.name} ${e.classifier} to $jar")
        }
      }
    }

  override def projectSettings: Seq[Setting[?]] =
    pullFrom ++ directPull ++ directPush ++ refreshCopiedResources ++ pathIndependentClasspath ++ (for {
      config <- Seq(Compile, Test)
      setting <- env("CI_COMPILE_CACHE_PUSH").toSeq.map { commit =>
        config / remoteCacheId := s"${cacheKey(config).value}-$commit"
      } ++ Some(commits("CI_COMPILE_CACHE_PULL")).filter(_.nonEmpty).toSeq.map { pull =>
        config / remoteCacheIdCandidates := {
          val key = cacheKey(config).value
          pull.map(commit => s"$key-$commit")
        }
      }
    } yield setting)
}
