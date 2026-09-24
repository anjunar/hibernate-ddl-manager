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

  test("foreign keys are added after all tables, so new tables may reference each other and themselves") {
    val id = ColumnModel(SchemaId("category/id"), SqlIdentifier("id"), SqlType.BigInt, nullable = false)
    val parent = ColumnModel(SchemaId("category/parent"), SqlIdentifier("parent_id"), SqlType.BigInt)
    val category = TableModel(SchemaId("category"), QualifiedName(SqlIdentifier("category")), Vector(id, parent),
      Vector(id.id), Vector(ForeignKeyModel(Vector(parent.id), SchemaId("category"), Vector(id.id))))
    val label = ColumnModel(SchemaId("customer-category"), SqlIdentifier("category_id"), SqlType.BigInt)
    val labelled = customer.copy(columns = customer.columns :+ label,
      foreignKeys = Vector(ForeignKeyModel(Vector(label.id), category.id, Vector(id.id))))
    assertEquals(DiffEngine.diff(initial, SchemaModel(Vector(labelled, category))), Right(Vector(
      SchemaOperation.AddColumn(customer.id, customer.name, label),
      SchemaOperation.CreateTable(category.copy(foreignKeys = Vector.empty)),
      SchemaOperation.AddForeignKey(category.id, category.name, Vector(SqlIdentifier("parent_id")),
        category.name, Vector(SqlIdentifier("id"))),
      SchemaOperation.AddForeignKey(customer.id, customer.name, Vector(SqlIdentifier("category_id")),
        category.name, Vector(SqlIdentifier("id")))
    )))
  }

  test("renames keep foreign keys; dropping or changing them requires manual migration") {
    val id = ColumnModel(SchemaId("category/id"), SqlIdentifier("id"), SqlType.BigInt, nullable = false)
    val other = ColumnModel(SchemaId("category/other"), SqlIdentifier("other_id"), SqlType.BigInt, nullable = false)
    val category = TableModel(SchemaId("category"), QualifiedName(SqlIdentifier("category")), Vector(id, other), Vector(id.id))
    val link = ColumnModel(SchemaId("customer-category"), SqlIdentifier("category_id"), SqlType.BigInt)
    val key = ForeignKeyModel(Vector(link.id), category.id, Vector(id.id))
    val linked = customer.copy(columns = customer.columns :+ link, foreignKeys = Vector(key))
    val before = SchemaModel(Vector(linked, category))
    val renamed = SchemaModel(Vector(linked, category.copy(name = QualifiedName(SqlIdentifier("categories")),
      columns = Vector(id.copy(name = SqlIdentifier("category_id")), other))))
    assertEquals(DiffEngine.diff(before, renamed).map(_.map(_.getClass.getSimpleName)),
      Right(Vector("RenameTable", "RenameColumn")))
    val dropped = SchemaModel(Vector(linked.copy(foreignKeys = Vector.empty), category))
    assert(errors(DiffEngine.diff(before, dropped)).exists(_.contains("Dropping foreign key (customer-category)")))
    val otherKey = category.copy(primaryKey = Vector(other.id))
    val changed = SchemaModel(Vector(linked.copy(foreignKeys = Vector(key.copy(referencedColumns = Vector(other.id)))), otherKey))
    assert(errors(DiffEngine.diff(before, changed)).exists(_.contains("Changing foreign key (customer-category)")))
  }

  test("unique keys are created with a new table or added to an existing one; dropping them is refused") {
    val code = column("customer-code", "code")
    val email = column("customer-email", "email")
    val unique = customer.copy(columns = customer.columns ++ Vector(code, email),
      uniqueKeys = Vector(UniqueKeyModel(Vector(email.id)), UniqueKeyModel(Vector(code.id, customer.columns.head.id))))
    val tag = table("tag", "tag", column("tag-name", "name")).copy(uniqueKeys = Vector(UniqueKeyModel(Vector(SchemaId("tag-name")))))
    assertEquals(DiffEngine.diff(initial, SchemaModel(Vector(unique, tag))), Right(Vector(
      SchemaOperation.AddColumn(customer.id, customer.name, code),
      SchemaOperation.AddColumn(customer.id, customer.name, email),
      SchemaOperation.AddUniqueKey(customer.id, customer.name, Vector(SqlIdentifier("code"), SqlIdentifier("name"))),
      SchemaOperation.AddUniqueKey(customer.id, customer.name, Vector(SqlIdentifier("email"))),
      SchemaOperation.CreateTable(tag)
    )))
    val before = SchemaModel(Vector(unique))
    val renamed = SchemaModel(Vector(unique.copy(columns = unique.columns.map(c => c.copy(name = SqlIdentifier(c.name.value + "_new"))))))
    assert(DiffEngine.diff(before, renamed).toOption.get.forall(_.isInstanceOf[SchemaOperation.RenameColumn]))
    val dropped = SchemaModel(Vector(unique.copy(uniqueKeys = unique.uniqueKeys.take(1))))
    assert(errors(DiffEngine.diff(before, dropped)).exists(_.contains("Dropping unique key (customer-code, customer-name)")))
  }

  test("renames expose their locking risk") {
    val renamed = customer.name.copy(name = SqlIdentifier("renamed"))
    assertEquals(SchemaOperation.RenameTable(customer.id, customer.name, renamed).risk, RiskLevel.Locking)
    assertEquals(SchemaOperation.RenameColumn(customer.id, customer.name, customer.columns.head.id,
      SqlIdentifier("name"), SqlIdentifier("renamed")).risk, RiskLevel.Locking)
  }
