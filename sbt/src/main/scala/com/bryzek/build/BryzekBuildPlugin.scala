package com.bryzek.build

import sbt._

/** Puts [[BryzekPins]] and [[BryzekLibSettings]] in scope of every build.sbt that adds
  * `lib-build-sbt` to its meta-build, under the names they have always had there, so a build.sbt
  * that wrote `BryzekPins.jackson2` against a copy in its own `project/` reads the same against the
  * release.
  *
  * It adds no setting. It is an AutoPlugin only because an `autoImport` is how sbt brings a
  * library's names into a build.sbt; the test-health plugins in `cilists` are the ones that change
  * a build.
  *
  * Members are `lazy`: [[BryzekLibSettings]] names sbt-scalafmt's keys, so a build whose
  * meta-build has no sbt-scalafmt reads [[BryzekPins]] without loading it.
  */
object BryzekBuildPlugin extends AutoPlugin {

  override def trigger = allRequirements

  object autoImport {
    lazy val BryzekPins: com.bryzek.build.BryzekPins.type = com.bryzek.build.BryzekPins
    lazy val BryzekLibSettings: com.bryzek.build.BryzekLibSettings.type = com.bryzek.build.BryzekLibSettings
  }
}
