# lib-build

What every Scala build on this fleet shares: the security pins, the lib-* build settings, the
test-health sbt plugins, and the suites that assert the pins against a build's own classpath.

Every version is published to the Maven repository at https://maven.bryzek.com.

## lib-build-sbt

Add it to the meta-build in `project/plugins.sbt`:

```scala
resolvers += "Bryzek Maven repository" at "https://maven.bryzek.com"
libraryDependencies += "com.bryzek" %% "lib-build-sbt" % "<version>"
```

That brings, with nothing else to enable:

- `BryzekPins` in every build.sbt: the Jackson 2, Jackson 3 and logback versions, each a security
  floor. `ThisBuild / dependencyOverrides ++= BryzekPins.jackson2 ++ BryzekPins.logback`
- `BryzekLibSettings` in every build.sbt: a library's publishing metadata, guava pin,
  browser-stack exclusions, compiler gate and test reports.
  `BryzekLibSettings.buildSettings("lib-util")`, and `.settings(BryzekLibSettings.rootSettings)`
  on the root project.
- `CiTestListsPlugin` and `CiTestRerunPlugin` on every project: the `CI_TEST_LISTS` filter on
  every test task, and the `ciListsUnknown`, `ciProof` and `ciTest` commands.

It needs sbt 1.12.11 or later and sbt-scalafmt in the same meta-build.

## lib-build-testkit

```scala
libraryDependencies += "com.bryzek" %% "lib-build-testkit" % "<version>" % Test
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

A version the fleet's own builds have not used for 90 days is removed from the Maven repository; a
library's newest version always stays.
