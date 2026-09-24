package com.anjunar.hibernateddl.core

class SchemaValidationSuite extends munit.FunSuite:
  private val table = TableModel(
    SchemaId("table"), QualifiedName(SqlIdentifier("customers")),
    Vector(ColumnModel(SchemaId("column"), SqlIdentifier("name"), SqlType.Varchar(80)))
  )

  test("stable IDs and identifiers reject blank values") {
    intercept[IllegalArgumentException](SchemaId("  "))
    intercept[IllegalArgumentException](SqlIdentifier("\t"))
    intercept[IllegalArgumentException](SchemaId(null))
    intercept[IllegalArgumentException](SqlIdentifier(null))
  }

  test("stable IDs are globally unique across tables and columns") {
    val duplicate = table.copy(columns = Vector(table.columns.head.copy(id = table.id)))
    assertEquals(SchemaValidation.validate(SchemaModel(Vector(duplicate))), Vector("Duplicate stable ID 'table'"))
    val second = table.copy(id = SchemaId("second"), name = QualifiedName(SqlIdentifier("second")))
    assert(SchemaValidation.validate(SchemaModel(Vector(table, second))).contains("Duplicate stable ID 'column'"))
  }

  test("table physical names are unique and columns are unique within each table") {
    val second = table.copy(id = SchemaId("second"), columns = Vector.empty)
    assert(SchemaValidation.validate(SchemaModel(Vector(table, second))).exists(_.contains("Duplicate physical table name")))
    val duplicateColumn = table.copy(columns = table.columns :+ table.columns.head.copy(id = SchemaId("second-column")))
    assert(SchemaValidation.validate(SchemaModel(Vector(duplicateColumn))).exists(_.contains("Duplicate physical column name")))
  }

  test("separate schemas may share table names and separate tables may share column names") {
    val second = table.copy(
      id = SchemaId("second"),
      name = table.name.copy(schema = Some(SqlIdentifier("other"))),
      columns = table.columns.map(_.copy(id = SchemaId("second-column")))
    )
    assertEquals(SchemaValidation.validate(SchemaModel(Vector(table, second))), Vector.empty)
  }

  test("primary keys reference existing non-null columns exactly once") {
    val column = table.columns.head
    def keyErrors(key: Vector[SchemaId], columns: Vector[ColumnModel] = Vector(column.copy(nullable = false))) =
      SchemaValidation.validate(SchemaModel(Vector(table.copy(columns = columns, primaryKey = key))))
    assertEquals(keyErrors(Vector(column.id)), Vector.empty)
    assert(keyErrors(Vector(SchemaId("missing"))).exists(_.contains("unknown column 'missing'")))
    assert(keyErrors(Vector(column.id), Vector(column)).exists(_.contains("must not be nullable")))
    assert(keyErrors(Vector(column.id, column.id)).exists(_.contains("more than once")))
  }

  test("VARCHAR lengths must be positive") {
    Vector(0, -1).foreach { length =>
      val invalid = table.copy(columns = table.columns.map(_.copy(dataType = SqlType.Varchar(length))))
      assert(SchemaValidation.validate(SchemaModel(Vector(invalid))).exists(_.contains("invalid VARCHAR length")))
    }
    assertEquals(SchemaValidation.validate(SchemaModel(Vector(table))), Vector.empty)
  }

  test("TIMESTAMP precisions must not be negative") {
    def errors(dataType: SqlType) =
      SchemaValidation.validate(SchemaModel(Vector(table.copy(columns = table.columns.map(_.copy(dataType = dataType))))))
    assert(errors(SqlType.Timestamp(-1)).exists(_.contains("invalid TIMESTAMP precision -1")))
    assert(errors(SqlType.TimestampWithTimeZone(-1)).exists(_.contains("invalid TIMESTAMP precision -1")))
    assertEquals(errors(SqlType.Timestamp(0)), Vector.empty)
    assertEquals(errors(SqlType.TimestampWithTimeZone(6)), Vector.empty)
  }
