package com.bryzek.pins

import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.classic.util.LogbackMDCAdapter
import ch.qos.logback.classic.{Level, LoggerContext}
import net.logstash.logback.encoder.LogstashEncoder
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Json

import java.nio.charset.StandardCharsets

/** Jackson 3 — the `tools.jackson` artifacts — is on this classpath for exactly one reader:
  * `LogstashEncoder`, which `api/conf/logback.xml` names and which logstash-logback-encoder 9.0
  * drives through databind's internal SPI. The version those artifacts resolve to is pinned in
  * `BryzekPins.jackson3` rather than inherited from the encoder's own POM, and no first-party
  * source here imports `tools.jackson` at all.
  *
  * So a pin the encoder cannot link against compiles, resolves, and throws AbstractMethodError or
  * NoSuchMethodError at the first log line the application writes — in production, on a path no
  * suite otherwise touches, because every other spec logs through a pattern encoder. This encodes
  * one event through the real encoder, which is the thing that observes the pin actually links.
  *
  * [[Jackson3PinSuite]] asserts the security limits that set the floor the pin states; this asserts
  * only that the pin works with the encoder an app's `conf/logback.xml` names, which is the half a
  * library build has no reason to carry.
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
  * class Jackson3EncoderSpec extends Jackson3EncoderSuite
  * }}
  */
abstract class Jackson3EncoderSuite extends AnyWordSpec with Matchers {

  "the resolved Jackson 3 pair" must {

    "encode a logging event through LogstashEncoder" in {
      val context = new LoggerContext()
      // A context built here rather than installed as slf4j's has no MDC adapter, and the encoder
      // asks every event for its MDC map unconditionally.
      context.setMDCAdapter(new LogbackMDCAdapter())
      context.start()
      try {
        val encoder = new LogstashEncoder()
        encoder.setContext(context)
        encoder.start()

        val event = new LoggingEvent(
          getClass.getName,
          context.getLogger("jackson-3-encoder-spec"),
          Level.INFO,
          "hello",
          null,
          null,
        )

        val json = Json.parse(new String(encoder.encode(event), StandardCharsets.UTF_8))
        (json \ "message").as[String] mustBe "hello"
        (json \ "level").as[String] mustBe "INFO"
      } finally {
        context.stop()
      }
    }
  }
}
