import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.must.Matchers
import sbt.*
import sbt.Keys.moduleID

/** The jar `ForkSafeTestFrameworkPlugin` attaches as each forked test JVM's agent, which is `scala-build-testkit`
  * from the subproject's own Test classpath now that no build holds a `testkit` subproject of its own.
  */
class ForkSafeTestFrameworkPluginSpec extends AnyFunSpec with Matchers {

  private def entry(path: String, module: Option[ModuleID]): Attributed[File] = {
    val blank = Attributed.blank(new File(path))
    module.fold(blank)(m => blank.put(moduleID.key, m))
  }

  it("names scala-build-testkit at this release's version") {
    ForkSafeTestFrameworkPlugin.autoImport.scalaBuildTestkit.name mustBe "scala-build-testkit"
    ForkSafeTestFrameworkPlugin.autoImport.scalaBuildTestkit.revision mustBe ScalaBuildVersion.version
  }

  it("attaches the scala-build-testkit jar on the Test classpath") {
    val classpath = Seq(
      entry("/c/scalatest_3.jar", Some("org.scalatest" % "scalatest_3" % "3.2.20")),
      entry("/c/classes", None),
      entry("/c/scala-build-testkit_3.jar", Some("com.bryzek" % "scala-build-testkit_3" % ScalaBuildVersion.version))
    )
    ForkSafeTestFrameworkPlugin.agentJar(classpath) mustBe new File("/c/scala-build-testkit_3.jar")
  }

  it("refuses a Test classpath without it rather than forking with no agent") {
    val e = intercept[RuntimeException] {
      ForkSafeTestFrameworkPlugin.agentJar(
        Seq(entry("/c/scalatest_3.jar", Some("org.scalatest" % "scalatest_3" % "3.2.20")))
      )
    }
    e.getMessage must include("scala-build-testkit")
  }
}
