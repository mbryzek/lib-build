package testkit

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.must.Matchers
import testimpact.TestImpact
import testimpact.TestImpact.{Graph, Inputs, Specs, Whole}

/** Which specs a change reaches by class. scala-build-app's `TestImpact.scala` is compiled into this
  * subproject's tests as well as into the meta-build, so what is asserted here is the code
  * `TestImpactPlugin` filters every test task with.
  */
class TestImpactSpec extends AnyFunSpec with Matchers {

  // core: Clock <- Billing <- BillingSpec; Clock <- ClockSpec; Mailer stands alone with MailerSpec.
  // api: Invoices -> core.Billing; InvoicesSpec -> api.Invoices. The router names Invoices.
  private val graph = Graph.of(
    sources = Seq(
      "core/app/core/Clock.scala" -> Set("core.Clock", "core.Clock$"),
      "core/app/core/Billing.scala" -> Set("core.Billing"),
      "core/app/core/Mailer.scala" -> Set("core.Mailer"),
      "core/test/core/ClockSpec.scala" -> Set("core.ClockSpec"),
      "core/test/core/BillingSpec.scala" -> Set("core.BillingSpec"),
      "core/test/core/MailerSpec.scala" -> Set("core.MailerSpec"),
      "api/app/api/Invoices.scala" -> Set("api.Invoices"),
      "api/test/api/InvoicesSpec.scala" -> Set("api.InvoicesSpec"),
    ),
    dependsOn = Seq(
      "core.Billing" -> "core.Clock$",
      "core.ClockSpec" -> "core.Clock",
      "core.BillingSpec" -> "core.Billing",
      "core.MailerSpec" -> "core.Mailer",
      "api.Invoices" -> "core.Billing",
      "api.InvoicesSpec" -> "api.Invoices",
    ),
  )

  private val core = Inputs(
    project = "core",
    graph = graph,
    named = Set.empty,
    loaded = Set.empty,
    booters = Set.empty,
    specs = Set("core.ClockSpec", "core.BillingSpec", "core.MailerSpec"),
    visibleDirs = Seq("core"),
    hiddenDirs = Nil,
    projectDirs = Seq("core", "api", "testkit"),
  )

  private val api = core.copy(
    project = "api",
    specs = Set("api.InvoicesSpec"),
    visibleDirs = Seq("core", "api"),
  )

  private def changed(paths: String*): TestImpact.Changes = TestImpact.Changes(paths, Set.empty)

  describe("select") {

    it("runs every spec of a subproject named whole, whatever the classes say") {
      TestImpact.select(TestImpact.Changes(Seq("core/app/core/Mailer.scala"), Set("api")), api) mustBe
        Whole("its tests read a changed file")
      TestImpact.select(TestImpact.Changes(Seq("core/app/core/Mailer.scala"), Set("api")), core) mustBe
        Specs(Set("core.MailerSpec"), 1, 2)
    }

    it("keeps the specs whose classes reach a changed class, transitively") {
      TestImpact.select(changed("core/app/core/Clock.scala"), core) mustBe
        Specs(Set("core.ClockSpec", "core.BillingSpec"), changedClasses = 1, reached = 6)
      TestImpact.select(changed("core/app/core/Mailer.scala"), core) mustBe
        Specs(Set("core.MailerSpec"), changedClasses = 1, reached = 2)
    }

    it("follows the edges into a subproject built on the changed one") {
      TestImpact.select(changed("core/app/core/Clock.scala"), api) mustBe
        Specs(Set("api.InvoicesSpec"), changedClasses = 1, reached = 6)
      TestImpact.select(changed("core/app/core/Mailer.scala"), api) mustBe Specs(Set.empty, 1, 2)
    }

    it("keeps a changed spec") {
      TestImpact.select(changed("core/test/core/MailerSpec.scala"), core) mustBe Specs(Set("core.MailerSpec"), 1, 1)
    }

    it("selects nothing when no changed path is on the subproject's test classpath") {
      TestImpact.select(changed("api/app/api/Invoices.scala"), core) mustBe Specs(Set.empty, 0, 0)
    }

    it("runs every spec when a visible path is not a compiled source") {
      TestImpact.select(changed("core/app/core/Mailer.scala", "core/conf/base.conf"), api) mustBe
        Whole("core/conf/base.conf is not a source zinc compiled")
      TestImpact.select(changed("core/app/core/Deleted.scala"), core) mustBe
        Whole("core/app/core/Deleted.scala is not a source zinc compiled")
    }

    it("ignores a path under a directory the subproject's test classpath does not hold") {
      val compileOnly = api.copy(hiddenDirs = Seq("core/test"))
      TestImpact.select(changed("core/test/core/fixtures/data.json"), compileOnly) mustBe Specs(Set.empty, 0, 0)
    }

    it("runs every spec when a path is under no subproject") {
      TestImpact.select(changed("build.sbt"), core) mustBe Whole("build.sbt is under no subproject")
    }

    // core.MailerSpec extends a helper that boots an application; the others boot nothing.
    val booting = Graph.of(
      sources = Seq("core/test/core/AppSpec.scala" -> Set("core.AppSpec")),
      dependsOn = Seq("core.MailerSpec" -> "core.AppSpec"),
    )
    val booted = core.copy(graph = graph ++ booting, booters = Set("core.AppSpec"))

    it("adds every spec booting an application when the change reaches a class configuration has one load") {
      TestImpact.select(changed("core/app/core/Clock.scala"), booted.copy(named = Set("core.Billing", "a.b"))) mustBe
        Specs(Set("core.ClockSpec", "core.BillingSpec", "core.MailerSpec"), 1, 6, Some("core.Billing"))
      TestImpact.select(changed("core/app/core/Mailer.scala"), booted.copy(named = Set("core.Billing"))) mustBe
        Specs(Set("core.MailerSpec"), 1, 2)
    }

    it("treats the classes Play loads by convention as named") {
      val routed = Graph.of(
        sources = Seq("core/app/router/Routes.scala" -> Set("router.Routes")),
        dependsOn = Seq("router.Routes" -> "core.Billing"),
      )
      TestImpact.select(changed("core/app/core/Clock.scala"), booted.copy(graph = booted.graph ++ routed)) mustBe
        Specs(Set("core.ClockSpec", "core.BillingSpec", "core.MailerSpec"), 1, 7, Some("router.Routes"))
    }

    it("follows a changed routes file through the router sources Play compiled from it") {
      val routed = Graph.of(
        sources = Seq("core/target/scala-3.8.4/routes/main/router/Routes.scala" -> Set("router.Routes")),
        dependsOn = Seq("router.Routes" -> "core.Billing"),
      )
      TestImpact.select(changed("core/conf/routes"), booted.copy(graph = booted.graph ++ routed)) mustBe
        Specs(Set("core.MailerSpec"), 1, 1, Some("router.Routes"))
      TestImpact.select(changed("api/conf/routes"), api) mustBe Whole("api/conf/routes is not a source zinc compiled")
    }

    // core.Module, which configuration names, binds core.Port to core.PortImpl and the actor
    // core.Ticker by name, and reads core.Consts while it configures. core.PortSpec asks for a Port.
    val module = Graph.of(
      sources = Seq(
        "core/app/core/Module.scala" -> Set("core.Module"),
        "core/app/core/Port.scala" -> Set("core.Port", "core.PortImpl"),
        "core/app/core/Ticker.scala" -> Set("core.Ticker"),
        "core/app/core/Consts.scala" -> Set("core.Consts"),
        "core/test/core/PortSpec.scala" -> Set("core.PortSpec"),
      ),
      dependsOn = Seq(
        "core.Module" -> "core.Port",
        "core.Module" -> "core.PortImpl",
        "core.Module" -> "core.Ticker",
        "core.Module" -> "core.Consts",
        "core.PortImpl" -> "core.Port",
        "core.PortImpl" -> "core.Clock",
        "core.Ticker" -> "core.Mailer",
        "core.PortSpec" -> "core.Port",
      ),
    )
    val modular = booted.copy(
      graph = booted.graph ++ module,
      named = Set("core.Module"),
      specs = booted.specs + "core.PortSpec",
      bindings = Map(
        "core/app/core/Module.scala" -> TestImpact.Bindings(
          keys = Set("Port"),
          typed = Set("Port" -> "PortImpl"),
          byName = Set("Ticker"),
        )
      ),
    )

    it("follows a module's typed binding to whoever depends on its key, rather than every booting spec") {
      TestImpact.select(changed("core/app/core/Clock.scala"), modular) mustBe
        Specs(Set("core.ClockSpec", "core.BillingSpec", "core.PortSpec"), 1, 10)
      modular.bootEntries mustBe Set("core.Ticker", "core.Consts")
    }

    it("still adds every booting spec when the change reaches a class the module binds by name") {
      TestImpact.select(changed("core/app/core/Mailer.scala"), modular) mustBe
        Specs(Set("core.MailerSpec"), 1, 4, Some("core.Ticker"))
    }

    it("still adds every booting spec when the change reaches a class the module reads but binds nothing to") {
      TestImpact.select(changed("core/app/core/Consts.scala"), modular).asInstanceOf[Specs].entry mustBe
        Some("core.Consts")
    }

    it("still adds every booting spec when the module's own source changes") {
      TestImpact.select(changed("core/app/core/Module.scala"), modular) mustBe
        Specs(Set("core.MailerSpec"), 1, 1, Some("core.Module"))
    }

    it("leaves a binding whose names do not resolve to one class unaccounted for, its key included") {
      val unresolved = modular.copy(bindings =
        Map("core/app/core/Module.scala" -> TestImpact.Bindings(Set("Port"), Set("Port" -> "Gone"), Set("Ticker")))
      )
      TestImpact.select(changed("core/app/core/Clock.scala"), unresolved) mustBe
        Specs(Set("core.ClockSpec", "core.BillingSpec", "core.MailerSpec"), 1, 8, Some("core.PortImpl"))
      unresolved.bootEntries mustBe Set("core.Port", "core.PortImpl", "core.Ticker", "core.Consts")
    }

    it("reads a value of an object as the object") {
      val instance = modular.copy(bindings =
        Map(
          "core/app/core/Module.scala" -> TestImpact.Bindings(
            Set("Port"),
            Set("Port" -> "core.PortImpl.Default"),
            Set("Ticker")
          )
        )
      )
      instance.bootEntries mustBe Set("core.Ticker", "core.Consts")
    }

    it("runs every spec when the change reaches a class the JVM loads by name") {
      TestImpact.select(changed("core/app/core/Clock.scala"), booted.copy(loaded = Set("core.Billing"))) mustBe
        Whole("reaches core.Billing, which the JVM loads by name")
    }
  }

  describe("a router") {

    // api's router names InvoicesController and UsersController, and reads Binders for its parameters.
    // Every *Spec but PlainSpec boots an application through AppSpec: InvoicesSpec requests an invoice
    // through the generated client, UsersSpec a user by the path it writes, and BootSpec nothing.
    val router = "api/target/scala-3.8.4/routes/main/router/Routes.scala"
    val graph = Graph.of(
      sources = Seq(
        router -> Set("router.Routes", "router.Routes$"),
        "api/app/api/InvoicesController.scala" -> Set("api.InvoicesController"),
        "api/app/api/UsersController.scala" -> Set("api.UsersController"),
        "api/app/api/Invoices.scala" -> Set("api.Invoices"),
        "api/app/api/Users.scala" -> Set("api.Users"),
        "api/app/api/Binders.scala" -> Set("api.Binders"),
        "api/test/api/AppSpec.scala" -> Set("api.AppSpec"),
        "api/test/api/InvoicesSpec.scala" -> Set("api.InvoicesSpec"),
        "api/test/api/UsersSpec.scala" -> Set("api.UsersSpec"),
        "api/test/api/BootSpec.scala" -> Set("api.BootSpec"),
        "api/test/api/PlainSpec.scala" -> Set("api.PlainSpec"),
      ),
      dependsOn = Seq(
        "router.Routes" -> "api.InvoicesController",
        "router.Routes" -> "api.UsersController",
        "router.Routes" -> "api.Binders",
        "api.InvoicesController" -> "api.Invoices",
        "api.UsersController" -> "api.Users",
        "api.InvoicesSpec" -> "api.AppSpec",
        "api.UsersSpec" -> "api.AppSpec",
        "api.BootSpec" -> "api.AppSpec",
      ),
    )
    val routes = TestImpact
      .routesFile(
        "routes",
        """# The application's routes
          |GET     /invoices          @api.InvoicesController.getInvoices()
          |GET     /users/:id         api.UsersController.getUserById(id: String)
          |""".stripMargin,
      )
      .toSeq
    def requests(texts: (String, String)*): Map[String, TestImpact.Requests] =
      texts.map { case (path, text) => path -> TestImpact.requestsIn(text) }.toMap
    val in = Inputs(
      project = "api",
      graph = graph,
      named = Set.empty,
      loaded = Set.empty,
      booters = Set("api.AppSpec"),
      specs = Set("api.InvoicesSpec", "api.UsersSpec", "api.BootSpec", "api.PlainSpec"),
      visibleDirs = Seq("api"),
      hiddenDirs = Nil,
      projectDirs = Seq("api"),
      routes = routes,
      requests = requests(
        "api/test/api/InvoicesSpec.scala" -> "class InvoicesSpec extends AppSpec { client.getInvoices() }",
        "api/test/api/UsersSpec.scala" ->
          "class UsersSpec extends AppSpec { ws.url(s\"$serverBaseUrl/users/${user.id}?expand=true\").get() }",
        "api/test/api/BootSpec.scala" -> "class BootSpec extends AppSpec { inject[api.Users] }",
        "api/test/api/PlainSpec.scala" -> "class PlainSpec { val path = \"/users\" }",
      ),
    )

    it("reaches a controller's specs only through the specs requesting its routes") {
      TestImpact.select(changed("api/app/api/Invoices.scala"), in) mustBe Specs(Set("api.InvoicesSpec"), 1, 3)
      TestImpact.select(changed("api/app/api/Users.scala"), in) mustBe Specs(Set("api.UsersSpec"), 1, 3)
      in.bootEntries mustBe Set.empty
    }

    it("adds every booting spec requesting a route when the change reaches the router itself") {
      TestImpact.select(changed("api/app/api/Binders.scala"), in) mustBe
        Specs(Set("api.InvoicesSpec", "api.UsersSpec"), 1, 2, Some("router.Routes"), routed = true)
      TestImpact.select(changed("api/conf/routes"), in) mustBe
        Specs(Set("api.InvoicesSpec", "api.UsersSpec"), 1, 1, Some("router.Routes"), routed = true)
      in.requesting mustBe Set("api.InvoicesSpec", "api.UsersSpec")
      TestImpact.entriesReached(changed("api/app/api/Binders.scala"), in) mustBe Seq("router.Routes")
    }

    it("reaches every controller from a source requesting a path nobody can read, or naming a router") {
      val opaque = in.copy(requests =
        in.requests ++ requests("api/test/api/BootSpec.scala" -> "class BootSpec { wsClient.url(s\"$base$path\") }")
      )
      TestImpact.select(changed("api/app/api/Invoices.scala"), opaque) mustBe
        Specs(Set("api.InvoicesSpec", "api.BootSpec"), 1, 4)
      val naming = in.copy(graph = in.graph ++ Graph.of(Nil, Seq("api.BootSpec" -> "router.Routes")))
      TestImpact.select(changed("api/app/api/Users.scala"), naming) mustBe
        Specs(Set("api.UsersSpec", "api.BootSpec"), 1, 4)
    }

    it("reads a helper requesting a path it is handed as requesting the paths its callers write") {
      val helped = in.copy(
        graph = in.graph ++ Graph.of(
          Seq("api/test/api/HooksSpec.scala" -> Set("api.HooksSpec", "api.HooksSpec.$anon")),
          Seq(
            "api.UsersSpec" -> "api.HooksSpec",
            "api.BootSpec" -> "api.HooksSpec",
            "api.HooksSpec" -> "api.AppSpec",
            "api.HooksSpec" -> "api.HooksSpec.$anon",
            "api.HooksSpec.$anon" -> "api.HooksSpec",
          ),
        ),
        requests = in.requests ++ requests(
          "api/test/api/HooksSpec.scala" -> "trait HooksSpec { def post(path: String) = ws.url(s\"$serverBaseUrl$path\") }",
          "api/test/api/UsersSpec.scala" -> "class UsersSpec extends HooksSpec { post(\"/users/1\") }",
          "api/test/api/BootSpec.scala" -> "class BootSpec extends HooksSpec { post(name) }",
        ),
      )
      TestImpact.select(changed("api/app/api/Users.scala"), helped) mustBe
        Specs(Set("api.UsersSpec", "api.BootSpec"), 1, 4)
      TestImpact.select(changed("api/app/api/Invoices.scala"), helped) mustBe
        Specs(Set("api.InvoicesSpec", "api.BootSpec"), 1, 4)
    }

    it("keeps a router no routes file is read for as an entry an application loads when it boots") {
      TestImpact.select(changed("api/app/api/Invoices.scala"), in.copy(routes = Nil)) mustBe
        Specs(Set("api.InvoicesSpec", "api.UsersSpec", "api.BootSpec"), 1, 3, Some("router.Routes"))
    }

    it("mounts an included router's routes under the prefix it is included at") {
      val sub = TestImpact.routesFile("conf/admin.routes", "GET /users  api.UsersController.getUserById()")
      val root = TestImpact.routesFile(
        "conf/routes",
        "-> /admin admin.Routes\nGET /invoices api.InvoicesController.getInvoices()"
      )
      sub.map(_.router) mustBe Some("admin.Routes")
      root.map(_.includes) mustBe Some(Seq("/admin" -> "admin.Routes"))
      val mounted = in.copy(
        graph = in.graph ++ Graph.of(
          Seq("api/target/scala-3.8.4/routes/main/admin/Routes.scala" -> Set("admin.Routes")),
          Seq("router.Routes" -> "admin.Routes", "admin.Routes" -> "api.UsersController"),
        ),
        routes = (sub ++ root).toSeq,
        requests =
          in.requests ++ requests("api/test/api/UsersSpec.scala" -> "class UsersSpec { ws.url(s\"$b/invoices\") }"),
      )
      mounted.routing.routers mustBe Set("router.Routes", "admin.Routes")
      TestImpact.select(changed("api/app/api/Users.scala"), mounted) mustBe Specs(Set.empty, 1, 2)
      val prefixed = mounted.copy(requests =
        mounted.requests ++ requests(
          "api/test/api/UsersSpec.scala" -> "class UsersSpec { ws.url(s\"$b/admin/users\") }"
        )
      )
      TestImpact.select(changed("api/app/api/Users.scala"), prefixed) mustBe Specs(Set("api.UsersSpec"), 1, 3)
    }
  }

  describe("routesFile") {

    it("reads each route's controller and action, and no file that is not a routes file") {
      TestImpact.routesFile(
        "routes",
        """# comment
          |GET   /a/:id/b    @com.x.controller.Things.getThingById(id: String, n: Int ?= 1)
          |POST  /a          controllers.Assets.at(path = "/public", file = "a.js")
          |->    /           sub.Routes
          |""".stripMargin,
      ) mustBe Some(
        TestImpact.RoutesFile(
          "router.Routes",
          Seq(
            TestImpact.Route("/a/:id/b", "com.x.controller.Things", "getThingById"),
            TestImpact.Route("/a", "controllers.Assets", "at"),
          ),
          Seq("/" -> "sub.Routes"),
        )
      )
      TestImpact.routesFile("application.conf", "GET /a a.B.c()") mustBe None
    }
  }

  describe("requestsIn and pathMatches") {

    it("reads the identifiers, the request paths and whether the source builds a request") {
      val r = TestImpact.requestsIn(
        """// client.getCommented()
          |class S { client.getThings(); ws.url(s"$base/things/${t.id}/parts?x=1").get(); ws.url("http://h:1/u") }""".stripMargin
      )
      r.names must contain("getThings")
      r.names must not contain "getCommented"
      r.paths mustBe Set(s"/things/${TestImpact.Hole}/parts", "/u")
      r.raw mustBe true
      TestImpact.requestsIn("class S { client.getThings() }").raw mustBe false
      TestImpact.requestPath("$base$path") mustBe Some(TestImpact.Hole.toString)
      TestImpact.requestPath("http://localhost:9000/a") mustBe Some("/a")
      TestImpact.requestPath("not a path") mustBe None
    }

    it("matches a written path to a route's segment by segment") {
      val hole = TestImpact.Hole
      TestImpact.pathMatches(s"/things/$hole/parts", "/things/:id/parts") mustBe true
      TestImpact.pathMatches("/things/1/parts", "/things/:id/parts") mustBe true
      TestImpact.pathMatches("/things/1", "/things/:id/parts") mustBe false
      TestImpact.pathMatches("/things/", "/things/:id/parts") mustBe true
      TestImpact.pathMatches("/assets/a/b.js", "/assets/*file") mustBe true
      TestImpact.pathMatches("/other", "/things") mustBe false
    }
  }

  describe("Graph") {

    it("names an object and its class as one") {
      graph.dependents(Set("core.Clock")) must contain("core.Billing")
    }

    it("merges the configurations of a classpath") {
      val more = Graph.of(Seq("core/app/core/Clock.scala" -> Set("core.Tick")), Seq("core.Tick" -> "core.Mailer"))
      val merged = graph ++ more
      merged.sources("core/app/core/Clock.scala") mustBe Set("core.Clock", "core.Tick")
      merged.dependsOn("core.Tick") mustBe Set("core.Mailer")
    }
  }

  describe("bindingsIn") {

    it("reads a Guice module's typed bindings, by whatever name the source writes") {
      TestImpact.bindingsIn(
        """class M extends AbstractModule {
          |  override def configure(): Unit = {
          |    bind(classOf[PlatformDatabase]).to(classOf[DefaultDatabase])
          |    bind(classOf[a.Signer])
          |      .toProvider(classOf[a.SignerProvider])
          |    bind(classOf[Storage]).annotatedWith(Names.named("psql")).to(classOf[PsqlStorage])
          |    bind(classOf[Catalog]).toInstance(a.TestCatalog)
          |    val fakeImages = new FakeImages(clock)
          |    bind(classOf[Images]).toInstance(fakeImages)
          |    bind(classOf[Embeddings]).toInstance(fakeEmbeddings)
          |  }
          |}""".stripMargin
      ) mustBe TestImpact.Bindings(
        keys = Set("PlatformDatabase", "a.Signer", "Storage", "Catalog", "Images", "Embeddings"),
        typed = Set(
          "PlatformDatabase" -> "DefaultDatabase",
          "a.Signer" -> "a.SignerProvider",
          "Storage" -> "PsqlStorage",
          "Catalog" -> "a.TestCatalog",
          "Images" -> "FakeImages",
          "Embeddings" -> "fakeEmbeddings",
        ),
        byName = Set.empty,
      )
    }

    it("reads Play's binding DSL") {
      TestImpact.bindingsIn(
        """class P extends Module {
          |  def bindings(e: Environment, c: Configuration) = Seq(
          |    bind[Sms].to[TwilioSms],
          |    bind[Storage].qualifiedWith("do").to[DoStorage],
          |    bind[Warm].toSelf.eagerly()
          |  )
          |}""".stripMargin
      ) mustBe TestImpact.Bindings(
        keys = Set("Sms", "Storage", "Warm"),
        typed = Set("Sms" -> "TwilioSms", "Storage" -> "DoStorage"),
        byName = Set("Warm"),
      )
    }

    it("reads what is bound by name: actors and eager singletons, whatever they are bound to") {
      TestImpact.bindingsIn(
        """class M extends AbstractModule with PekkoGuiceSupport {
          |  override def configure(): Unit = {
          |    bind(classOf[Shutdown]).asEagerSingleton()
          |    bind(classOf[Keyring]).toProvider(classOf[KeyringProvider]).asEagerSingleton()
          |    bindActor[PeriodicActor]("PeriodicActor")
          |    bindActorFactory[TaskActor, TaskActor.Factory]
          |    bindJournalActor[PeopleHandler](PeopleTable.JournalActorName)
          |  }
          |}""".stripMargin
      ) mustBe TestImpact.Bindings(
        keys = Set("Shutdown", "Keyring", "TaskActor.Factory"),
        typed = Set("TaskActor.Factory" -> "TaskActor"),
        byName = Set("Shutdown", "Keyring", "KeyringProvider", "PeriodicActor", "PeopleHandler"),
      )
    }

    it("reads an actor factory as a binding of the factory to the actor it builds, which boot does not construct") {
      val module = "api/app/api/modules/ApiModule.scala"
      val factoryGraph = Graph.of(
        sources = Seq(
          module -> Set("api.modules.ApiModule"),
          "api/app/api/TaskActor.scala" -> Set("api.TaskActor", "api.TaskActor$", "api.TaskActor.Factory"),
          "api/app/api/Dispatcher.scala" -> Set("api.Dispatcher"),
          "api/app/api/Processors.scala" -> Set("api.Processors"),
          "api/test/api/DispatcherSpec.scala" -> Set("api.DispatcherSpec"),
          "api/test/api/AppSpec.scala" -> Set("api.AppSpec"),
        ),
        dependsOn = Seq(
          "api.modules.ApiModule" -> "api.TaskActor",
          "api.modules.ApiModule" -> "api.TaskActor.Factory",
          "api.TaskActor" -> "api.Processors",
          "api.Dispatcher" -> "api.TaskActor.Factory",
          "api.DispatcherSpec" -> "api.Dispatcher",
        ),
      )
      val in = api.copy(
        graph = factoryGraph,
        named = Set("api.modules.ApiModule"),
        booters = Set("api.AppSpec"),
        specs = Set("api.DispatcherSpec", "api.AppSpec"),
        visibleDirs = Seq("api"),
        bindings = Map(
          module -> TestImpact.bindingsIn(
            "class ApiModule extends AbstractModule with PekkoGuiceSupport { bindActorFactory[TaskActor, TaskActor.Factory] }"
          )
        ),
      )
      in.bootEntries mustBe Set.empty
      TestImpact.select(changed("api/app/api/Processors.scala"), in) mustBe Specs(Set("api.DispatcherSpec"), 1, 6)
    }

    it("reads a set binding through its variable or its chain, and no addBinding whose set it cannot tell") {
      TestImpact.bindingsIn(
        """class M extends AbstractModule {
          |  override def configure(): Unit = {
          |    // bind(classOf[Commented]).to(classOf[Out])
          |    val refs =
          |      com.google.inject.multibindings.Multibinder.newSetBinder(binder(), classOf[References])
          |    com.google.inject.multibindings.Multibinder
          |      .newSetBinder(binder(), classOf[Catalog])
          |      .addBinding()
          |      .toInstance(a.Messages)
          |    refs.addBinding().to(classOf[FileReferences])
          |    a.Health.All.foreach(source => refs.addBinding().toInstance(source))
          |    others.addBinding().to(classOf[Unknown])
          |  }
          |}""".stripMargin
      ) mustBe TestImpact.Bindings(
        keys = Set("References", "Catalog"),
        typed = Set("Catalog" -> "a.Messages", "References" -> "FileReferences", "References" -> "a.Health.All"),
        byName = Set.empty,
      )
    }

    it("reads nothing from a source declaring no module") {
      TestImpact.bindingsIn("class Form { def x = form.bind(data); bind(classOf[A]).to(classOf[B]) }") mustBe
        TestImpact.Bindings.empty
    }
  }

  describe("dottedNames") {

    it("reads the fully qualified names out of configuration") {
      TestImpact.dottedNames("play.modules.enabled += \"core.modules.CoreModule\"\nkey = 3") mustBe
        Set("play.modules.enabled", "core.modules.CoreModule")
    }
  }

  describe("bootsApplication") {

    it("knows a source that starts an application or an injector") {
      TestImpact.bootsApplication("new GuiceApplicationBuilder().build()") mustBe true
      TestImpact.bootsApplication("class X extends AnyFunSpec") mustBe false
    }
  }

  describe("appNamedIn and jvmNamedIn") {

    it("reads the settings of a .conf, not its comments") {
      TestImpact.appNamedIn(
        "core/conf/core.test.conf",
        "# `core.db.SomeSpec` pins this\nplay.modules.enabled += \"core.modules.CoreModule\"\n  // a.b.Gone\n",
      ) mustBe Set("play.modules.enabled", "core.modules.CoreModule")
    }

    it("reads an xml file's class attributes, not its logger names") {
      TestImpact.jvmNamedIn(
        "api/conf/logback.xml",
        "<appender class=\"ch.qos.logback.core.ConsoleAppender\"/><logger name=\"core.db.QueryTiming\"/>",
      ) mustBe Set("ch.qos.logback.core.ConsoleAppender")
    }

    it("reads a ServiceLoader file whole and nothing from a routes or json file") {
      TestImpact.jvmNamedIn("core/conf/META-INF/services/a.Spi", "core.SpiImpl\n") mustBe Set("core.SpiImpl")
      TestImpact.appNamedIn("core/conf/META-INF/services/a.Spi", "core.SpiImpl\n") mustBe Set.empty
      TestImpact.appNamedIn("api/conf/issues.routes", "GET /x controllers.Issues.get") mustBe Set.empty
      TestImpact.jvmNamedIn("api/conf/issues.routes", "GET /x controllers.Issues.get") mustBe Set.empty
      TestImpact.appNamedIn("core/conf/a.json", "{\"type\": \"com.bryzek.models.Thing\"}") mustBe Set.empty
      TestImpact.jvmNamedIn("core/conf/logback.xml", "<logger name=\"a.B\"/>") mustBe Set.empty
    }
  }

  describe("configNamed") {

    val base =
      TestImpact.configFile("base.test.conf", "include \"devtest.conf\"\nplay.http.filters = \"core.Filters\"\n")
    val devtest = TestImpact.configFile("devtest.conf", "include \"base\"\n")
    val baseConf = TestImpact.configFile("base.conf", "pekko.loggers = [\"core.Logger\"]\n")
    val api = TestImpact.configFile(
      "api.test.conf",
      "include \"base.test.conf\"\nplay.modules.enabled += \"core.modules.MockIntegrationsModule\"\n",
    )
    val application = TestImpact.configFile("application.conf", "play.modules.enabled += \"integrations.Module\"\n")
    val integrations = TestImpact.configFile(
      "integrations.test.conf",
      "include \"base.test.conf\"\nplay.modules.enabled += \"integrations.Module\"\n",
    )
    val reference = TestImpact.configFile("reference.conf", "a.b = \"lib.Extension\"\n")
    val quartz = TestImpact.configFile("quartz.properties", "job.class = lib.QuartzJob\n")
    val files = Seq(base, devtest, baseConf, api, application, integrations, reference, quartz)

    it("reads the named resource, what it includes, and every reference.conf, not another root") {
      TestImpact.configNamed(files, Some("api.test.conf")) mustBe Set(
        "base.test.conf",
        "devtest.conf",
        "play.modules.enabled",
        "core.modules.MockIntegrationsModule",
        "play.http.filters",
        "core.Filters",
        "pekko.loggers",
        "core.Logger",
        "a.b",
        "lib.Extension",
        "job.class",
        "lib.QuartzJob",
      )
    }

    it("reads every file when no resource is named, the named one is absent, or an include names nothing") {
      val all = files.flatMap(_.names).toSet
      TestImpact.configNamed(files, None) mustBe all
      TestImpact.configNamed(files, Some("missing.test.conf")) mustBe all
      TestImpact.configNamed(files.filterNot(_ == devtest), Some("api.test.conf")) mustBe all
    }

    it("reads the includes of a file, from a classpath() or required() form, and not from a comment") {
      TestImpact
        .configFile(
          "a.conf",
          "include required(classpath(\"b.conf\"))\n# include \"c.conf\"\ninclude \"d\"\n",
        )
        .includes mustBe Set("b.conf", "d")
    }

    it("takes the last -Dconfig.resource a test task's JVM is given") {
      TestImpact.configResourceIn(
        Seq("-Xss4m", "-Dconfig.resource=core.test.conf", "-Dconfig.resource=api.test.conf")
      ) mustBe
        Some("api.test.conf")
      TestImpact.configResourceIn(Seq("-Xss4m")) mustBe None
    }
  }

  describe("dependencyConfigs") {

    it("follows compile->compile;test->test from both configurations") {
      TestImpact.dependencyConfigs(Some("compile->compile;test->test"), "test") mustBe Set("compile", "test")
      TestImpact.dependencyConfigs(Some("compile->compile;test->test"), "compile") mustBe Set("compile")
    }

    it("reads a bare dependsOn as compile->compile and % Test as test->compile") {
      TestImpact.dependencyConfigs(None, "test") mustBe Set("compile")
      TestImpact.dependencyConfigs(None, "compile") mustBe Set("compile")
      TestImpact.dependencyConfigs(Some("test"), "test") mustBe Set("compile")
      TestImpact.dependencyConfigs(Some("test"), "compile") mustBe Set.empty
    }
  }

  describe("parseChanged") {

    it("reads one path a line, and the subprojects named whole") {
      TestImpact.parseChanged("core/a.scala\n\n  api/b.scala \ncore/a.scala\nwhole api\n") mustBe
        TestImpact.Changes(Seq("core/a.scala", "api/b.scala"), Set("api"))
    }
  }

  describe("line") {

    it("says how many specs ran and why") {
      TestImpact.line("core", Specs(Set("core.ClockSpec"), 1, 4), 3) mustBe
        "ci-impact: core class 1 of 3 specs (1 changed classes reach 4)"
      TestImpact.line("core", Specs(Set("core.ClockSpec"), 1, 4, Some("router.Routes")), 3) mustBe
        "ci-impact: core class 1 of 3 specs (1 changed classes reach 4, and router.Routes, which an application " +
        "loads when it boots, so every spec booting one runs)"
      TestImpact.line("api", Specs(Set("api.ASpec"), 1, 4, Some("router.Routes"), routed = true), 3) mustBe
        "ci-impact: api class 1 of 3 specs (1 changed classes reach 4, and router.Routes, which routes every " +
        "request, so every spec requesting a route runs)"
      TestImpact.line("api", Whole("reaches router.Routes, which configuration names"), 9) mustBe
        "ci-impact: api whole 9 specs (reaches router.Routes, which configuration names)"
    }
  }
}
