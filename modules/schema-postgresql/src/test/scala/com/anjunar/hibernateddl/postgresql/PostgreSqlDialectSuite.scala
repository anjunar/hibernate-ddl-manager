package com.anjunar.hibernateddl.postgresql

import com.anjunar.hibernateddl.core.*
import com.anjunar.hibernateddl.core.SchemaOperation.*

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

  test("nullability changes render per column; NULLs are counted") {
    val orders = name("order", Some("sales"))
    assertEquals(PostgreSqlDialect.render(Vector(
      SetNotNull(tableId, orders, columnId, SqlIdentifier("state\"code")),
      DropNotNull(tableId, orders, columnId, SqlIdentifier("note"))
    )), Right(Vector(
      "ALTER TABLE \"sales\".\"order\" ALTER COLUMN \"state\"\"code\" SET NOT NULL;",
      "ALTER TABLE \"sales\".\"order\" ALTER COLUMN \"note\" DROP NOT NULL;"
    )))
    assertEquals(PostgreSqlDialect.nullCount(orders, SqlIdentifier("note")),
      "SELECT count(*) FROM \"sales\".\"order\" WHERE \"note\" IS NULL")
  }

  test("drops render without CASCADE; all tables go in one statement") {
    val orders = name("order", Some("sales"))
    assertEquals(PostgreSqlDialect.render(Vector(
      DropColumn(tableId, orders, columnId, SqlIdentifier("status")),
      DropTables(Vector(DroppedTable(tableId, orders), DroppedTable(SchemaId("t2"), name("line", Some("sales"))))),
      DropSequence(SchemaId("s"), name("order_seq", Some("sales")))
    )), Right(Vector(
      "ALTER TABLE \"sales\".\"order\" DROP COLUMN \"status\";",
      "DROP TABLE \"sales\".\"order\", \"sales\".\"line\";",
      "DROP SEQUENCE \"sales\".\"order_seq\";"
    )))
    assert(PostgreSqlDialect.render(Vector(DropTables(Vector.empty))).isLeft)
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

  test("foreign keys render as separate ALTER TABLE statements and new tables must not carry them") {
    val key = AddForeignKey(tableId, name("invoice", Some("sales")), Vector(SqlIdentifier("customer_id")),
      name("customer", Some("crm")), Vector(SqlIdentifier("id")))
    assertEquals(
      PostgreSqlDialect.render(Vector(key)),
      Right(Vector("ALTER TABLE \"sales\".\"invoice\" ADD FOREIGN KEY (\"customer_id\") REFERENCES \"crm\".\"customer\" (\"id\");"))
    )
    assert(PostgreSqlDialect.render(Vector(key.copy(referencedColumns = Vector.empty))).swap.toOption.get
      .exists(_.contains("as many referenced columns")))
    val id = ColumnModel(SchemaId("id"), SqlIdentifier("id"), SqlType.BigInt, nullable = false)
    val table = TableModel(tableId, name("invoice"), Vector(id), Vector(id.id),
      Vector(ForeignKeyModel(Vector(id.id), tableId, Vector(id.id))))
    assert(PostgreSqlDialect.render(Vector(CreateTable(table))).swap.toOption.get.exists(_.contains("separate operations")))
  }

  test("unique keys render inside CREATE TABLE and as ADD UNIQUE") {
    val id = ColumnModel(SchemaId("id"), SqlIdentifier("id"), SqlType.BigInt, nullable = false)
    val code = ColumnModel(SchemaId("code"), SqlIdentifier("code"), SqlType.Text)
    val table = TableModel(tableId, name("orders", Some("public")), Vector(id, code), Vector(id.id),
      uniqueKeys = Vector(UniqueKeyModel(Vector(code.id)), UniqueKeyModel(Vector(code.id, id.id))))
    assertEquals(
      PostgreSqlDialect.render(Vector(CreateTable(table), AddUniqueKey(tableId, name("orders", Some("public")), Vector(SqlIdentifier("id"))))),
      Right(Vector(
        "CREATE TABLE \"public\".\"orders\" (\"id\" bigint NOT NULL, \"code\" text, PRIMARY KEY (\"id\"), " +
          "UNIQUE (\"code\"), UNIQUE (\"code\", \"id\"));",
        "ALTER TABLE \"public\".\"orders\" ADD UNIQUE (\"id\");"
      ))
    )
    assert(PostgreSqlDialect.render(Vector(AddUniqueKey(tableId, name("orders"), Vector.empty))).isLeft)
    assert(PostgreSqlDialect.render(Vector(CreateTable(table.copy(uniqueKeys = Vector(UniqueKeyModel(Vector(SchemaId("x")))))))).isLeft)
  }

  test("indexes render as unnamed CREATE INDEX with directions and new tables must not carry them") {
    val index = CreateIndex(tableId, name("orders", Some("public")), Vector(
      IndexedColumn(SqlIdentifier("placed_at"), descending = true), IndexedColumn(SqlIdentifier("id"), descending = false)))
    assertEquals(
      PostgreSqlDialect.render(Vector(index)),
      Right(Vector("CREATE INDEX ON \"public\".\"orders\" (\"placed_at\" DESC, \"id\");"))
    )
    assert(PostgreSqlDialect.render(Vector(index.copy(columns = Vector.empty))).isLeft)
    val id = ColumnModel(SchemaId("id"), SqlIdentifier("id"), SqlType.BigInt, nullable = false)
    val table = TableModel(tableId, name("orders"), Vector(id), Vector(id.id), indexes = Vector(IndexModel(Vector(IndexColumn(id.id)))))
    assert(PostgreSqlDialect.render(Vector(CreateTable(table))).swap.toOption.get.exists(_.contains("indexes must be separate")))
  }

  test("further types render as PostgreSQL types and out-of-range sizes are rejected") {
    val types = Vector(SqlType.Char(1) -> "char(1)", SqlType.Numeric(10, 2) -> "numeric(10,2)", SqlType.Time(0) -> "time(0)",
      SqlType.SmallInt -> "smallint", SqlType.Real -> "real", SqlType.DoublePrecision -> "double precision",
      SqlType.Date -> "date", SqlType.Binary -> "bytea", SqlType.LargeObject -> "oid",
      SqlType.Json -> "jsonb")
    types.foreach { (dataType, sql) =>
      val column = ColumnModel(SchemaId("x"), SqlIdentifier("x"), dataType)
      assertEquals(PostgreSqlDialect.render(Vector(AddColumn(tableId, name("t"), column))),
        Right(Vector(s"ALTER TABLE \"t\" ADD COLUMN \"x\" $sql;")))
    }
    Vector(SqlType.Numeric(1001, 0), SqlType.Numeric(5, 6), SqlType.Time(7), SqlType.Char(10485761), SqlType.Varchar(10485761))
      .foreach { invalid =>
        val column = ColumnModel(SchemaId("x"), SqlIdentifier("x"), invalid)
        assert(PostgreSqlDialect.render(Vector(AddColumn(tableId, name("t"), column))).isLeft, invalid)
      }
  }

  test("column checks render with a name derived from the column ID and the check") {
    val values = ColumnCheck.AllowedValues(Vector("NEW", "it's"))
    val status = ColumnModel(SchemaId("status"), SqlIdentifier("status"), SqlType.Varchar(10), check = Some(values))
    val valuesName = PostgreSqlDialect.checkName(status.id, values).value
    assert(valuesName.matches("ck_[0-9a-f]{24}"), valuesName)
    assertEquals(PostgreSqlDialect.render(Vector(AddColumn(tableId, name("t"), status))), Right(Vector(
      s"ALTER TABLE \"t\" ADD COLUMN \"status\" varchar(10) CONSTRAINT \"$valuesName\" CHECK (\"status\" IN ('NEW', 'it''s'));")))
    val range = ColumnCheck.Range(0, 2)
    val change = ChangeCheck(tableId, name("t"), status.id, SqlIdentifier("state"), Some(values), Some(range))
    val rangeName = PostgreSqlDialect.checkName(status.id, range).value
    assertEquals(PostgreSqlDialect.render(Vector(change)), Right(Vector(
      s"ALTER TABLE \"t\" DROP CONSTRAINT \"$valuesName\", ADD CONSTRAINT \"$rangeName\" CHECK (\"state\" BETWEEN 0 AND 2);")))
    assertNotEquals(rangeName, valuesName)
    assertNotEquals(PostgreSqlDialect.checkName(SchemaId("other"), values).value, valuesName)
    assert(PostgreSqlDialect.render(Vector(change.copy(to = Some(ColumnCheck.AllowedValues(Vector("a\u0000b")))))).isLeft)
  }

  test("sequences and identity columns render as PostgreSQL DDL") {
    val sequence = SequenceModel(SchemaId("s"), name("customer_SEQ", Some("public")), 1, 50)
    val key = ColumnModel(SchemaId("id"), SqlIdentifier("id"), SqlType.BigInt, nullable = false, identity = true)
    assertEquals(PostgreSqlDialect.render(Vector(
      CreateSequence(sequence),
      RenameSequence(sequence.id, sequence.name, name("client_SEQ", Some("public"))),
      CreateTable(TableModel(tableId, name("client", Some("public")), Vector(key), Vector(key.id)))
    )), Right(Vector(
      "CREATE SEQUENCE \"public\".\"customer_SEQ\" AS bigint START WITH 1 INCREMENT BY 50;",
      "ALTER SEQUENCE \"public\".\"customer_SEQ\" RENAME TO \"client_SEQ\";",
      "CREATE TABLE \"public\".\"client\" (\"id\" bigint NOT NULL GENERATED BY DEFAULT AS IDENTITY, PRIMARY KEY (\"id\"));"
    )))
    assert(PostgreSqlDialect.render(Vector(RenameSequence(sequence.id, sequence.name, name("x", Some("other"))))).isLeft)
  }

  test("an empty plan renders to an empty statement vector") {
    assertEquals(PostgreSqlDialect.render(Vector.empty), Right(Vector.empty))
  }

  private def retype(from: SqlType, to: SqlType, column: String = "display_name"): SchemaOperation =
    ChangeColumnType(tableId, name("users", Some("public")), columnId, SqlIdentifier(column), from, to)

  test("the three widenings render as a quoted ALTER COLUMN TYPE from the model's type, without USING") {
    assertEquals(PostgreSqlDialect.render(Vector(
      retype(SqlType.Varchar(100), SqlType.Varchar(255)),
      retype(SqlType.Integer, SqlType.BigInt, "visit\"count"),
      retype(SqlType.Numeric(10, 2), SqlType.Numeric(14, 2), "Balance")
    )), Right(Vector(
      "ALTER TABLE \"public\".\"users\" ALTER COLUMN \"display_name\" TYPE varchar(255);",
      "ALTER TABLE \"public\".\"users\" ALTER COLUMN \"visit\"\"count\" TYPE bigint;",
      "ALTER TABLE \"public\".\"users\" ALTER COLUMN \"Balance\" TYPE numeric(14,2);"
    )))
  }

  test("a type change built by hand cannot make the renderer emit anything but a supported widening") {
    Vector(
      retype(SqlType.Varchar(255), SqlType.Varchar(100)) -> "shortens VARCHAR",
      retype(SqlType.BigInt, SqlType.Integer) -> "narrows BIGINT",
      retype(SqlType.Numeric(10, 2), SqlType.Numeric(14, 4)) -> "NUMERIC scale",
      retype(SqlType.Text, SqlType.Uuid) -> "not a supported widening",
      retype(SqlType.Varchar(10), SqlType.Varchar(10)) -> "already has type",
      retype(SqlType.Varchar(100), SqlType.Varchar(10485761)) -> "PostgreSQL supports 1 to 10485760",
      retype(SqlType.Numeric(10, 2), SqlType.Numeric(1001, 2)) -> "precision 1 to 1000",
      retype(SqlType.Varchar(0), SqlType.Varchar(10)) -> "The previous type has VARCHAR length 0"
    ).foreach { (operation, reason) =>
      val diagnostics = PostgreSqlDialect.render(Vector(operation)).swap.getOrElse(fail(s"$operation rendered"))
      assert(diagnostics.exists(_.contains(reason)), diagnostics)
    }
    assert(PostgreSqlDialect.render(Vector(retype(SqlType.Varchar(100), SqlType.Varchar(10485760)))).isRight)
    assert(PostgreSqlDialect.render(Vector(retype(SqlType.Numeric(10, 2), SqlType.Numeric(1000, 2)))).isRight)
  }

  test("dropped indexes and unique keys render as templates that name no object and cannot run") {
    val table = name("my \"users\"", Some("app"))
    val dropIndex = DropIndex(IndexRef(tableId, Vector(IndexColumn(columnId), IndexColumn(SchemaId("c2"), descending = true))),
      table, Vector(IndexedColumn(SqlIdentifier("last"), false), IndexedColumn(SqlIdentifier("fi\"rst"), true)))
    val dropUnique = DropUniqueKey(UniqueKeyRef(tableId, Vector(columnId)), table, Vector(SqlIdentifier("email")))
    assertEquals(PostgreSqlDialect.render(Vector(dropIndex, dropUnique)), Right(Vector(
      "DROP INDEX \"app\".<the index (\"last\", \"fi\"\"rst\" DESC) of \"app\".\"my \"\"users\"\"\", found under the migration lock>;",
      "ALTER TABLE \"app\".\"my \"\"users\"\"\" DROP CONSTRAINT <the unique key (\"email\"), found under the migration lock>;"
    )))
    assert(SchemaOperation.boundAtExecution(dropIndex) && SchemaOperation.boundAtExecution(dropUnique))
    assert(!SchemaOperation.boundAtExecution(rename("a", "b")))
    assert(PostgreSqlDialect.render(Vector(dropUnique.copy(columns = Vector.empty))).isLeft)
    assert(PostgreSqlDialect.render(Vector(dropIndex.copy(columns = dropIndex.columns.map(_.copy(descending = false))))).isLeft)
  }

  test("bound drops use the catalog's names, quoted, and no IF EXISTS") {
    assertEquals(PostgreSqlDialect.renderDropIndex(SqlIdentifier("app"), SqlIdentifier("my \"users\"_last_idx")),
      "DROP INDEX \"app\".\"my \"\"users\"\"_last_idx\";")
    assertEquals(PostgreSqlDialect.renderDropConstraint(name("people", Some("app")), SqlIdentifier("users_email_key")),
      "ALTER TABLE \"app\".\"people\" DROP CONSTRAINT \"users_email_key\";")
  }
