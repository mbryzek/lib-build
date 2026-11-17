package testimpact

/** Which of a subproject's specs a change can reach, from the class dependencies zinc records.
  *
  * zinc's analysis of every compiled configuration says which classes each source defines and which
  * classes each class depends on, by member reference and by inheritance, inside its subproject and
  * across into the subprojects it is built on. A spec whose class does not reach a changed class
  * through those edges compiled against none of the change, so the class-level answer is the specs
  * the REVERSE closure of the changed classes holds — a few dozen for a change under core, rather
  * than every suite of every subproject built on core.
  *
  * WHAT THE EDGES CANNOT SEE, AND WHAT IT COSTS. A class reached by name rather than by reference:
  * a Guice module or a router Play loads because configuration names it, a binding the injector
  * resolves at run time, a resource read off the classpath. So the answer widens:
  *
  *   - to every spec that BOOTS AN APPLICATION when the change reaches a class the application loads
  *     by name: one Play or Pekko configuration names (`play.modules.enabled`, `play.http.filters`)
  *     or one Play loads by convention ([[ConventionalEntries]]). Such a spec reaches every binding of
  *     every enabled module, the router and the filters without naming one; a spec that boots
  *     nothing cannot reach them at all. A spec boots one when its classes reach a source that names
  *     a booting API ([[BootingApis]]): `DefaultAppSpec` does, so every spec extending it does;
  *   - but a router is read rather than taken whole ([[route]]). It constructs every controller its
  *     routes name, and a spec reaches a controller's actions only by requesting one of its routes.
  *     So the edge from a router to a controller it names is replaced by an edge from each test
  *     source to the controllers whose routes it requests ([[requestsIn]]): by a generated client's
  *     operation, whose method is named as the route's action, or, for a request the source builds
  *     itself, by the path it writes. A change reaching a router itself (its routes file, a binder
  *     it reads) adds every booting spec requesting a route ([[Inputs.requesting]]), and a spec
  *     booting an application without requesting a route reaches no controller through it;
  *   - and a Guice module is read rather than taken whole ([[bindingsIn]]). A binding of `T` to
  *     `Impl`, or to a provider or an instance of `T`, is an edge from `T` to `Impl`: whoever depends
  *     on `T` is handed `Impl`, so a change reaching `Impl` reaches them, and the module reaching
  *     `Impl` through that binding widens nothing. What the module still loads when an application
  *     boots stands in for it: a class bound by name (an actor, an eager singleton) and every class
  *     its source references that no typed binding accounts for. A change to the module's own source
  *     still widens, since it can change any binding;
  *   - to every spec of the subproject (`Whole`) when the change reaches a class the JVM loads by
  *     name for any spec (a logback appender, a `ServiceLoader` implementation), or touches a path
  *     under a subproject this one is built on that zinc did not compile as a source
  *     (configuration, a resource, a deleted source). A routes file stands for the router sources
  *     Play compiled from it, so it is followed like them.
  *
  * The build of `main` runs the whole suite, so anything the edges miss is still caught there.
  *
  * No sbt type appears here on purpose, for the reason `TestShards` gives: this file is compiled by
  * the meta-build, where [[TestImpactPlugin]] feeds it zinc's analysis, and by `testkit` as a test
  * source, where `TestImpactSpec` asserts every rule below.
  */
object TestImpact {

  /** A file naming the changed paths, one repository-relative path a line, and `whole <subproject>`
    * for a subproject that runs every spec whatever the classes say (its tests read a changed spec
    * as a file). `ci/build.slow.sh` writes it from `scripts/affected-subprojects --format impact` and
    * exports it for the suite's invocation alone; unset, no spec is filtered.
    */
  val ChangedPathsEnvVar: String = "CI_TEST_IMPACT"

  /** Classes Play loads by name when configuration names none: the generated router, the root
    * `Module`, and the root-package handlers it looks for.
    */
  val ConventionalEntries: Set[String] = Set("router.Routes", "Module", "ErrorHandler", "Filters", "RequestHandler")

  /** What a source names when it starts a Play application or a Guice injector. */
  val BootingApis: Seq[String] = Seq(
    "GuiceApplicationBuilder",
    "GuiceInjectorBuilder",
    "GuiceApplicationLoader",
    "ApplicationLoader",
    "GuiceOneAppPerSuite",
    "GuiceOneAppPerTest",
    "GuiceOneServerPerSuite",
    "GuiceOneServerPerTest",
    "Guice.createInjector",
  )

  def bootsApplication(sourceText: String): Boolean = BootingApis.exists(sourceText.contains)

  /** The classes the compiled sources define and depend on.
    *
    * @param sources
    *   repository-relative source path -> the classes it defines
    * @param dependsOn
    *   class -> the classes it depends on, in its own subproject and in the ones it is built on
    */
  final case class Graph(sources: Map[String, Set[String]], dependsOn: Map[String, Set[String]]) {
    def ++(other: Graph): Graph =
      Graph(
        sources = mergeSets(sources, other.sources),
        dependsOn = mergeSets(dependsOn, other.dependsOn),
      )

    def classes: Set[String] = sources.values.flatten.toSet ++ dependsOn.keySet

    /** `classes` and every class that depends on one of them, directly or transitively. */
    def dependents(classes: Set[String]): Set[String] = {
      val reverse: Map[String, Set[String]] =
        dependsOn.toSeq
          .flatMap { case (from, tos) => tos.toSeq.map(to => to -> from) }
          .groupBy(_._1)
          .map { case (to, pairs) => to -> pairs.map(_._2).toSet }
      @annotation.tailrec
      def walk(pending: List[String], seen: Set[String]): Set[String] = pending match {
        case Nil => seen
        case head :: tail =>
          val next = reverse.getOrElse(head, Set.empty).filterNot(seen.contains)
          walk(next.toList ++ tail, seen ++ next)
      }
      walk(classes.toList, classes)
    }
  }

  object Graph {
    val empty: Graph = Graph(Map.empty, Map.empty)

    /** zinc names an object `a.B$` beside its class `a.B`; one name stands for both here. */
    def normalize(className: String): String = className.stripSuffix("$")

    /** From zinc's own shapes: each source with its classes, and each class-to-class edge. */
    def of(sources: Seq[(String, Set[String])], dependsOn: Seq[(String, String)]): Graph =
      Graph(
        sources = sources.groupBy(_._1).map { case (path, pairs) => path -> pairs.flatMap(_._2).map(normalize).toSet },
        dependsOn = dependsOn
          .map { case (from, to) => normalize(from) -> normalize(to) }
          .filter { case (from, to) => from != to }
          .groupBy(_._1)
          .map { case (from, pairs) => from -> pairs.map(_._2).toSet },
      )
  }

  private def mergeSets(a: Map[String, Set[String]], b: Map[String, Set[String]]): Map[String, Set[String]] =
    b.foldLeft(a) { case (acc, (k, v)) => acc.updated(k, acc.getOrElse(k, Set.empty) ++ v) }

  /** What one subproject's test task can see.
    *
    * @param project
    *   the subproject's id
    * @param graph
    *   every configuration on its test classpath, its own included
    * @param named
    *   every dotted name the configuration its application reads has it load ([[configNamed]])
    * @param loaded
    *   every dotted name a resource file on that classpath has the JVM load ([[jvmNamedIn]])
    * @param booters
    *   the classes defined by a source that boots an application ([[bootsApplication]])
    * @param specs
    *   the suites its test task defines
    * @param visibleDirs
    *   repository-relative base directories of the subprojects on its test classpath
    * @param hiddenDirs
    *   source and resource directories under those that are NOT on its test classpath: a subproject
    *   built on `core` only through `compile->compile` sees nothing under `core/test`
    * @param projectDirs
    *   the base directory of every subproject in the build
    * @param bindings
    *   repository-relative source path -> what the Guice module it defines binds ([[bindingsIn]])
    * @param routes
    *   every routes file among the resources on its test classpath ([[routesFile]])
    * @param requests
    *   repository-relative test source path -> what it requests of a router ([[requestsIn]])
    */
  final case class Inputs(
    project: String,
    graph: Graph,
    named: Set[String],
    loaded: Set[String],
    booters: Set[String],
    specs: Set[String],
    visibleDirs: Seq[String],
    hiddenDirs: Seq[String],
    projectDirs: Seq[String],
    bindings: Map[String, Bindings] = Map.empty,
    routes: Seq[RoutesFile] = Nil,
    requests: Map[String, Requests] = Map.empty,
  ) {

    /** The classes an application loads by name. */
    lazy val entries: Set[String] = (named ++ ConventionalEntries).filter(graph.classes.contains)

    /** The classes the JVM loads by name, for a spec whether it boots an application or not. */
    lazy val jvmEntries: Set[String] = loaded.filter(graph.classes.contains)

    /** Every entry defined by a source that binds, read ([[wire]]). */
    lazy val modules: Map[String, Wiring] = {
      val sourceOf = graph.sources.toSeq.flatMap { case (path, classes) => classes.map(_ -> path) }.toMap
      entries.toSeq.flatMap { entry =>
        sourceOf.get(entry).flatMap(bindings.get).filter(_.nonEmpty).map(b => entry -> wire(entry, b, graph))
      }.toMap
    }

    /** The routers read, and what the test sources request of them ([[route]]). */
    lazy val routing: Routing = route(graph, routes, requests)

    /** `graph`, with an edge from every key a module binds to what it binds the key to, and the
      * edges of [[routing]] in place of those from a router to the controllers its routes name.
      */
    lazy val wired: Graph = {
      val cut = graph.dependsOn.map { case (from, tos) =>
        from -> (if (routing.routers.contains(from)) tos -- routing.controllers else tos)
      }
      val added = Graph.of(Nil, (modules.values.flatMap(_.edges) ++ routing.edges).toSeq).dependsOn
      graph.copy(dependsOn = mergeSets(cut, added))
    }

    /** The classes an application loads when it boots: every entry, a module standing for what it
      * still loads ([[Wiring.boots]]). A router read is not one: a spec reaches it by requesting a
      * route, which [[requesting]] answers.
      */
    lazy val bootEntries: Set[String] =
      (entries -- modules.keySet -- routing.routers) ++ modules.values.flatMap(_.boots)

    /** The specs that boot an application. */
    lazy val booting: Set[String] = {
      val reaching = wired.dependents(booters)
      specs.map(Graph.normalize).filter(reaching.contains)
    }

    /** The specs that boot an application and request a route of it. */
    lazy val requesting: Set[String] = booting.intersect(wired.dependents(routing.requesting))
  }

  /** One route of a routes file: `GET /a/:id  a.Controller.action(id: String)`. */
  final case class Route(path: String, controller: String, action: String)

  /** A routes file: the router Play compiles it to, its routes, and the routers it includes, each
    * with the prefix it is mounted at (`-> /prefix sub.Routes`).
    */
  final case class RoutesFile(router: String, routes: Seq[Route], includes: Seq[(String, String)])

  private val RouteLine = """^[A-Z]+\s+(\S+)\s+@?([A-Za-z_][\w.$]*)\.(\w+)\s*(?:\(.*)?$""".r
  private val IncludeLine = """^->\s+(\S+)\s+([A-Za-z_][\w.]*)$""".r

  /** A routes file read: `<pkg>.routes` compiles to `<pkg>.Routes`, a bare `routes` to `router.Routes`. */
  def routesFile(resource: String, text: String): Option[RoutesFile] = {
    val name = resource.split('/').last
    val pkg = if (name == "routes") Some("router") else Some(name.stripSuffix(".routes")).filter(_ != name)
    pkg.map { p =>
      val lines = text.split("\n").toSeq.map(_.trim).filterNot(_.startsWith("#"))
      RoutesFile(
        router = s"$p.Routes",
        routes = lines.collect { case RouteLine(path, controller, action) => Route(path, controller, action) },
        includes = lines.collect { case IncludeLine(prefix, router) => prefix -> router },
      )
    }
  }

  /** What a test source requests of an application's router.
    *
    * @param names
    *   every identifier it writes: a generated client's operation is named as its route's action
    * @param paths
    *   every string literal it writes that reads as a request path, each interpolation a [[Hole]]
    * @param raw
    *   it builds a request itself, or is handed a router ([[RawRequestApis]])
    */
  final case class Requests(names: Set[String], paths: Set[String], raw: Boolean)

  /** What a source names when it builds a request rather than calling a generated client's operation:
    * Play's `route(app, FakeRequest(...))` and `call(...)`, a `WSClient`'s `url(...)`, a connection,
    * or a router it walks.
    */
  val RawRequestApis: Seq[String] =
    Seq("FakeRequest", "route(", "call(", ".url(", "openConnection", "Router", "handlerFor", "Routes")

  /** An interpolated value in a path a source writes. */
  val Hole: Char = '\u0001'

  private val StringLiteral = "\"((?:[^\"\\\\\n]|\\\\.)*)\"".r
  private val Interpolation = """\$\{[^}]*\}|\$[A-Za-z_]\w*""".r

  /** [[Comment]], leaving the `//` of a URL a string holds: `"http://host/a"` is a request path. */
  private val CommentNotUrl = """(?s)/\*.*?\*/|(?<!:)//[^\n]*""".r
  private val Identifier = """\b[a-z][A-Za-z0-9_]*\b""".r

  /** A string literal as the path it requests: `s"$base/a/${id}?x=1"` is `/a/<Hole>`. A literal that
    * is nothing but interpolations is a lone [[Hole]], a request whose path nobody can read; a
    * literal starting anywhere else is not a path.
    */
  def requestPath(literal: String): Option[String] = {
    val holed = Interpolation.replaceAllIn(literal, Hole.toString)
    val path = holed.replaceFirst("^https?://[^/]*", "").dropWhile(_ == Hole).takeWhile(c => c != '?' && c != '#')
    if (holed.nonEmpty && holed.forall(_ == Hole)) Some(Hole.toString)
    else if (path.startsWith("/")) Some(path)
    else None
  }

  def requestsIn(sourceText: String): Requests = {
    val text = CommentNotUrl.replaceAllIn(sourceText, " ")
    Requests(
      names = Identifier.findAllIn(text).toSet,
      paths = StringLiteral.findAllMatchIn(text).flatMap(m => requestPath(m.group(1))).toSet,
      raw = RawRequestApis.exists(text.contains),
    )
  }

  /** Whether a path a source writes can request a route's path, segment by segment: a route's
    * parameter, or a segment holding an interpolation, matches any one; a route's `*rest`, or a
    * written path ending in `/` (the rest appended at run time), matches whatever is left.
    */
  def pathMatches(written: String, route: String): Boolean = {
    def segments(p: String) = p.split("/", -1).toList.drop(1)
    @annotation.tailrec
    def walk(w: List[String], r: List[String]): Boolean = (w, r) match {
      case (_, rest :: _) if rest.startsWith("*") => true
      case (List(""), _ :: _) => true
      case (Nil, Nil) => true
      case (ws :: wt, rs :: rt) if ws.contains(Hole) || rs.startsWith(":") || rs.startsWith("$") || ws == rs =>
        walk(wt, rt)
      case _ => false
    }
    walk(segments(written), segments(route))
  }

  /** The routers read, and what the test sources request of them.
    *
    * @param routers
    *   every router a routes file was read for
    * @param controllers
    *   every class their routes name, to which [[Inputs.wired]] cuts a router's edges
    * @param edges
    *   from every class a test source defines to the controllers whose routes it requests, from a
    *   caller of one requesting a path nobody can read to those of the paths it hands over, and to
    *   every router and controller from a class naming a router or requesting a path no route matches
    * @param requesting
    *   the classes those edges leave
    */
  final case class Routing(
    routers: Set[String],
    controllers: Set[String],
    edges: Set[(String, String)],
    requesting: Set[String],
  )

  /** Read `files` against `graph`. Only a router `graph` defines in a source Play compiled from a
    * routes file is read, and only a controller `graph` holds counts; a router no file is read for
    * keeps its edges and stays an entry an application loads when it boots.
    *
    * A source building a request to a path nobody can read (`s"$base$path"`) requests the paths its
    * callers hand it: every outermost test class reaching it requests what the test sources between
    * the two write, and every router and controller when they write no path a route matches.
    */
  def route(graph: Graph, files: Seq[RoutesFile], requests: Map[String, Requests]): Routing = {
    val compiled = graph.sources.collect { case (p, cs) if p.contains("/routes/main/") => cs }.flatten.toSet
    val read = files.filter(f => compiled.contains(f.router)).groupBy(_.router).map { case (r, fs) => r -> fs.head }
    val routes = mountedRoutes(read).filter(r => graph.classes.contains(r.controller))
    val routers = read.keySet
    val everything = routers ++ routes.map(_.controller)
    val byAction = routes.groupBy(_.action).map { case (a, rs) => a -> rs.map(_.controller).toSet }
    def written(r: Requests): Set[String] =
      r.paths.filter(_ != Hole.toString).flatMap(p => routes.filter(rt => pathMatches(p, rt.path)).map(_.controller))
    def opaque(r: Requests): Boolean = r.raw && r.paths.contains(Hole.toString)
    def requested(r: Requests): Set[String] = {
      val byPath = Some(written(r)).filter(_.nonEmpty || opaque(r)).getOrElse(everything)
      r.names.flatMap(byAction.getOrElse(_, Set.empty)) ++ (if (r.raw) byPath else Set.empty)
    }
    val sourceOf = graph.sources.toSeq.flatMap { case (path, cs) => cs.map(_ -> path) }.toMap
    val tested = sourceOf.collect { case (c, path) if requests.contains(path) => c -> requests(path) }
    val fromSources = tested.toSeq.flatMap { case (c, r) => requested(r).map(c -> _) }
    val fromCallers = callerEdges(graph, tested.filter(t => opaque(t._2)).keySet, tested.keySet) { chain =>
      Some(chain.flatMap(c => written(tested(c)))).filter(_.nonEmpty).getOrElse(everything)
    }
    // A class outside the routers depending on one walks it, whatever it requests.
    val namingRouter = graph.dependsOn.toSeq.collect {
      case (from, tos) if !compiled.contains(from) && tos.exists(routers.contains) => from
    }
    val edges = (fromSources ++ fromCallers ++ namingRouter.flatMap(from => everything.map(from -> _))).toSet
    Routing(routers, routes.map(_.controller).toSet, edges, edges.map(_._1))
  }

  /** Every route of `read`, mounted from the routers no other includes, each path under the
    * prefixes its includes give it, its controller normalized.
    */
  private def mountedRoutes(read: Map[String, RoutesFile]): Seq[Route] = {
    def under(prefix: String, path: String) = (prefix + "/" + path).replaceAll("/+", "/")
    def mounted(router: String, prefix: String, seen: Set[String]): Seq[Route] =
      read.get(router).filterNot(_ => seen.contains(router)).toSeq.flatMap { f =>
        f.routes.map(r => r.copy(path = under(prefix, r.path))) ++
          f.includes.flatMap { case (p, sub) => mounted(Graph.normalize(sub), under(prefix, p), seen + router) }
      }
    val included = read.values.flatMap(_.includes.map(i => Graph.normalize(i._2))).toSet
    val roots = Some(read.keySet -- included).filter(_.nonEmpty).getOrElse(read.keySet)
    roots.toSeq
      .flatMap(mounted(_, "/", Set.empty))
      .map(r => r.copy(controller = Graph.normalize(r.controller)))
      .distinct
  }

  /** For every class of `within` reaching one of `helpers` that no other such class depends on, an
    * edge to each of `targets(chain)`, `chain` being the classes of `within` between it and the
    * helpers it reaches, both included.
    */
  private def callerEdges(graph: Graph, helpers: Set[String], within: Set[String])(
    targets: Set[String] => Set[String]
  ): Seq[(String, String)] =
    if (helpers.isEmpty) Nil
    else {
      val inside = Graph(
        Map.empty,
        graph.dependsOn.collect {
          case (from, tos) if within.contains(from) => from -> tos.intersect(within)
        }
      )
      val reaching = inside.dependents(helpers)
      @annotation.tailrec
      def down(pending: List[String], seen: Set[String]): Set[String] = pending match {
        case Nil => seen
        case head :: tail =>
          val next = inside.dependsOn.getOrElse(head, Set.empty).intersect(reaching).filterNot(seen.contains)
          down(next.toList ++ tail, seen ++ next)
      }
      val chains = reaching.toSeq.map(c => c -> down(List(c), Set(c))).toMap
      // Covered: a class something it does not reach depends on, and every class of its cycle with it
      // (a class and the anonymous classes it defines depend on each other, and stand or fall as one).
      val covered = reaching.flatMap { d =>
        inside.dependsOn
          .getOrElse(d, Set.empty)
          .intersect(reaching)
          .filter(c => c != d && !chains(c).contains(d))
          .flatMap(c => chains(c).filter(x => chains(x).contains(c)))
      }
      (reaching -- covered).toSeq.flatMap(top => targets(chains(top)).toSeq.map(top -> _))
    }

  /** What a module's source binds, by the names it writes: every key it binds, every key it binds
    * to a target (`to`, `toProvider`, `toInstance`, a set binding's `addBinding`, an actor factory's
    * `bindActorFactory[A, F]`, which binds `F` to what builds an `A` when `F` is called), and every
    * class it binds by name (`bindActor[A]` and every other `bindX[A]`, an eager singleton's key and
    * target), which an application constructs at boot whoever depends on it.
    */
  final case class Bindings(keys: Set[String], typed: Set[(String, String)], byName: Set[String]) {
    def nonEmpty: Boolean = keys.nonEmpty || byName.nonEmpty
  }

  object Bindings {
    val empty: Bindings = Bindings(Set.empty, Set.empty, Set.empty)
  }

  /** A module's bindings resolved against the classes it depends on.
    *
    * @param edges
    *   key -> target, for every typed binding both of whose names resolve to one class
    * @param boots
    *   what the module still loads when an application boots: every class it depends on that is
    *   neither a key nor a typed binding's target, and every class it binds by name
    */
  final case class Wiring(edges: Set[(String, String)], boots: Set[String])

  /** Resolve `b` against the classes `module` depends on. A name written short resolves to the one
    * dependency it ends; a name resolving to none or to several accounts for nothing, so the class
    * it meant stays among what the module boots, and so does a key bound to it.
    */
  def wire(module: String, b: Bindings, graph: Graph): Wiring = {
    val deps = graph.dependsOn.getOrElse(module, Set.empty)
    def resolve(name: String): Set[String] = {
      val n = Graph.normalize(name)
      deps.filter(c => c == n || c.endsWith("." + n))
    }
    // `a.Obj.Instance` is a value of the object `a.Obj`, which is the class depended on.
    def one(name: String): Option[String] =
      Some(resolve(name)).filter(_.size == 1).map(_.head).orElse {
        if (name.contains('.')) one(name.substring(0, name.lastIndexOf('.'))) else None
      }
    val byName = b.byName.flatMap(resolve)
    val typed = b.typed.toSeq.map { case (key, target) => (key, one(key), one(target)) }
    val edges = typed.collect { case (_, Some(key), Some(target)) => key -> target }.toSet
    // A key bound to something that resolves to no class is handed a class nobody can follow.
    val unfollowed = typed.collect { case (key, _, None) => key }.toSet
    val accounted = ((b.keys -- unfollowed).flatMap(one) ++ edges.map(_._2)) -- byName
    Wiring(edges, deps -- accounted - module)
  }

  private val ModuleDeclaration =
    """\b(?:AbstractModule|PekkoGuiceSupport)\b|\bextends\s+(?:play\.api\.inject\.)?Module\b""".r

  private val Comment = """(?s)/\*.*?\*/|//[^\n]*""".r

  private val TypeName = """([A-Za-z_][\w.]*)"""
  private val ClassArg = s"""(?:\\(\\s*classOf\\[$TypeName|\\[$TypeName)"""

  private sealed trait Token
  private final case class SetVal(name: String, key: String) extends Token
  private final case class NewSet(key: String) extends Token
  private final case class AddTo(set: Option[String]) extends Token
  private final case class Bind(key: String) extends Token
  private final case class BindByName(classes: Seq[String]) extends Token
  private final case class BindFactory(actor: String, factory: String) extends Token
  private final case class Target(name: String) extends Token
  private case object ToSelf extends Token
  private case object Eager extends Token

  private val Tokens: Seq[(scala.util.matching.Regex, scala.util.matching.Regex.Match => Token)] = Seq(
    (
      s"""\\bval\\s+(\\w+)\\s*=\\s*[\\w.\\s]*newSetBinder\\s*\\(\\s*binder\\(\\)\\s*,\\s*classOf\\[$TypeName""".r,
      m => SetVal(m.group(1), m.group(2))
    ),
    (s"""\\bnewSetBinder\\s*\\(\\s*binder\\(\\)\\s*,\\s*classOf\\[$TypeName""".r, m => NewSet(m.group(1))),
    ("""\b(\w+)\s*\.\s*addBinding\s*\(\s*\)""".r, m => AddTo(Some(m.group(1)))),
    ("""\)\s*\.\s*addBinding\s*\(\s*\)""".r, _ => AddTo(None)),
    (s"""\\bbind\\s*$ClassArg""".r, m => Bind(Option(m.group(1)).getOrElse(m.group(2)))),
    // Ahead of the by-name pattern, which matches the same span: an earlier entry wins a tie.
    (
      s"""\\bbindActorFactory\\s*\\[\\s*$TypeName\\s*,\\s*$TypeName\\s*\\]""".r,
      m => BindFactory(m.group(1), m.group(2))
    ),
    ("""\bbind[A-Z]\w*\s*\[([^\]]*)\]""".r, m => BindByName(TypeNameIn.findAllIn(m.group(1)).toSeq)),
    (s"""\\.\\s*(?:to|toProvider)\\s*$ClassArg""".r, m => Target(Option(m.group(1)).getOrElse(m.group(2)))),
    (s"""\\.\\s*toInstance\\s*\\(\\s*$TypeName\\s*\\)""".r, m => Target(m.group(1))),
    ("""\.\s*toSelf\b""".r, _ => ToSelf),
    ("""\.\s*(?:asEagerSingleton|eagerly)\s*\(\s*\)""".r, _ => Eager),
  )

  private val TypeNameIn = TypeName.r

  /** `val fake = new FakeClient(...)`: what an instance binding of `fake` binds. */
  private val LocalInstance = s"""\\bval\\s+(\\w+)\\s*=\\s*new\\s+$TypeName""".r

  /** `a.Sources.All.foreach(source => ...)`: an instance binding of `source` binds a value `a.Sources` holds. */
  private val EachInstance = s"""(?<=[^\\w.])${TypeName}\\s*\\.\\s*foreach\\s*\\(\\s*(\\w+)\\s*=>""".r

  /** The tokens of a source in order, an overlapping later match dropped. */
  private def tokens(text: String): Seq[Token] = {
    val matches = Tokens.flatMap { case (regex, token) =>
      regex.findAllMatchIn(text).map(m => (m.start, m.end, token(m)))
    }
    matches
      .sortBy { case (start, end, _) => (start, -end) }
      .foldLeft((Vector.empty[Token], 0)) { case ((acc, until), (start, end, token)) =>
        if (start < until) (acc, until) else (acc :+ token, end)
      }
      ._1
  }

  private final case class Chain(key: Option[String], targets: Vector[String] = Vector.empty, eager: Boolean = false)

  /** The bindings read so far: the chains in order, the set binders by variable, and the set binder
    * just declared, which a chained `addBinding()` adds to.
    */
  private final case class Reading(
    chains: Vector[Chain] = Vector.empty,
    byName: Set[String] = Set.empty,
    sets: Map[String, String] = Map.empty,
    lastSet: Option[String] = None,
  ) {
    def start(chain: Chain, set: Option[String] = None): Reading = copy(chains = chains :+ chain, lastSet = set)
    def amend(f: Chain => Chain): Reading =
      if (chains.isEmpty) this else copy(chains = chains.init :+ f(chains.last), lastSet = None)

    def read(token: Token): Reading = token match {
      case SetVal(name, key) => copy(sets = sets + (name -> key)).start(Chain(Some(key)), Some(key))
      case NewSet(key) => start(Chain(Some(key)), Some(key))
      case AddTo(Some(set)) => start(Chain(sets.get(set)))
      case AddTo(None) => start(Chain(lastSet))
      case Bind(key) => start(Chain(Some(key)))
      case BindByName(classes) => copy(byName = byName ++ classes).start(Chain(None))
      case BindFactory(actor, factory) => start(Chain(Some(factory), Vector(actor)))
      case Target(name) => amend(c => c.copy(targets = c.targets :+ name))
      case ToSelf => amend(c => c.copy(targets = c.targets ++ c.key.toSeq))
      case Eager => amend(c => c.copy(eager = true))
    }
  }

  /** What a Guice module's source binds ([[Bindings]]); empty for a source declaring no module.
    *
    * Read off the text in order: a binding starts at `bind(classOf[T])`, `bind[T]`, a set binder's
    * `newSetBinder(binder(), classOf[T])` or an `addBinding()` on one, and every `.to`, `.toProvider`,
    * `.toInstance`, `.toSelf` and `.asEagerSingleton()` after it belongs to it until the next one
    * starts. An `addBinding()` whose set it cannot tell binds a key nobody is told of, so its target
    * is left unaccounted for. An instance held in a local `val` constructed with `new` binds that
    * class, and one a `foreach` hands over binds what the collection it walks belongs to.
    */
  def bindingsIn(sourceText: String): Bindings = {
    val text = Comment.replaceAllIn(sourceText, " ")
    if (ModuleDeclaration.findFirstIn(text).isEmpty) Bindings.empty
    else {
      val reading = tokens(text).foldLeft(Reading())(_.read(_))
      val (eagerChains, typedChains) = reading.chains.partition(_.eager)
      val constructed = LocalInstance.findAllMatchIn(text).map(m => m.group(1) -> m.group(2)).toMap ++
        EachInstance.findAllMatchIn(text).map(m => m.group(2) -> m.group(1)).toMap
      Bindings(
        keys = reading.chains.flatMap(_.key).toSet,
        typed = typedChains
          .flatMap(c => c.key.toSeq.flatMap(key => c.targets.map(t => key -> constructed.getOrElse(t, t))))
          .toSet,
        byName = reading.byName ++ eagerChains.flatMap(c => c.key.toSeq ++ c.targets),
      )
    }
  }

  sealed trait Selection {
    def runs(spec: String): Boolean
  }

  /** The specs reaching a changed class, and, when the change reaches a class an application loads
    * when it boots (`entry`, one of [[Inputs.bootEntries]] or a changed module), every spec booting one;
    * or, when it reaches a router read and no such class (`entry` the router, `routed`), every spec
    * requesting a route ([[Inputs.requesting]]).
    */
  final case class Specs(
    specs: Set[String],
    changedClasses: Int,
    reached: Int,
    entry: Option[String] = None,
    routed: Boolean = false,
  ) extends Selection {
    def runs(spec: String): Boolean = specs.contains(Graph.normalize(spec))
  }

  /** Every spec, and why the class-level answer could not be trusted. */
  final case class Whole(reason: String) extends Selection {
    def runs(spec: String): Boolean = true
  }

  /** What `ChangedPathsEnvVar`'s file says. */
  final case class Changes(paths: Seq[String], whole: Set[String])

  /** One path a line, or `whole <subproject>`; blank lines and surrounding space are dropped. */
  def parseChanged(text: String): Changes = {
    val lines = text.split("\n").toSeq.map(_.trim).filter(_.nonEmpty).distinct
    val (whole, paths) = lines.partition(_.startsWith("whole "))
    Changes(paths, whole.map(_.stripPrefix("whole ").trim).toSet)
  }

  private def under(path: String, dir: String): Boolean =
    dir.nonEmpty && (path == dir || path.startsWith(dir.stripSuffix("/") + "/"))

  /** Which of `in.specs` the changes can reach. */
  def select(changes: Changes, in: Inputs): Selection =
    if (changes.whole.contains(in.project)) Whole("its tests read a changed file")
    else selectPaths(changes.paths, in)

  private def selectPaths(changed: Seq[String], in: Inputs): Selection = {
    val outside = changed.find(path => !in.projectDirs.exists(under(path, _)))
    val relevant =
      changed.filter(path => in.visibleDirs.exists(under(path, _)) && !in.hiddenDirs.exists(under(path, _)))
    val compiled = relevant.map(path => path -> compiledFrom(path, in.graph))
    (outside, compiled.collectFirst { case (path, None) => path }) match {
      case (Some(path), _) => Whole(s"$path is under no subproject")
      case (_, Some(path)) => Whole(s"$path is not a source zinc compiled")
      case _ =>
        val changedClasses = compiled.flatMap(_._2).flatten.flatMap(in.graph.sources).toSet
        val reached = in.wired.dependents(changedClasses)
        val specs = in.specs.map(Graph.normalize).filter(reached.contains)
        (
          reached.intersect(in.jvmEntries).toSeq.sorted.headOption,
          changedClasses
            .intersect(in.modules.keySet)
            .toSeq
            .sorted
            .headOption
            .orElse(reached.intersect(in.bootEntries).toSeq.sorted.headOption)
        ) match {
          case (Some(entry), _) => Whole(s"reaches $entry, which the JVM loads by name")
          case (None, Some(entry)) => Specs(specs ++ in.booting, changedClasses.size, reached.size, Some(entry))
          case (None, None) =>
            reached.intersect(in.routing.routers).toSeq.sorted.headOption match {
              case Some(router) =>
                Specs(specs ++ in.requesting, changedClasses.size, reached.size, Some(router), routed = true)
              case None => Specs(specs, changedClasses.size, reached.size)
            }
        }
    }
  }

  /** Every class an application loads when it boots, and every router read, that `changes` reach,
    * sorted: what [[select]] names the first of, for a measurement to say what else a change would
    * still widen on.
    */
  def entriesReached(changes: Changes, in: Inputs): Seq[String] = {
    val compiled = changes.paths.flatMap(path => compiledFrom(path, in.graph)).flatten
    val changedClasses = compiled.flatMap(in.graph.sources).toSet
    val reached = in.wired.dependents(changedClasses)
    (changedClasses.intersect(in.modules.keySet) ++ reached.intersect(
      in.bootEntries ++ in.routing.routers
    )).toSeq.sorted
  }

  /** The compiled sources a changed path stands for: itself when zinc compiled it, and for a Play
    * routes file every source the routes compiler wrote for its subproject
    * (`<subproject>/target/.../routes/main/`), the router among them. None for anything else.
    */
  def compiledFrom(path: String, graph: Graph): Option[Set[String]] =
    if (graph.sources.contains(path)) Some(Set(path))
    else if ((path.endsWith(".routes") || path.endsWith("/routes")) && path.contains("/conf/")) {
      val generated = path.split('/').head + "/target/"
      Some(graph.sources.keySet.filter(p => p.startsWith(generated) && p.contains("/routes/main/"))).filter(_.nonEmpty)
    } else None

  /** The line `ci/build.slow.sh`'s log carries for each subproject the suite filtered. */
  def line(project: String, selection: Selection, total: Int): String = selection match {
    case Specs(specs, changed, reached, None, _) =>
      s"ci-impact: $project class ${specs.size} of $total specs ($changed changed classes reach $reached)"
    case Specs(specs, changed, reached, Some(router), true) =>
      s"ci-impact: $project class ${specs.size} of $total specs ($changed changed classes reach $reached, " +
        s"and $router, which routes every request, so every spec requesting a route runs)"
    case Specs(specs, changed, reached, Some(entry), false) =>
      s"ci-impact: $project class ${specs.size} of $total specs ($changed changed classes reach $reached, " +
        s"and $entry, which an application loads when it boots, so every spec booting one runs)"
    case Whole(reason) => s"ci-impact: $project whole $total specs ($reason)"
  }

  /** Every dotted name in a text: `play.modules.enabled += "core.modules.CoreModule"` names
    * `core.modules.CoreModule` (and `play.modules.enabled`, which names no class here).
    */
  private val DottedName = """[A-Za-z_][A-Za-z0-9_$]*(?:\.[A-Za-z_][A-Za-z0-9_$]*)+""".r

  def dottedNames(text: String): Set[String] = DottedName.findAllIn(text).map(Graph.normalize).toSet

  private val ClassAttribute = """class\s*=\s*"([^"]+)"""".r

  /** The names a resource file has an APPLICATION load: a `.conf` or `.properties` file's settings,
    * comment lines dropped — Play's and Pekko's (`play.modules.enabled`, `play.http.errorHandler`,
    * an actor's class). A routes file is not read: the router it compiles to names its controllers
    * in code, and the router is one of [[ConventionalEntries]].
    */
  def appNamedIn(path: String, text: String): Set[String] = {
    val name = path.split('/').last
    if (name.endsWith(".conf") || name.endsWith(".properties"))
      dottedNames(
        text.split("\n").filterNot(line => line.trim.startsWith("#") || line.trim.startsWith("//")).mkString("\n")
      )
    else Set.empty
  }

  /** A resource file an application reads as configuration ([[appNamedIn]]).
    *
    * @param resource
    *   its name on the classpath: its path under the resource directory holding it
    * @param names
    *   what it has an application load ([[appNamedIn]])
    * @param includes
    *   what its `include` lines name, as written
    */
  final case class ConfigFile(resource: String, names: Set[String], includes: Set[String])

  private val Include =
    """(?m)^\s*include\s+(?:required\s*\(\s*)?(?:(?:classpath|file|url)\s*\(\s*)?"([^"]+)"""".r

  def configFile(resource: String, text: String): ConfigFile = {
    val settings = text.split("\n").filterNot(l => l.trim.startsWith("#") || l.trim.startsWith("//")).mkString("\n")
    ConfigFile(resource, appNamedIn(resource, text), Include.findAllMatchIn(settings).map(_.group(1)).toSet)
  }

  /** What `-Dconfig.resource=<name>` hands a test task's application when it is set: that file and
    * everything it includes, and every `reference.conf`, which Typesafe config always reads. Every
    * other `.conf` on the classpath is not read: `application.conf` gives way to the named resource,
    * and another subproject's `<subproject>.test.conf` is that subproject's alone. So a module only
    * `api`'s `application.conf` enables, or only `integrations.test.conf`, is no entry of `api`'s
    * specs, which boot with `api.test.conf`.
    *
    * A `.properties` file other than `application.properties` is read whatever is set, since
    * something other than Typesafe config may load it. Without a named resource, or with one, or an
    * include, no file answers for, every file is read, as the application would be handed an
    * unknown set.
    */
  def configNamed(files: Seq[ConfigFile], configResource: Option[String]): Set[String] = {
    val all = files.flatMap(_.names).toSet
    def found(name: String, from: String): Seq[ConfigFile] = {
      val dir = from.split('/').init.mkString("/")
      val candidates = Seq(name) ++ Seq(".conf", ".json", ".properties").filterNot(name.endsWith).map(name + _)
      val paths = candidates.flatMap(c => Seq(c, if (dir.isEmpty) c else s"$dir/$c")).distinct
      files.filter(f => paths.contains(f.resource))
    }
    configResource match {
      case None => all
      case Some(resource) =>
        val roots = files.filter(f => f.resource == resource || f.resource.split('/').last == "reference.conf")
        @annotation.tailrec
        def closure(pending: List[ConfigFile], seen: Set[ConfigFile]): Option[Set[ConfigFile]] = pending match {
          case Nil => Some(seen)
          case head :: tail =>
            val included = head.includes.toSeq.map(found(_, head.resource))
            if (included.exists(_.isEmpty)) None
            else {
              val next = included.flatten.filterNot(seen.contains)
              closure(next.toList ++ tail, seen ++ next)
            }
        }
        val read = if (roots.exists(_.resource == resource)) closure(roots.toList, roots.toSet) else None
        read match {
          case None => all
          case Some(loaded) =>
            val properties = files.filter { f =>
              f.resource.endsWith(".properties") && f.resource.split('/').last != "application.properties"
            }
            (loaded.toSeq ++ properties).flatMap(_.names).toSet
        }
    }
  }

  /** The `-Dconfig.resource=` a test task's JVM is started with. */
  def configResourceIn(javaOptions: Seq[String]): Option[String] =
    javaOptions.reverse.collectFirst {
      case o if o.startsWith("-Dconfig.resource=") => o.stripPrefix("-Dconfig.resource=").trim
    }

  /** The names a resource file has the JVM load for any spec: an `.xml` file's `class="..."`
    * attributes (logback's appenders and encoders, not the logger names beside them, which load
    * nothing) and a `META-INF/services/` file's `ServiceLoader` implementations. Anything else
    * (json, sql, templates) loads nothing.
    */
  def jvmNamedIn(path: String, text: String): Set[String] = {
    val name = path.split('/').last
    if (path.contains("META-INF/services/")) dottedNames(text)
    else if (name.endsWith(".xml")) ClassAttribute.findAllMatchIn(text).flatMap(m => dottedNames(m.group(1))).toSet
    else Set.empty
  }

  /** The configurations of a dependency that one configuration's classpath holds, from a
    * `dependsOn` mapping: `"compile->compile;test->test"` from `test` is `compile` and `test`, and a
    * bare `"test"` (`% Test`) is `test->compile`. `test` extends `runtime` and `compile`, so a
    * mapping from either of those reaches it too. Only `compile` and `test` are answered: `runtime`
    * and `default` are read as `compile`, whose classes they hold.
    */
  def dependencyConfigs(mapping: Option[String], from: String): Set[String] = {
    val froms = if (from == "test") Set("test", "runtime", "compile") else Set(from)
    def normalizeConfig(c: String): Option[String] = c.trim match {
      case "test" => Some("test")
      case "compile" | "runtime" | "default" | "" => Some("compile")
      case other if other.startsWith("default(") => Some("compile")
      case _ => None
    }
    mapping
      .getOrElse("compile->compile")
      .split(";")
      .toSeq
      .flatMap { part =>
        part.split("->", 2).toSeq match {
          case Seq(f, to) if froms.contains(f.trim) => to.split(",").toSeq.flatMap(normalizeConfig)
          case Seq(f) if froms.contains(f.trim) => Seq("compile")
          case _ => Nil
        }
      }
      .toSet
  }
}
