package cilists

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.must.Matchers
import sbt.AutoPlugin

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.collection.JavaConverters._

/** sbt finds a library's AutoPlugins only through the `sbt/sbt.autoplugins` resource the library's
  * jar carries. `lib-build-sbt` is published as a plain library rather than with `sbtPlugin := true`
  * (whose `_2.12_1.0` artifact name the library release does not publish), so that resource is
  * written by hand, and a plugin left out of it compiles, publishes and is silently never enabled
  * in any consumer: no test-health filter, no rerun, and no `BryzekPins` in a build.sbt.
  *
  * So this asserts both directions against the sources: every AutoPlugin object here is listed,
  * and every listed name is an AutoPlugin.
  */
class AutoPluginDescriptorSpec extends AnyFunSpec with Matchers {

  private val listed: Seq[String] = {
    val in = getClass.getClassLoader.getResourceAsStream("sbt/sbt.autoplugins")
    if (in == null) fail("lib-build-sbt carries no sbt/sbt.autoplugins resource")
    try scala.io.Source.fromInputStream(in, "UTF-8").getLines().map(_.trim).filter(_.nonEmpty).toList
    finally in.close()
  }

  private val ObjectExtendingAutoPlugin = """(?m)^object (\w+) extends AutoPlugin\b""".r
  private val Package = """(?m)^package ([\w.]+)""".r

  /** Every `object X extends AutoPlugin` under this module's main sources, by its full name. */
  private def declared: Seq[String] = {
    val root = Paths.get(sys.props.getOrElse("user.dir", "."))
    val sources = Seq(root.resolve("sbt/src/main/scala"), root.resolve("src/main/scala")).find(Files.isDirectory(_))
    val dir = sources.getOrElse(fail(s"no main sources under $root"))
    Files
      .walk(dir)
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
  }

  describe("sbt/sbt.autoplugins") {

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
