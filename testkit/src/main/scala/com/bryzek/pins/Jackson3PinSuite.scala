package com.bryzek.pins

import com.fasterxml.jackson.annotation.{JsonUnwrapped, JsonView}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tools.jackson.core.async.ByteArrayFeeder
import tools.jackson.core.exc.StreamConstraintsException
import tools.jackson.core.json.JsonFactory
import tools.jackson.core.{ObjectReadContext, StreamReadConstraints}
import tools.jackson.databind.json.JsonMapper

object Jackson3PinSuite {

  /** The two views the deserialization assertion below reads under. Empty on purpose: a view is a
    * class literal and nothing else, and `@JsonView` names it rather than instantiating it.
    *
    * The privileged view EXTENDS the public one, which is how `@JsonView` composes: a property is
    * included when the active view is assignable to the one it names, so everything public is also
    * visible to an admin and not the reverse.
    */
  class PublicView
  class AdminView extends PublicView

  /** The privileged state, grouped in its own object so the property that carries it can be view-
    * gated as a whole -- which is the shape the advisory describes and the reason the container's
    * own view matters.
    */
  class AccountFlags {
    private var roleValue: String = null

    def getRole: String = roleValue

    // Public on its own: nothing about this field is privileged, and the gate the application
    // relies on is the view of the property that WRAPS it. Jackson 3 leaves an unannotated
    // property out of every view, so saying so explicitly is what makes the container's gate the
    // only thing under test.
    @JsonView(Array(classOf[PublicView]))
    def setRole(value: String): Unit = roleValue = value
  }

  /** `flags` is `@JsonUnwrapped`, so `role` is bound from the top level of the document, and it is
    * `@JsonView(AdminView)`, so a reader working under `PublicView` must not bind it at all.
    */
  class Registration {
    private var emailValue: String = null
    private var flagsValue: AccountFlags = null

    def getEmail: String = emailValue
    def setEmail(value: String): Unit = emailValue = value

    def getFlags: AccountFlags = flagsValue

    // The annotations sit on the mutator because that is the member the bypass runs through:
    // deserialization replays the buffered JSON into the unwrapped property by calling this.
    @JsonView(Array(classOf[AdminView]))
    @JsonUnwrapped
    def setFlags(value: AccountFlags): Unit = flagsValue = value
  }
}

/** Jackson 3 -- the `tools.jackson` artifacts -- is pinned by `BryzekPins.jackson3` rather than
  * inherited from logstash-logback-encoder, and a pin that slips below the floor resolves cleanly
  * and says nothing.
  *
  * Neither of the two advisories that set that floor can be read off a version number: each is the
  * behaviour of a limit that is configured, reported as in force, and not applied.
  *
  * The core one is in the non-blocking parser. `maxNumberLength` was applied to the digits within
  * each chunk fed to it rather than to the number accumulated across feeds, so a value split
  * across `feedInput` calls was not bounded at all and no chunk ever had to exceed the limit
  * (GHSA-r7wm-3cxj-wff9). The first assertion feeds one number in pieces and asks for the refusal.
  *
  * The databind one is the quieter, because there the configuration that is supposed to stop a
  * write reports that it is in force and is not. `@JsonView` is a write gate: an endpoint binds an
  * untrusted body under a public view and groups privileged state in a property that names a
  * privileged one. Where that property is also `@JsonUnwrapped`, the buffered JSON was replayed
  * into it without ever asking whether the property is visible in the active view, so an untrusted
  * caller mass-assigned it (GHSA-5gvw-p9qm-jgwh). The second assertion binds a document naming a
  * privileged field under the public view and asks for it to be left alone -- and reads the same
  * document under the privileged view first, because a property Jackson never discovered would
  * leave the container null for the wrong reason and pass.
  *
  * ==Running it==
  * Published in `lib-build-testkit`, every dependency `Provided`, so what it exercises is the
  * consuming build's own resolution and never a version this library brings. sbt runs only the
  * suites a project compiles itself, so a build runs this one by declaring it in its own tests,
  * under the name its test history already has:
  *
  * {{
  * package com.bryzek.pins
  *
  * class Jackson3PinSpec extends Jackson3PinSuite
  * }}
  */
abstract class Jackson3PinSuite extends AnyWordSpec with Matchers {

  import Jackson3PinSuite.*

  private val document = """{"email":"a@example.com","role":"ADMIN"}"""

  "the resolved Jackson 3 pair" must {

    "bound a number accumulated across feeds, not the digits within one feed" in {
      val factory = JsonFactory
        .builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNumberLength(100).build())
        .build()
      val parser = factory.createNonBlockingByteArrayParser(ObjectReadContext.empty())
      val feeder = parser.nonBlockingInputFeeder().asInstanceOf[ByteArrayFeeder]
      // Ten feeds of fifty digits: 500 digits against a limit of 100, with no single chunk over
      // the limit. On an unfixed line every feed is measured on its own and all ten are accepted.
      val chunk = "1".repeat(50).getBytes("UTF-8")

      val thrown = intercept[StreamConstraintsException] {
        (1 to 10).foreach { _ =>
          feeder.feedInput(chunk, 0, chunk.length)
          parser.nextToken()
        }
        feeder.endOfInput()
        parser.nextToken()
      }
      thrown.getMessage must include("Number value length")
    }

    "apply @JsonView to an @JsonUnwrapped property itself, not only to the fields it unwraps" in {
      val mapper = JsonMapper.builder().build()

      // The control: under the view the property names, the same document binds it. Without this
      // the assertion below passes for a property Jackson never found.
      val privileged = mapper
        .readerWithView(classOf[AdminView])
        .forType(classOf[Registration])
        .readValue[Registration](document)
      privileged.getFlags.getRole mustBe "ADMIN"

      val untrusted = mapper
        .readerWithView(classOf[PublicView])
        .forType(classOf[Registration])
        .readValue[Registration](document)
      untrusted.getFlags mustBe null
    }
  }
}
