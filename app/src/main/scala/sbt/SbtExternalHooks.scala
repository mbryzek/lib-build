package sbt

/** sbt's `externalHooks`, which sbt declares `private[sbt]`: the one way a build hands zinc its own classpath hash
  * ([[PathIndependentClasspath]], ISS-44010).
  */
object SbtExternalHooks {
  val key: TaskKey[xsbti.compile.ExternalHooks] = Keys.externalHooks
}
