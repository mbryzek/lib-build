package testimpact

import sbt.*
import sbt.Keys.*
import sbt.plugins.JvmPlugin

import scala.util.control.NonFatal

/** Runs only the specs a change can reach, by class, when `CI_TEST_IMPACT` names the changed paths.
  *
  * [[TestImpact]] decides; this is what hands it zinc's analysis and applies its answer:
  *
  *   - `ciImpactGraph`, per subproject and configuration: the classes each compiled source defines
  *     and the classes each class depends on, read off that configuration's `compile` analysis;
  *   - `ciImpactNames`, likewise: every dotted name in the configuration's resource files, and its
  *     routes files read ([[TestImpact.routesFile]]);
  *   - `ciImpactSources`, likewise: the classes of every source booting an application, what every
  *     Guice module source binds ([[TestImpact.bindingsIn]]), and, in a test configuration, what each
  *     source requests of a router ([[TestImpact.requestsIn]]);
  *   - `ciImpactInputs`, per subproject: both of those for every configuration on its test classpath,
  *     followed through each `dependsOn` mapping, with its specs and directories;
  *   - a `Tests.Filter` on every subproject keeping the specs [[TestImpact.select]] answers, and a
  *     `ci-impact:` line in the log saying how many and why. With the variable unset there is no
  *     filter and nothing above is evaluated;
  *   - `ciImpactPlan <file>`, which answers for every subproject and every change set in a file
  *     without running a test: the measurement `scripts/measure-test-impact` drives.
  *
  * An AutoPlugin on every project for the reason `CiTestListsPlugin` is one: a subproject added next
  * year must not be able to run unfiltered while its neighbours are filtered, or filtered by a graph
  * that left its classes out.
  */
object TestImpactPlugin extends AutoPlugin {

  override def trigger = allRequirements

  override def requires = JvmPlugin

  object autoImport {
    val ciImpactGraph: TaskKey[TestImpact.Graph] =
      taskKey[TestImpact.Graph]("The classes this configuration's sources define and depend on, from zinc")
    val ciImpactNames: TaskKey[(Seq[TestImpact.ConfigFile], Set[String], Seq[TestImpact.RoutesFile])] =
      taskKey[(Seq[TestImpact.ConfigFile], Set[String], Seq[TestImpact.RoutesFile])](
        "The configuration files among this configuration's resources, the names its resource files have the JVM load, and its routes files"
      )
    val ciImpactSources: TaskKey[(Set[String], Map[String, TestImpact.Bindings], Map[String, TestImpact.Requests])] =
      taskKey[(Set[String], Map[String, TestImpact.Bindings], Map[String, TestImpact.Requests])](
        "The classes this configuration defines in a source that boots an application, what each module source binds, and what each test source requests of a router"
      )
    val ciImpactInputs: TaskKey[TestImpact.Inputs] =
      taskKey[TestImpact.Inputs]("What this subproject's test task can see, for the class-level selection")
    val ciImpactSelection: TaskKey[Option[TestImpact.Selection]] =
      taskKey[Option[TestImpact.Selection]]("The specs CI_TEST_IMPACT's changed paths reach; None when it is unset")
  }

  import autoImport.*

  /** Read once per load, and a file that cannot be read fails the load: a build told to run the
    * specs a change reaches must not run with none of the change.
    */
  private lazy val changedPaths: Option[TestImpact.Changes] =
    sys.env.get(TestImpact.ChangedPathsEnvVar).filter(_.trim.nonEmpty).map { path =>
      try TestImpact.parseChanged(IO.read(file(path)))
      catch { case NonFatal(e) => sys.error(s"${TestImpact.ChangedPathsEnvVar}=$path: ${e.getMessage}") }
    }

  /** A resource file this large, or holding a NUL byte, is not configuration. */
  private val MaxResourceBytes = 512 * 1024

  private def graphOf(
    analysis: xsbti.compile.CompileAnalysis,
    converter: xsbti.FileConverter,
    root: File
  ): TestImpact.Graph =
    analysis match {
      case a: sbt.internal.inc.Analysis =>
        val relations = a.relations
        val base = root.toPath.toAbsolutePath.normalize
        val sources = relations.allSources.toSeq.map { ref =>
          val path = converter.toPath(ref).toAbsolutePath.normalize
          val relative = if (path.startsWith(base)) base.relativize(path).toString else path.toString
          relative -> relations.classNames(ref)
        }
        TestImpact.Graph.of(sources, (relations.internalClassDep.all ++ relations.externalClassDep.all).toSeq)
      case _ => TestImpact.Graph.empty
    }

  private def readText(f: File): Option[String] =
    try Some(IO.read(f)).filter(_.indexOf('\u0000') < 0)
    catch { case NonFatal(_) => None }

  /** Each resource file read once, under its name on the classpath. */
  private def namesIn(dirs: Seq[File]): (Seq[TestImpact.ConfigFile], Set[String], Seq[TestImpact.RoutesFile]) = {
    val texts = dirs.flatMap { dir =>
      (dir ** "*")
        .get()
        .filter(f => f.isFile && f.length <= MaxResourceBytes)
        .flatMap(f => readText(f).map(text => (IO.relativize(dir, f).getOrElse(f.getName), f.getPath, text)))
    }
    (
      texts.collect {
        case (resource, _, text) if TestImpact.appNamedIn(resource, text).nonEmpty || resource.endsWith(".conf") =>
          TestImpact.configFile(resource, text)
      },
      texts.flatMap { case (_, path, text) => TestImpact.jvmNamedIn(path, text) }.toSet,
      texts.flatMap { case (resource, _, text) => TestImpact.routesFile(resource, text) },
    )
  }

  /** Each compiled source read once: the classes of the ones booting an application, what each Guice
    * module source binds, and, when `requests`, what each requests of a router.
    */
  private def sourcesIn(
    graph: TestImpact.Graph,
    root: File,
    requests: Boolean,
  ): (Set[String], Map[String, TestImpact.Bindings], Map[String, TestImpact.Requests]) = {
    val read = graph.sources.toSeq.flatMap { case (path, classes) =>
      val f = if (file(path).isAbsolute) file(path) else root / path
      readText(f).map(text => (path, classes, text))
    }
    (
      read.collect { case (_, classes, text) if TestImpact.bootsApplication(text) => classes }.flatten.toSet,
      read.flatMap { case (path, _, text) =>
        Some(TestImpact.bindingsIn(text)).filter(_.nonEmpty).map(path -> _)
      }.toMap,
      if (requests) read.map { case (path, _, text) => path -> TestImpact.requestsIn(text) }.toMap else Map.empty,
    )
  }

  private val perConfiguration: Seq[Setting[?]] = Seq(
    ciImpactGraph := graphOf(compile.value, fileConverter.value, (ThisBuild / baseDirectory).value),
    ciImpactNames := namesIn(unmanagedResourceDirectories.value),
    ciImpactSources := sourcesIn(
      ciImpactGraph.value,
      (ThisBuild / baseDirectory).value,
      requests = configuration.value.name == Test.name,
    ),
  )

  /** Every (subproject, configuration) on `self`'s test classpath, `self`'s own two included. */
  private def reach(
    classpath: Map[ProjectRef, Seq[ClasspathDep[ProjectRef]]],
    self: ProjectRef,
  ): Set[(ProjectRef, String)] = {
    @annotation.tailrec
    def walk(pending: List[(ProjectRef, String)], seen: Set[(ProjectRef, String)]): Set[(ProjectRef, String)] =
      pending match {
        case Nil => seen
        case (ref, config) :: tail =>
          val own = if (config == "test") Seq(ref -> "compile") else Nil
          val next = (own ++ classpath.getOrElse(ref, Nil).flatMap { dep =>
            TestImpact.dependencyConfigs(dep.configuration, config).toSeq.map(dep.project -> _)
          }).filterNot(seen.contains)
          walk(next.toList ++ tail, seen ++ next)
      }
    walk(List(self -> "test"), Set(self -> "test"))
  }

  private def relativeDirs(root: File, dirs: Seq[File]): Seq[String] =
    dirs.flatMap(dir => IO.relativize(root, dir)).filter(_.nonEmpty).distinct

  private def inputsTask: Def.Initialize[Task[TestImpact.Inputs]] = Def.taskDyn {
    val self = thisProjectRef.value
    val scopes = reach(buildDependencies.value.classpath, self)
    val compileRefs = scopes.collect { case (ref, "compile") => ref }.toSeq
    val testRefs = scopes.collect { case (ref, "test") => ref }.toSeq
    val seen = scopes.map(_._1).toSeq
    val hiddenRefs = seen.filterNot(testRefs.contains)
    val compiled = ScopeFilter(inProjects(compileRefs: _*), inConfigurations(Compile))
    val tested = ScopeFilter(inProjects(testRefs: _*), inConfigurations(Test))
    val visible = ScopeFilter(inProjects(seen: _*))
    val hidden =
      ScopeFilter(inProjects((if (hiddenRefs.isEmpty) Seq(self) else hiddenRefs): _*), inConfigurations(Test))
    Def.task {
      val root = (ThisBuild / baseDirectory).value
      val graph = (ciImpactGraph.all(compiled).value ++ ciImpactGraph.all(tested).value)
        .foldLeft(TestImpact.Graph.empty)(_ ++ _)
      val names = ciImpactNames.all(compiled).value ++ ciImpactNames.all(tested).value
      val sources = ciImpactSources.all(compiled).value ++ ciImpactSources.all(tested).value
      val hiddenDirs =
        if (hiddenRefs.isEmpty) Nil
        else (unmanagedSourceDirectories.all(hidden).value ++ unmanagedResourceDirectories.all(hidden).value).flatten
      TestImpact.Inputs(
        project = self.project,
        graph = graph,
        named = TestImpact.configNamed(names.flatMap(_._1), TestImpact.configResourceIn((Test / javaOptions).value)),
        loaded = names.flatMap(_._2).toSet,
        booters = sources.flatMap(_._1).toSet,
        specs = (Test / definedTestNames).value.toSet,
        visibleDirs = relativeDirs(root, baseDirectory.all(visible).value),
        hiddenDirs = relativeDirs(root, hiddenDirs),
        projectDirs = relativeDirs(root, baseDirectory.all(ScopeFilter(inAnyProject)).value),
        bindings = sources.flatMap(_._2).toMap,
        routes = names.flatMap(_._3),
        requests = sources.flatMap(_._3).toMap,
      )
    }
  }

  override def projectSettings: Seq[Setting[?]] =
    inConfig(Compile)(perConfiguration) ++ inConfig(Test)(perConfiguration) ++ Seq(
      ciImpactInputs := inputsTask.value,
      ciImpactSelection := Def.taskDyn {
        changedPaths match {
          case None => Def.task(Option.empty[TestImpact.Selection])
          case Some(paths) =>
            Def.task {
              val in = ciImpactInputs.value
              val selection = TestImpact.select(paths, in)
              println(TestImpact.line(in.project, selection, in.specs.size))
              Option(selection)
            }
        }
      }.value,
      // Unscoped, as `CiTestListsPlugin`'s filter is, so it reaches `test` and `testOnly` alike.
      testOptions ++= ciImpactSelection.value.toSeq.map(selection => Tests.Filter(selection.runs)),
    )

  override def globalSettings: Seq[Setting[?]] = Seq(commands += ciImpactPlan)

  /** `ciImpactPlan <file>`: the file holds change sets, each a `== <label>` line followed by its
    * changed paths (and `whole` lines, as `CI_TEST_IMPACT`'s file has). It prints every subproject's specs once, then for every subproject with specs
    * and every change set one line, the specs that change runs or why it runs them all:
    *
    *   ci-impact-plan: specs <subproject> <spec>...
    *   ci-impact-plan: <label> <subproject> class <spec>...
    *   ci-impact-plan: <label> <subproject> booted <entry> <spec>...
    *   ci-impact-plan: <label> <subproject> routed <router> <spec>...
    *   ci-impact-plan: <label> <subproject> edges <spec>...   (after either: the edges alone)
    *   ci-impact-plan: <label> <subproject> entries <class>... (after either: every entry it reaches)
    *   ci-impact-plan: <label> <subproject> whole <reason>
    *
    * It compiles what `ciImpactInputs` needs and runs no test.
    */
  private def ciImpactPlan: Command = Command.single("ciImpactPlan") { (state, path) =>
    val sets = parseChangeSets(IO.read(file(path)))
    val extracted = Project.extract(state)
    val (last, inputs) = extracted.structure.allProjectRefs.foldLeft((state, Seq.empty[TestImpact.Inputs])) {
      case ((current, acc), ref) =>
        val (next, in) = extracted.runTask(ref / ciImpactInputs, current)
        (next, if (in.specs.isEmpty) acc else acc :+ in)
    }
    inputs.foreach { in =>
      println(s"ci-impact-plan: specs ${in.project} ${in.specs.toSeq.sorted.mkString(" ")}".trim)
      println(
        s"ci-impact-plan: graph ${in.project} classes=${in.graph.classes.size} booting=${in.booting.size} " +
          s"jvm=${in.jvmEntries.toSeq.sorted.mkString(",")} entries=${in.entries.toSeq.sorted.mkString(",")} " +
          s"modules=${in.modules.keySet.toSeq.sorted.mkString(",")} boots=${in.bootEntries.size} " +
          s"routers=${in.routing.routers.size} controllers=${in.routing.controllers.size} " +
          s"requesting=${in.requesting.size}"
      )
    }
    for ((label, paths) <- sets; in <- inputs) {
      TestImpact.select(paths, in) match {
        case TestImpact.Specs(specs, _, _, None, _) =>
          println(s"ci-impact-plan: $label ${in.project} class ${specs.toSeq.sorted.mkString(" ")}".trim)
        case TestImpact.Specs(specs, _, _, Some(entry), routed) =>
          val kind = if (routed) "routed" else "booted"
          println(s"ci-impact-plan: $label ${in.project} $kind $entry ${specs.toSeq.sorted.mkString(" ")}".trim)
          // What the class edges alone reach, for the measurement: the cost of widening to every
          // spec booting an application (or requesting a route), which an answer trusting the edges would save.
          val edges = TestImpact.select(paths, in.copy(booters = Set.empty)) match {
            case TestImpact.Specs(reached, _, _, _, _) => reached
            case TestImpact.Whole(_) => in.specs
          }
          println(s"ci-impact-plan: $label ${in.project} edges ${edges.toSeq.sorted.mkString(" ")}".trim)
          // Every entry the change reaches, not only the one named: what it would still widen on.
          println(s"ci-impact-plan: $label ${in.project} entries ${TestImpact.entriesReached(paths, in).mkString(" ")}")
        case TestImpact.Whole(reason) => println(s"ci-impact-plan: $label ${in.project} whole $reason")
      }
    }
    println(s"ci-impact-plan: done ${sets.size} change sets")
    last
  }

  private def parseChangeSets(text: String): Seq[(String, TestImpact.Changes)] =
    text
      .split("\n")
      .toSeq
      .foldLeft(Vector.empty[(String, Vector[String])]) { (acc, raw) =>
        val line = raw.trim
        if (line.startsWith("== ")) acc :+ (line.stripPrefix("== ").trim -> Vector.empty)
        else if (line.isEmpty || acc.isEmpty) acc
        else acc.init :+ (acc.last._1 -> (acc.last._2 :+ line))
      }
      .map { case (label, lines) => label -> TestImpact.parseChanged(lines.mkString("\n")) }
}
