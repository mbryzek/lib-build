import sbt.*
import sbt.Keys.*

/** Turns Scaladoc off for every subproject. Nothing in this build publishes a doc jar, so the only
  * thing `doc` ever produced here was output `dist` packaged and threw away.
  *
  * An AutoPlugin rather than a settings block each subproject opts into, for the same reason
  * [[ForkSafeTestFrameworkPlugin]] is one: the subproject that forgets to opt in does not fail at
  * the point of the mistake. This suppression used to live in `packagingSettings`, which `testkit`
  * — the one subproject that packages nothing — did not apply, and that was invisible until it
  * broke a release.
  *
  * Scaladoc's own classpath is subject to the build-wide `dependencyOverrides` in build.sbt, and
  * Scala 3's scaladoc parses the static site before it documents anything using
  * `com.fasterxml.jackson.annotation.JsonSerializeAs`, which needs jackson-annotations >= 2.21. So
  * a build whose Jackson pin sits below that line cannot run `doc` at all, and `sbt dist` fails in
  * whichever subproject reaches it first. That is not why this plugin exists — it exists because
  * the output is waste — but it is why turning it off is coupled to the Jackson version.
  *
  * `requires JvmPlugin` rather than `PlayScala`: `testkit` does not enable `PlayScala`, and
  * `testkit` is precisely the project this needs to reach.
  *
  * Emptying `doc / sources` rather than disabling the task keeps `doc` itself defined and cheap, so
  * anything that depends on it (`packageDoc`, aggregate `dist`) still runs and produces an empty
  * artifact instead of failing on a missing key.
  */
object NoScaladocPlugin extends AutoPlugin {

  override def trigger = allRequirements

  override def requires = plugins.JvmPlugin

  override def projectSettings: Seq[Setting[?]] = Seq(
    Compile / doc / sources := Seq.empty,
    Compile / packageDoc / publishArtifact := false,
  )
}
