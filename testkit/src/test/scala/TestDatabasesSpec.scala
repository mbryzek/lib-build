import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.must.Matchers

/** Which databases a build may clone a lane from. scala-build-app's `TestDatabases.scala` is compiled into this
  * subproject's tests as well as into the meta-build, so what is asserted here is the rule every application's
  * build reads its own `dev db session` database by.
  */
class TestDatabasesSpec extends AnyFunSpec with Matchers {

  it("accepts each app's session database") {
    TestDatabases.isSessionDb("platformdb_sess_i25743") mustBe true
    TestDatabases.isSessionDb("playbookdb_sess_i103193_c01") mustBe true
  }

  it("refuses a database that is not a session's") {
    TestDatabases.isSessionDb("platformdb") mustBe false
    TestDatabases.isSessionDb("platformdb_core") mustBe false
    TestDatabases.isSessionDb("platformdb_sess_") mustBe false
    TestDatabases.isSessionDb("x_platformdb_sess_i1") mustBe false
  }

  it("refuses a lane of a session database") {
    TestDatabases.isSessionDb("platformdb_sess_i25743__core") mustBe false
  }
}
