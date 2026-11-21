# scala-build

What every Scala build on this fleet shares: the security pins, the lib-* build settings, the
test-health sbt plugins, the build tooling of the Play applications, and the suites and test
framework their tests run under. The repository was `lib-build`; `lib-build-sbt` and
`lib-build-testkit` stay resolvable at their last version, 0.0.9.

Every version is published to the Maven repository at https://maven.bryzek.com.

## scala-build-sbt

Every Scala build adds it to the meta-build in `project/plugins.sbt`:

```scala
resolvers += "Bryzek Maven repository" at "https://maven.bryzek.com"
libraryDependencies += "com.bryzek" %% "scala-build-sbt" % "<version>"
```

That brings, with nothing else to enable:

- `BryzekPins` in every build.sbt: the Jackson 2, Jackson 3 and logback versions, each a security
  floor. `ThisBuild / dependencyOverrides ++= BryzekPins.jackson2 ++ BryzekPins.logback`
- `BryzekLibSettings` in every build.sbt: a library's publishing metadata, guava pin,
  browser-stack exclusions, compiler gate and test reports.
  `BryzekLibSettings.buildSettings("lib-util")`, and `.settings(BryzekLibSettings.rootSettings)`
  on the root project.
- `CiTestListsPlugin` and `CiTestRerunPlugin` on every project: the `CI_TEST_LISTS` filter on
  every test task, and the `ciListsUnknown`, `ciProof`, `ciTest` and `ciVerdict` commands.
  Under `CI_TEST_LISTS` the filter also leaves out every guard, a class whose name ends in
  `GuardSpec`; `CI_GUARD_SPECS=1` runs the guards and nothing else, `CI_SLOW_SPECS=1` never runs
  one, and a local `sbt test` with neither set runs them.

A build's whole test phase is one sbt start: `ciTest [--stop] [<subproject>...] [-- <subproject>...]`
runs the named subprojects' tests (every subproject when none is named), reruns each failed spec once
(`ci-flake:` when it passes, `ci-rerun: failed again:` when it does not) and runs the subprojects
after `--` as a second phase. With `--stop` a spec that fails its rerun leaves the second phase unrun,
named on a `ci-stopped:` line. `ciVerdict` is the finish of a split build: it reads every shard's
`<subproject>/target/test-reports` and prints the whole test phase's verdict.

It needs sbt 1.12.11 or later and sbt-scalafmt in the same meta-build.

## scala-build-app

A Play application's build (platform, playbook) adds it beside `scala-build-sbt`, with the Play
sbt-plugin and the Postgres driver its own `core` uses:

```scala
addSbtPlugin("org.playframework" % "sbt-plugin" % "<play version>")
libraryDependencies += "org.postgresql" % "postgresql" % "<driver version>"
libraryDependencies += "com.bryzek" %% "scala-build-app" % "<version>"
```

Its plugins trigger on their own, so a library never adds it:

- On every `PlayScala` project: `TestDatabasePlugin` (a lane database per subproject and shard,
  cloned from the `dev db session` database, `<database>_sess_<id>`) and
  `ForkSafeTestFrameworkPlugin`, which adds `scala-build-testkit` to the project's Test classpath
  at this release's version, runs its suites through `testkit.ForkSafeScalaTestFramework` and
  attaches the jar to each forked test JVM as its agent.
- On every project: `CiShardPlugin`, `CompileCachePlugin`, `CompileProfilePlugin`,
  `NoScaladocPlugin`, `BuildDefinitionMarkerPlugin` and `testimpact.TestImpactPlugin`.

And in every build.sbt: `TestDatabases`, `TestDatabasePlugin.ShardTag` and `admitShard` for
`concurrentRestrictions`; `AutonomyWorkflowRegistry.generator` and
`OneTimeMigrationRegistry.generator`, through `RegisteredByExistence`, for a registry generated
from the classes that declare it; and `scalaBuildTestkit`, the testkit's module at this release.

It needs sbt 1.13.1.

## scala-build-testkit

```scala
libraryDependencies += "com.bryzek" %% "scala-build-testkit" % "<version>" % Test
```

Each pin suite is abstract; sbt runs only the suites a project compiles itself, so a build declares
the ones whose libraries it resolves:

```scala
package com.bryzek.pins

class JacksonPinSpec extends JacksonPinSuite
```

`JacksonPinSuite`, `Jackson3PinSuite`, `LogbackPinSuite`, and `Jackson3EncoderSuite` for an app
whose `conf/logback.xml` names `LogstashEncoder`. Every dependency is `Provided`: a suite runs
against the versions the consuming build resolves.

The `testkit` package is what `scala-build-app` wires into an application's forked test JVMs:
`ForkSafeScalaTestFramework`, the `ForkSafeExitAgent` java agent (the jar's `Premain-Class`),
`ShardDatabase`, `ShardReport` and `PartStart`.

A version the fleet's own builds have not used for 90 days is removed from the Maven repository; a
library's newest version always stays.
