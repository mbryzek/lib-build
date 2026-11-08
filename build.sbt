// lib-build: what every Scala build on this fleet shares, published to the Maven repository at
// maven.bryzek.com and pinned by version in each consumer (ISS-53963).
//
// Two artifacts, released together by lib-build's rollout (`dev rollout --app lib-build`):
//
//   - `lib-build-sbt` (Scala 2.12, the meta-build's Scala): the security pins (`BryzekPins`), the
//     shared lib-* settings (`BryzekLibSettings`) and the test-health plugins (`cilists`). A build
//     adds it to `project/plugins.sbt`:
//
//       libraryDependencies += "com.bryzek" %% "lib-build-sbt" % "<version>"
//
//     A plain library and not `sbtPlugin := true`, whose `_2.12_1.0` artifact name the library
//     release does not publish; its jar carries the `sbt/sbt.autoplugins` descriptor itself, which
//     is how sbt finds an AutoPlugin on the meta-build's classpath (`AutoPluginDescriptorSpec`).
//
//   - `lib-build-testkit` (Scala 3): the pin suites, each an abstract suite a build runs by
//     declaring a one-line subclass in its own tests. Every dependency is `Provided`, so a suite
//     exercises the consumer's own resolution.

ThisBuild / version := "0.0.3"

// Publishing coordinates and metadata, the guava pin and the browser-stack exclusions, from this
// repository's own sources (project/build.sbt compiles `sbt/` into the meta-build).
BryzekLibSettings.buildSettings("lib-build")

ThisBuild / scalaVersion := "3.8.4"

ThisBuild / resolvers += "Bryzek Maven repository" at "https://maven.bryzek.com"

ThisBuild / dependencyOverrides ++= BryzekPins.jackson2 ++ BryzekPins.jackson3 ++ BryzekPins.logback

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
    name := "lib-build-sbt",
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

lazy val testkit = project
  .in(file("testkit"))
  .settings(BryzekLibSettings.rootSettings)
  .settings(
    name := "lib-build-testkit",
    libraryDependencies ++= (Seq(
      "org.scalatest" %% "scalatest" % "3.2.20",
      "net.logstash.logback" % "logstash-logback-encoder" % "9.0",
      "org.playframework" %% "play-json" % "3.0.6"
    ) ++ BryzekPins.jackson2 ++ BryzekPins.jackson3 ++ BryzekPins.logback).map(_ % Provided)
  )

lazy val root = project
  .in(file("."))
  .aggregate(sbtModule, testkit)
  .settings(
    publish / skip := true
  )
