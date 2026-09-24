package com.anjunar.hibernateddl.core

class DiffEngineSuite extends munit.FunSuite:
  private def column(id: String, name: String): ColumnModel =
    ColumnModel(SchemaId(id), SqlIdentifier(name), SqlType.Varchar(100))

  private def table(id: String, name: String, columns: ColumnModel*): TableModel =
    TableModel(SchemaId(id), QualifiedName(SqlIdentifier(name)), columns.toVector)

  private val customer = table("customer", "customer", column("customer-name", "name"))
  private val initial = SchemaModel(Vector(customer))

  private def errors(result: Either[Vector[String], Vector[SchemaOperation]]): Vector[String] =
    result match
      case Left(messages) => messages
      case Right(operations) => fail(s"Expected manual migration diagnostics, got $operations")

  test("stable IDs produce table and column renames using the final table name") {
    val renamed = customer.copy(
      name = QualifiedName(SqlIdentifier("customers")),
      columns = Vector(customer.columns.head.copy(name = SqlIdentifier("full_name")))
    )
    assertEquals(
      DiffEngine.diff(initial, SchemaModel(Vector(renamed))),
      Right(Vector(
        SchemaOperation.RenameTable(customer.id, customer.name, renamed.name),
        SchemaOperation.RenameColumn(customer.id, renamed.name, customer.columns.head.id,
          SqlIdentifier("name"), SqlIdentifier("full_name"))
      ))
    )
  }

  test("unchanged and empty schemas produce no operations") {
    assertEquals(DiffEngine.diff(initial, initial), Right(Vector.empty))
    assertEquals(DiffEngine.diff(SchemaModel(Vector.empty), SchemaModel(Vector.empty)), Right(Vector.empty))
  }

  test("table and column input order does not change deterministic planning") {
    val a = table("a", "alpha", column("a-2", "second"), column("a-1", "first"))
    val b = table("b", "beta", column("b-1", "first"))
    def rename(t: TableModel): TableModel = t.copy(
      name = QualifiedName(SqlIdentifier(t.name.name.value + "_new")),
      columns = t.columns.map(c => c.copy(name = SqlIdentifier(c.name.value + "_new")))
    )
    val previous = SchemaModel(Vector(b, a))
    val desired = SchemaModel(Vector(rename(b), rename(a)))
    def reorder(s: SchemaModel): SchemaModel = s.copy(tables = s.tables.reverse.map(t => t.copy(columns = t.columns.reverse)))
    val result = DiffEngine.diff(previous, desired)
    assertEquals(result, DiffEngine.diff(reorder(previous), reorder(desired)))
    assertEquals(DiffEngine.diff(previous, reorder(previous)), Right(Vector.empty))
    val plannedIds = result.toOption.get.map {
      case r: SchemaOperation.RenameTable => r.tableId.value
      case r: SchemaOperation.RenameColumn => r.columnId.value
      case other => fail(s"Unexpected operation in rename-only fixture: $other")
    }
    assertEquals(plannedIds, Vector("a", "a-1", "a-2", "b", "b-1"))
  }

  test("new tables and nullable columns are planned; removals still require manual migration") {
    val unrelated = SchemaModel(Vector(table("new-customer", "customer", column("new-name", "name"))))
    val diagnostics = errors(DiffEngine.diff(initial, unrelated))
    assert(diagnostics.exists(_.contains("Dropping table")))
    val replacedColumn = SchemaModel(Vector(customer.copy(columns = Vector(column("replacement", "name")))))
    val columnDiagnostics = errors(DiffEngine.diff(initial, replacedColumn))
    assert(columnDiagnostics.exists(_.contains("Dropping column")))
  }

  test("create table and add nullable column are deterministic and preserve stable IDs") {
    val extra = column("new-note", "note")
    val newTable = table("z-new", "notes", column("z-body", "body"))
    val after = SchemaModel(Vector(newTable, customer.copy(columns = customer.columns :+ extra)))
    assertEquals(DiffEngine.diff(initial, after), Right(Vector(
      SchemaOperation.AddColumn(customer.id, customer.name, extra),
      SchemaOperation.CreateTable(newTable)
    )))
  }

  test("adding a non-null column to an existing table requires a backfill") {
    val extra = column("new-note", "note").copy(nullable = false)
    val result = DiffEngine.diff(initial, SchemaModel(Vector(customer.copy(columns = customer.columns :+ extra))))
    assert(errors(result).exists(_.contains("backfill")))
  }

  test("a rename is not returned alongside an unsupported type or nullability change") {
    val changed = customer.copy(columns = Vector(customer.columns.head.copy(
      name = SqlIdentifier("renamed"), dataType = SqlType.Text, nullable = false
    )))
    val diagnostics = errors(DiffEngine.diff(initial, SchemaModel(Vector(changed))))
    assert(diagnostics.exists(_.contains("Changing type")))
    assert(diagnostics.exists(_.contains("Changing nullability")))
  }

  test("schema and catalog moves are rejected") {
    Vector(
      customer.name.copy(schema = Some(SqlIdentifier("other"))),
      customer.name.copy(catalog = Some(SqlIdentifier("other")))
    ).foreach { movedName =>
      val diagnostics = errors(DiffEngine.diff(initial, SchemaModel(Vector(customer.copy(name = movedName)))))
      assert(diagnostics.exists(_.contains("Moving table")))
    }
  }

  test("table swap and dependent renames are rejected") {
    val a = table("a", "alpha")
    val b = table("b", "beta")
    val before = SchemaModel(Vector(a, b))
    val swapped = SchemaModel(Vector(a.copy(name = b.name), b.copy(name = a.name)))
    assertEquals(errors(DiffEngine.diff(before, swapped)).count(_.contains("collides")), 2)
    val dependent = SchemaModel(Vector(a.copy(name = b.name), b.copy(name = QualifiedName(SqlIdentifier("gamma")))) )
    assert(errors(DiffEngine.diff(before, dependent)).exists(_.contains("collides")))
  }

  test("column swap and dependent renames are rejected") {
    val a = column("a", "alpha")
    val b = column("b", "beta")
    val before = SchemaModel(Vector(table("t", "t", a, b)))
    val swapped = SchemaModel(Vector(table("t", "t", a.copy(name = b.name), b.copy(name = a.name))))
    assertEquals(errors(DiffEngine.diff(before, swapped)).count(_.contains("collides")), 2)
    val dependent = SchemaModel(Vector(table("t", "t", a.copy(name = b.name), b.copy(name = SqlIdentifier("gamma")))))
    assert(errors(DiffEngine.diff(before, dependent)).exists(_.contains("collides")))
  }

  test("stable ID reuse across column ownership is rejected explicitly") {
    val a = table("a", "alpha", column("shared", "name"))
    val b = table("b", "beta")
    val before = SchemaModel(Vector(a, b))
    val after = SchemaModel(Vector(a.copy(columns = Vector.empty), b.copy(columns = a.columns)))
    assert(errors(DiffEngine.diff(before, after)).exists(_.contains("was reused")))
  }

  test("stable ID reuse across entity kinds is rejected explicitly") {
    val after = SchemaModel(Vector(table("customer-name", "other", column("customer", "name"))))
    assertEquals(errors(DiffEngine.diff(initial, after)).count(_.contains("was reused")), 2)
  }

  test("invalid previous or desired schemas are rejected before planning") {
    val invalid = SchemaModel(Vector(customer, customer.copy(name = QualifiedName(SqlIdentifier("other")))))
    assert(errors(DiffEngine.diff(invalid, initial)).exists(_.startsWith("Previous schema: Duplicate stable ID")))
    assert(errors(DiffEngine.diff(initial, invalid)).exists(_.startsWith("Desired schema: Duplicate stable ID")))
  }

  test("primary key columns may be renamed but the key itself cannot change") {
    val key = customer.columns.head.copy(nullable = false)
    val keyed = customer.copy(columns = Vector(key), primaryKey = Vector(key.id))
    val before = SchemaModel(Vector(keyed))
    val renamed = SchemaModel(Vector(keyed.copy(columns = Vector(key.copy(name = SqlIdentifier("customer_name"))))))
    assertEquals(DiffEngine.diff(before, renamed), Right(Vector(
      SchemaOperation.RenameColumn(keyed.id, keyed.name, key.id, SqlIdentifier("name"), SqlIdentifier("customer_name"))
    )))
    val unkeyed = SchemaModel(Vector(keyed.copy(primaryKey = Vector.empty)))
    assert(errors(DiffEngine.diff(before, unkeyed)).exists(_.contains("Changing primary key")))
  }

  test("renames expose their locking risk") {
    val renamed = customer.name.copy(name = SqlIdentifier("renamed"))
    assertEquals(SchemaOperation.RenameTable(customer.id, customer.name, renamed).risk, RiskLevel.Locking)
    assertEquals(SchemaOperation.RenameColumn(customer.id, customer.name, customer.columns.head.id,
      SqlIdentifier("name"), SqlIdentifier("renamed")).risk, RiskLevel.Locking)
  }
