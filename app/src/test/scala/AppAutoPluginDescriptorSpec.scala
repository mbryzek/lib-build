import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.must.Matchers
import sbt.AutoPlugin

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.collection.JavaConverters._

/** `scala-build-app`'s own `sbt/sbt.autoplugins`, held to its sources the way `cilists.AutoPluginDescriptorSpec`
  * holds `scala-build-sbt`'s: a plugin left out compiles, publishes and is silently never enabled in platform or
  * playbook, so every AutoPlugin object under `app/src/main/scala` is listed, and every listed name is one.
  *
  * The test classpath carries `scala-build-sbt`'s descriptor too (this module depends on it), so the one read here is
  * the one under this module's own classes.
  */
class AppAutoPluginDescriptorSpec extends AnyFunSpec with Matchers {

  private val root = Paths.get(sys.props.getOrElse("user.dir", "."))

  private val sources = Some(root.resolve("app/src/main/scala"))
    .filter(Files.isDirectory(_))
    .getOrElse(fail(s"no app sources under $root"))

  private val listed: Seq[String] = {
    val urls = getClass.getClassLoader.getResources("sbt/sbt.autoplugins").asScala.toSeq
    val own = urls
      .find(_.toString.contains("/app/target/"))
      .getOrElse(fail(s"scala-build-app carries no sbt/sbt.autoplugins resource among $urls"))
    val in = own.openStream()
    try scala.io.Source.fromInputStream(in, "UTF-8").getLines().map(_.trim).filter(_.nonEmpty).toList
    finally in.close()
  }

  private val ObjectExtendingAutoPlugin = """(?m)^object (\w+) extends AutoPlugin\b""".r
  private val Package = """(?m)^package ([\w.]+)""".r

  private def declared: Seq[String] =
    Files
      .walk(sources)
      .iterator()
      .asScala
      .toSeq
      .filter(_.toString.endsWith(".scala"))
      .flatMap { path =>
        val text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        val pkg = Package.findFirstMatchIn(text).map(_.group(1) + ".").getOrElse("")
        ObjectExtendingAutoPlugin.findAllMatchIn(text).map(m => pkg + m.group(1))
      }
      .sorted

  describe("scala-build-app's sbt/sbt.autoplugins") {

    it("lists every AutoPlugin this module declares") {
      listed.sorted mustBe declared
    }

    it("names only AutoPlugin objects") {
      listed.foreach { name =>
        val module = Class.forName(name + "$").getField("MODULE$").get(null)
        withClue(s"$name: ") { module mustBe an[AutoPlugin] }
      }
    }
  }
}
