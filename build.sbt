// scala-build: what every Scala build on this fleet shares, published to the Maven repository at
// maven.bryzek.com and pinned by version in each consumer (ISS-53963). The repository was lib-build
// (design DES-102458, "Build tooling: scala-build"); `lib-build-sbt` and `lib-build-testkit` stay
// resolvable at their last version, 0.0.9.
//
// Three artifacts, released together by scala-build's rollout (`dev rollout --app scala-build`):
//
//   - `scala-build-sbt` (Scala 2.12, the meta-build's Scala): the security pins (`BryzekPins`), the
//     shared lib-* settings (`BryzekLibSettings`) and the test-health plugins (`cilists`). Every
//     Scala build adds it to `project/plugins.sbt`:
//
//       libraryDependencies += "com.bryzek" %% "scala-build-sbt" % "<version>"
//
//     A plain library and not `sbtPlugin := true`, whose `_2.12_1.0` artifact name the library
//     release does not publish; its jar carries the `sbt/sbt.autoplugins` descriptor itself, which
//     is how sbt finds an AutoPlugin on the meta-build's classpath (`AutoPluginDescriptorSpec`).
//
//   - `scala-build-app` (Scala 2.12): what a Play application's build runs beside those, the code
//     platform and playbook each kept a copy of under `project/`: per-subproject test databases and
//     shards, the compile cache, test impact, the fork-safe test framework's wiring and the
//     registries generated from source. Its plugins trigger on every project (two of them on every
//     `PlayScala` one), so it is an artifact of its own: a library that adds `scala-build-sbt` gains
//     none of them, and needs no Play sbt-plugin on its meta-build.
//
//   - `scala-build-testkit` (Scala 3): the pin suites, each an abstract suite a build runs by
//     declaring a one-line subclass in its own tests, and the fork-safe ScalaTest framework and
//     java agent `scala-build-app` attaches to every forked test JVM. Every dependency is
//     `Provided`, so a suite exercises the consumer's own resolution.

ThisBuild / version := "0.0.14"

// Publishing coordinates and metadata, the guava pin and the browser-stack exclusions, from this
// repository's own sources (project/build.sbt compiles `sbt/` into the meta-build). Named for the
// repository as GitHub holds it.
BryzekLibSettings.buildSettings("scala-build")

ThisBuild / scalaVersion := "3.8.4"

ThisBuild / resolvers += "Bryzek Maven repository" at "https://maven.bryzek.com"

ThisBuild / dependencyOverrides ++= BryzekPins.jackson2 ++ BryzekPins.jackson3 ++ BryzekPins.logback ++
  BryzekPins.jline ++ BryzekPins.log4j

// Each subproject's JUnit XML lands in its own target/test-reports, which `# ci-test-reports:` in
// ci/build.fast.sh names.
lazy val testReports = Seq(
  testOptions ++= Seq(
    Tests.Argument("-oDF"),
    Tests.Argument(TestFrameworks.ScalaTest, "-u", (target.value / "test-reports").getAbsolutePath)
  )
)

lazy val sbtModule = project
  .in(file("sbt"))
  .settings(testReports)
  .settings(
    name := "scala-build-sbt",
    // The Scala the running sbt is built on, which is the Scala every consumer's meta-build
    // compiles with. Read from sbt rather than written here, so build.sbt states one literal
    // scalaVersion (the Scala 3 one) and an sbt bump moves this with it.
    scalaVersion := appConfiguration.value.provider.scalaProvider.version,
    crossScalaVersions := Seq(scalaVersion.value),
    scalafmtOnCompile := true,
    // The sources use `import sbt.*` and `Setting[?]`, the spelling Scala 2.12 and 3 share.
    scalacOptions ++= Seq("-feature", "-deprecation", "-Xsource:3", "-Xfatal-warnings"),
    Compile / packageDoc / mappings := Seq(),
    Compile / packageDoc / publishArtifact := true,
    libraryDependencies ++= Seq(
      // The oldest sbt a consumer runs (play-core). Provided: the consumer's own sbt is what loads it.
      "org.scala-sbt" % "sbt" % "1.12.11" % Provided,
      // `BryzekLibSettings.rootSettings` sets `scalafmtOnCompile`; every consumer adds sbt-scalafmt.
      Defaults.sbtPluginExtra("org.scalameta" % "sbt-scalafmt" % "2.6.1", "1.0", "2.12") % Provided,
      "org.scalatest" %% "scalatest" % "3.2.20" % Test
    )
  )

// The sbt the applications that add `scala-build-app` run (platform, playbook), which is what its
// plugins compile against: `CompileCachePlugin` and `SbtExternalHooks` reach sbt internals.
val appSbtVersion = "1.13.1"

lazy val app = project
  .in(file("app"))
  .dependsOn(sbtModule)
  .settings(testReports)
  .settings(
    name := "scala-build-app",
    scalaVersion := appConfiguration.value.provider.scalaProvider.version,
    crossScalaVersions := Seq(scalaVersion.value),
    scalafmtOnCompile := true,
    scalacOptions ++= Seq("-feature", "-deprecation", "-Xsource:3"),
    Compile / packageDoc / mappings := Seq(),
    Compile / packageDoc / publishArtifact := true,
    // `ScalaBuildVersion`: the release this jar is and the testkit published beside it, which
    // `ForkSafeTestFrameworkPlugin` adds to every Play subproject's Test classpath.
    Compile / sourceGenerators += Def.task {
      val out = (Compile / sourceManaged).value / "ScalaBuildVersion.scala"
      IO.write(
        out,
        s"""/** The scala-build release this jar is, generated by scala-build's build.sbt. */
           |object ScalaBuildVersion {
           |  val version: String = "${version.value}"
           |  val testkitName: String = "${(testkit / name).value}"
           |}
           |""".stripMargin
      )
      Seq(out)
    }.taskValue,
    libraryDependencies ++= Seq(
      "org.scala-sbt" % "sbt" % appSbtVersion % Provided,
      Defaults.sbtPluginExtra("org.playframework" % "sbt-plugin" % "3.0.12", "1.0", "2.12") % Provided,
      // `TestDatabases` drives the driver directly. Provided: each application's meta-build declares
      // it at the version its own `core` uses, so the build and the application never resolve two.
      "org.postgresql" % "postgresql" % "42.7.14" % Provided,
      "org.scalatest" %% "scalatest" % "3.2.20" % Test
    )
  )

// The app module's sources that hold no sbt type, compiled again by `testkit` so a spec asserts the
// code a build runs rather than a copy of it.
lazy val appSources = file("app") / "src" / "main" / "scala"

lazy val testkit = project
  .in(file("testkit"))
  .settings(BryzekLibSettings.rootSettings)
  .settings(
    name := "scala-build-testkit",
    libraryDependencies ++= (Seq(
      "org.scalatest" %% "scalatest" % "3.2.20",
      // The `sbt.testing` interfaces `ForkSafeScalaTestFramework` implements.
      "org.scala-sbt" % "test-interface" % "1.0",
      "net.logstash.logback" % "logstash-logback-encoder" % "9.0",
      "org.playframework" %% "play-json" % "3.0.6"
    ) ++ BryzekPins.jackson2 ++ BryzekPins.jackson3 ++ BryzekPins.logback).map(_ % Provided),
    libraryDependencies ++= Seq(
      // `sbt.ForkMain`, the forked-test agent sbt runs, which `ForkSafeExitAgentSpec` starts as a
      // real subprocess: pinned to the sbt an application runs, the ForkMain its forks get.
      "org.scala-sbt" % "test-agent" % appSbtVersion % Test,
      "org.postgresql" % "postgresql" % "42.7.14" % Test
    ),
    // What makes this jar usable as `-javaagent`; it is the plain Test library otherwise.
    Compile / packageBin / packageOptions +=
      Package.ManifestAttributes("Premain-Class" -> "testkit.ForkSafeExitAgent"),
    // How a forked test JVM clones its own lane database (`testkit.ShardDatabase`), compiled from
    // the same file `scala-build-app` drops those lanes with, so the two cannot disagree on a name.
    Compile / unmanagedSources += appSources / "testshards" / "LaneClone.scala",
    // Unforked: the fork-safe specs drive sbt's and ScalaTest's fork plumbing by hand.
    Test / fork := false,
    Test / unmanagedSources ++= Seq(
      appSources / "testshards" / "TestShards.scala",
      appSources / "testshards" / "LastGroup.scala",
      appSources / "testimpact" / "TestImpact.scala",
      appSources / "copiedresources" / "CopiedResources.scala",
      appSources / "cacheentries" / "CacheEntries.scala",
      appSources / "TestDatabases.scala"
    )
  )

lazy val root = project
  .in(file("."))
  .aggregate(sbtModule, app, testkit)
  .settings(
    publish / skip := true
  )
