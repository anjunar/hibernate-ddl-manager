package io.github.hibernateddl.executor

import io.github.hibernateddl.core.*

class SchemaFingerprintSuite extends munit.FunSuite:
  private val column = ColumnModel(SchemaId("name"), SqlIdentifier("name"), SqlType.Varchar(100), nullable = false)
  private val table = TableModel(SchemaId("customer"), QualifiedName(SqlIdentifier("customer")), Vector(column))
  private val model = SchemaModel(Vector(table))

  test("fingerprints ignore table and column ordering") {
    val first = table.copy(columns = Vector(column, column.copy(id = SchemaId("age"), name = SqlIdentifier("age"))))
    val second = table.copy(id = SchemaId("orders"), name = QualifiedName(SqlIdentifier("orders")),
      columns = Vector(column.copy(id = SchemaId("order-name"))))
    val ordered = SchemaModel(Vector(first, second))
    val reversed = SchemaModel(ordered.tables.reverse.map(t => t.copy(columns = t.columns.reverse)))
    assertEquals(SchemaFingerprint.of(ordered), SchemaFingerprint.of(reversed))
  }

  test("every schema field changes the fingerprint") {
    val changedTables = Vector(
      table.copy(id = SchemaId("other")),
      table.copy(name = table.name.copy(name = SqlIdentifier("other"))),
      table.copy(name = table.name.copy(schema = Some(SqlIdentifier("public")))),
      table.copy(name = table.name.copy(catalog = Some(SqlIdentifier("db")))),
      table.copy(columns = Vector.empty),
      table.copy(primaryKey = Vector(column.id))
    ) ++ Vector(
      column.copy(id = SchemaId("other")), column.copy(name = SqlIdentifier("other")),
      column.copy(nullable = true), column.copy(dataType = SqlType.Varchar(101)),
      column.copy(dataType = SqlType.Integer), column.copy(dataType = SqlType.BigInt),
      column.copy(dataType = SqlType.Boolean), column.copy(dataType = SqlType.Text)
    ).map(c => table.copy(columns = Vector(c)))
    val variants = changedTables.map(t => SchemaModel(Vector(t))) :+ SchemaModel(Vector.empty)
    val hashes = variants.map(SchemaFingerprint.of) :+ SchemaFingerprint.of(model)
    assertEquals(hashes.distinct.size, hashes.size)
    assert(hashes.forall(_.matches("[0-9a-f]{64}")))
  }

  test("length-prefixing distinguishes delimiter-containing IDs and names") {
    val left = SchemaModel(Vector(table.copy(id = SchemaId("a|b"), name = QualifiedName(SqlIdentifier("c")))))
    val right = SchemaModel(Vector(table.copy(id = SchemaId("a"), name = QualifiedName(SqlIdentifier("b|c")))))
    assertNotEquals(SchemaFingerprint.of(left), SchemaFingerprint.of(right))
  }
