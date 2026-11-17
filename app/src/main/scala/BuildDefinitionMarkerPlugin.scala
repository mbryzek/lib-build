import sbt.*
import sbt.Keys.*

import scala.sys.process.{Process, ProcessLogger}

/** Marks a fresh checkout with the build definition sbt compiles it under (ISS-34021).
  *
  * `ci/build.slow.sh` removes a checkout's incremental state when the definition it last compiled under, in
  * `target/.ci-build-definition`, is not the one about to be compiled, and reads compiled output with
  * no marker as exactly that (ISS-13194). A fix session that compiles with a hand-run sbt in a fresh
  * clone and then runs `ci/build.slow.sh` in the same tree would lose its own compile to that rule. So
  * every load runs `ci/lib/build-definition.sh claim`, which writes the marker when the checkout holds
  * no compiled output yet, and leaves it alone otherwise. The id is the script's: there is one
  * definition of it, and this plugin only runs it.
  *
  * On every load, `reload` included: a reload after an edit to `build.sbt` finds output compiled
  * under the old definition and writes nothing, which is what `ci/build.slow.sh` should then see.
  *
  * A failure warns and never fails the load: a missing marker costs the next `ci/build.slow.sh` a compile,
  * never a build.
  */
object BuildDefinitionMarkerPlugin extends AutoPlugin {

  override def trigger = allRequirements

  private val script = "ci/lib/build-definition.sh"

  override def globalSettings: Seq[Setting[?]] = Seq(
    onLoad := onLoad.value.andThen(claim)
  )

  private def claim(state: State): State = {
    val root = state.baseDir
    if ((root / script).isFile) {
      val output = new StringBuilder
      val logger = ProcessLogger(line => output.append(line).append('\n'), line => output.append(line).append('\n'))
      val status =
        try Process(Seq("bash", script, "claim"), root).!(logger)
        catch { case e: Exception => output.append(e.getMessage); -1 }
      if (status != 0) state.log.warn(s"$script claim exited $status: ${output.toString.trim}")
    }
    state
  }
}
