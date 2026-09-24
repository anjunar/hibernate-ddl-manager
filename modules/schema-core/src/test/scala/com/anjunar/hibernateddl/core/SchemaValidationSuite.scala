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

  test("foreign keys reference the primary key of a modeled table with matching column types") {
    val id = ColumnModel(SchemaId("customer/id"), SqlIdentifier("id"), SqlType.BigInt, nullable = false)
    val customer = TableModel(SchemaId("customer"), QualifiedName(SqlIdentifier("customer")), Vector(id), Vector(id.id))
    val owner = ColumnModel(SchemaId("invoice/owner"), SqlIdentifier("owner_id"), SqlType.BigInt)
    val key = ForeignKeyModel(Vector(owner.id), customer.id, customer.primaryKey)
    val invoice = TableModel(SchemaId("invoice"), QualifiedName(SqlIdentifier("invoice")), Vector(owner), foreignKeys = Vector(key))
    def errors(keys: ForeignKeyModel*) =
      SchemaValidation.validate(SchemaModel(Vector(customer, invoice.copy(foreignKeys = keys.toVector))))
    assertEquals(errors(key), Vector.empty)
    assert(errors(key.copy(referencedTable = SchemaId("missing"))).exists(_.contains("references unknown table 'missing'")))
    assert(errors(key.copy(columns = Vector(SchemaId("missing")))).exists(_.contains("references unknown column 'missing'")))
    assert(errors(key.copy(columns = Vector.empty, referencedColumns = Vector.empty)).exists(_.contains("has no columns")))
    assert(errors(key.copy(referencedColumns = Vector.empty)).exists(_.contains("must reference the primary key")))
    assert(errors(key, key).exists(_.contains("more than one foreign key on (invoice/owner)")))
    val mistyped = SchemaValidation.validate(SchemaModel(Vector(customer,
      invoice.copy(columns = Vector(owner.copy(dataType = SqlType.Integer))))))
    assert(mistyped.exists(_.contains("has type Integer but references type BigInt")), mistyped)
  }

  test("unique keys list existing columns once and are not declared twice") {
    val column = table.columns.head
    def errors(keys: UniqueKeyModel*) = SchemaValidation.validate(SchemaModel(Vector(table.copy(uniqueKeys = keys.toVector))))
    assertEquals(errors(UniqueKeyModel(Vector(column.id))), Vector.empty)
    assert(errors(UniqueKeyModel(Vector.empty)).exists(_.contains("has no columns")))
    assert(errors(UniqueKeyModel(Vector(column.id, column.id))).exists(_.contains("more than once")))
    assert(errors(UniqueKeyModel(Vector(SchemaId("missing")))).exists(_.contains("unknown column 'missing'")))
    assert(errors(UniqueKeyModel(Vector(column.id)), UniqueKeyModel(Vector(column.id))).exists(_.contains("more than one unique key")))
  }

  test("indexes list existing columns once and are not declared twice") {
    val column = table.columns.head
    def errors(indexes: IndexModel*) = SchemaValidation.validate(SchemaModel(Vector(table.copy(indexes = indexes.toVector))))
    val ascending = IndexModel(Vector(IndexColumn(column.id)))
    assertEquals(errors(ascending, IndexModel(Vector(IndexColumn(column.id, descending = true)))), Vector.empty)
    assert(errors(IndexModel(Vector.empty)).exists(_.contains("has no columns")))
    assert(errors(IndexModel(Vector(IndexColumn(column.id), IndexColumn(column.id, descending = true))))
      .exists(_.contains("more than once")))
    assert(errors(IndexModel(Vector(IndexColumn(SchemaId("missing"))))).exists(_.contains("unknown column 'missing'")))
    assert(errors(ascending, ascending).exists(_.contains("more than one index on (column)")))
  }

  test("CHAR lengths, NUMERIC precision and scale and TIME precisions must be in range") {
    def errors(dataType: SqlType) =
      SchemaValidation.validate(SchemaModel(Vector(table.copy(columns = table.columns.map(_.copy(dataType = dataType))))))
    assert(errors(SqlType.Char(0)).exists(_.contains("invalid CHAR length 0")))
    assert(errors(SqlType.Numeric(0, 0)).exists(_.contains("invalid NUMERIC(0, 0)")))
    assert(errors(SqlType.Numeric(5, 6)).exists(_.contains("invalid NUMERIC(5, 6)")))
    assert(errors(SqlType.Numeric(5, -1)).exists(_.contains("invalid NUMERIC(5, -1)")))
    assert(errors(SqlType.Time(-1)).exists(_.contains("invalid TIME precision -1")))
    Vector(SqlType.Char(1), SqlType.Numeric(38, 2), SqlType.Numeric(10, 10), SqlType.Time(0), SqlType.SmallInt,
      SqlType.Real, SqlType.DoublePrecision, SqlType.Date, SqlType.Binary).foreach { valid =>
      assertEquals(errors(valid), Vector.empty)
    }
  }

  test("TIMESTAMP precisions must not be negative") {
    def errors(dataType: SqlType) =
      SchemaValidation.validate(SchemaModel(Vector(table.copy(columns = table.columns.map(_.copy(dataType = dataType))))))
    assert(errors(SqlType.Timestamp(-1)).exists(_.contains("invalid TIMESTAMP precision -1")))
    assert(errors(SqlType.TimestampWithTimeZone(-1)).exists(_.contains("invalid TIMESTAMP precision -1")))
    assertEquals(errors(SqlType.Timestamp(0)), Vector.empty)
    assertEquals(errors(SqlType.TimestampWithTimeZone(6)), Vector.empty)
  }
