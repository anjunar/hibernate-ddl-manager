package io.github.hibernateddl.cli

class MainSuite extends munit.FunSuite:
  test("demo produces the expected rename statement end to end") {
    val output = Main.run(Vector("demo")).toOption.get
    assert(output.contains("Stable ID: 7f3a9c21/f34e45b6"))
    assert(output.contains("ALTER TABLE \"public\".\"users\" RENAME COLUMN \"username\" TO \"login_name\";"))
    assert(output.contains("not executed"))
    assert(!output.contains("DROP"))
  }

  test("unknown commands fail instead of pretending to execute a migration") {
    assert(Main.run(Vector("migrate")).isLeft)
  }

  test("help documents the runnable demo") {
    assert(Main.run(Vector("--help")).toOption.get.contains("schemaCli/run demo"))
  }
