package io.github.hibernateddl.postgresql

import io.github.hibernateddl.core.*
import io.github.hibernateddl.core.SchemaOperation.*

class PostgreSqlDialectSuite extends munit.FunSuite:
  private val tableId = SchemaId("table:orders")
  private val columnId = SchemaId("column:status")

  private def name(value: String, schema: Option[String] = None): QualifiedName =
    QualifiedName(SqlIdentifier(value), schema.map(SqlIdentifier.apply))

  private def rename(from: String, to: String): SchemaOperation =
    RenameTable(tableId, name(from), name(to))

  test("quotes reserved words and escapes embedded double quotes") {
    assertEquals(
      PostgreSqlDialect.render(Vector(rename("select", "an\"order"))),
      Right(Vector("ALTER TABLE \"select\" RENAME TO \"an\"\"order\";"))
    )
  }

  test("quotes schema names separately and preserves operation order") {
    val before = name("order", Some("sales\"team"))
    val after = name("orders", Some("sales\"team"))
    val operations = Vector(
      RenameTable(tableId, before, after),
      RenameColumn(tableId, after, columnId, SqlIdentifier("from"), SqlIdentifier("state\"code"))
    )

    assertEquals(
      PostgreSqlDialect.render(operations),
      Right(Vector(
        "ALTER TABLE \"sales\"\"team\".\"order\" RENAME TO \"orders\";",
        "ALTER TABLE \"sales\"\"team\".\"orders\" RENAME COLUMN \"from\" TO \"state\"\"code\";"
      ))
    )
  }

  test("returns all diagnostics without a partial SQL plan") {
    val source = QualifiedName(SqlIdentifier("orders"), catalog = Some(SqlIdentifier("database")))
    val operations = Vector(
      rename("valid", "also_valid"),
      RenameTable(tableId, source, name("renamed")),
      RenameTable(tableId, name("orders", Some("public")), name("orders", Some("archive")))
    )

    val diagnostics = PostgreSqlDialect.render(operations).swap.toOption.get
    assertEquals(diagnostics.size, 2)
    assert(diagnostics.exists(message => message.startsWith("Operation 2:") && message.contains("Catalog")))
    assert(diagnostics.exists(message => message.startsWith("Operation 3:") && message.contains("across schemas")))
  }

  test("rejects catalog qualification in column renames and target table names") {
    val catalogName = QualifiedName(SqlIdentifier("orders"), catalog = Some(SqlIdentifier("database")))
    val operations = Vector(
      RenameColumn(tableId, catalogName, columnId, SqlIdentifier("old"), SqlIdentifier("new")),
      RenameTable(tableId, name("old"), catalogName)
    )
    assertEquals(PostgreSqlDialect.render(operations).swap.toOption.get.size, 2)
  }

  test("rejects changing between qualified and unqualified table names") {
    val operation = RenameTable(tableId, name("orders"), name("renamed", Some("public")))
    assert(PostgreSqlDialect.render(Vector(operation)).isLeft)
  }

  test("accepts exactly 63 UTF-8 bytes and rejects identifiers that exceed the limit") {
    val asciiBoundary = "a" * 63
    val unicodeBoundary = "é" * 31 + "a"
    assert(PostgreSqlDialect.render(Vector(rename(asciiBoundary, unicodeBoundary))).isRight)
    assert(PostgreSqlDialect.render(Vector(rename("a" * 64, "ok"))).isLeft)
    assert(PostgreSqlDialect.render(Vector(rename("ok", "é" * 32))).isLeft)
  }

  test("validates schemas and both sides of column renames") {
    val operation = RenameColumn(
      tableId,
      name("orders", Some("s" * 64)),
      columnId,
      SqlIdentifier("a" * 64),
      SqlIdentifier("b" * 64)
    )
    assertEquals(PostgreSqlDialect.render(Vector(operation)).swap.toOption.get.size, 3)
  }

  test("rejects NUL-containing identifiers") {
    val operations = Vector(
      rename("valid", "bad\u0000name"),
      RenameColumn(tableId, name("orders"), columnId, SqlIdentifier("bad\u0000column"), SqlIdentifier("new"))
    )
    val diagnostics = PostgreSqlDialect.render(operations).swap.toOption.get
    assertEquals(diagnostics.size, 2)
    assertEquals(diagnostics.count(_.contains("NUL")), 2)
  }

  test("new tables render their primary key in key order") {
    val a = ColumnModel(SchemaId("a"), SqlIdentifier("tenant"), SqlType.BigInt, nullable = false)
    val b = ColumnModel(SchemaId("b"), SqlIdentifier("id"), SqlType.BigInt, nullable = false)
    val table = TableModel(tableId, name("orders", Some("public")), Vector(a, b), primaryKey = Vector(b.id, a.id))
    assertEquals(
      PostgreSqlDialect.render(Vector(CreateTable(table))),
      Right(Vector(
        "CREATE TABLE \"public\".\"orders\" (\"tenant\" bigint NOT NULL, \"id\" bigint NOT NULL, PRIMARY KEY (\"id\", \"tenant\"));"
      ))
    )
    assert(PostgreSqlDialect.render(Vector(CreateTable(table.copy(primaryKey = Vector(SchemaId("x")))))).isLeft)
  }

  test("uuid and timestamps render with their precision, which PostgreSQL limits to 6") {
    val columns = Vector(
      ColumnModel(SchemaId("id"), SqlIdentifier("id"), SqlType.Uuid, nullable = false),
      ColumnModel(SchemaId("created"), SqlIdentifier("created_at"), SqlType.Timestamp(0)),
      ColumnModel(SchemaId("paid"), SqlIdentifier("paid_at"), SqlType.TimestampWithTimeZone(6))
    )
    val table = TableModel(tableId, name("orders", Some("public")), columns, Vector(SchemaId("id")))
    assertEquals(
      PostgreSqlDialect.render(Vector(CreateTable(table))),
      Right(Vector("CREATE TABLE \"public\".\"orders\" (\"id\" uuid NOT NULL, \"created_at\" timestamp(0), " +
        "\"paid_at\" timestamp(6) with time zone, PRIMARY KEY (\"id\"));"))
    )
    Vector(SqlType.Timestamp(7), SqlType.TimestampWithTimeZone(-1)).foreach { invalid =>
      val column = ColumnModel(SchemaId("x"), SqlIdentifier("x"), invalid)
      val diagnostics = PostgreSqlDialect.render(Vector(AddColumn(tableId, name("orders"), column))).swap.toOption.get
      assert(diagnostics.exists(_.contains("supports 0 to 6")), diagnostics)
    }
  }

  test("an empty plan renders to an empty statement vector") {
    assertEquals(PostgreSqlDialect.render(Vector.empty), Right(Vector.empty))
  }
