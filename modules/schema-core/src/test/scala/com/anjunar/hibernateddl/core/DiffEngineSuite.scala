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

  test("a dropped table or column cannot hand its name to a new one in the same plan") {
    val unrelated = SchemaModel(Vector(table("new-customer", "customer", column("new-name", "name"))))
    assert(errors(DiffEngine.diff(initial, unrelated)).exists(_.contains("uses the previous name of 'customer'")))
    val replacedColumn = SchemaModel(Vector(customer.copy(columns = Vector(column("replacement", "name")))))
    assert(errors(DiffEngine.diff(initial, replacedColumn)).exists(_.contains("uses an occupied previous name")))
  }

  test("drops come last: columns, then all tables in one operation, then sequences; keys over dropped columns go with them") {
    val id = ColumnModel(SchemaId("category/id"), SqlIdentifier("id"), SqlType.BigInt, nullable = false)
    val category = TableModel(SchemaId("category"), QualifiedName(SqlIdentifier("category")), Vector(id), Vector(id.id))
    val tag = table("tag", "tag", column("tag-name", "name"))
    val link = ColumnModel(SchemaId("customer-category"), SqlIdentifier("category_id"), SqlType.BigInt)
    val code = column("customer-code", "code")
    val linked = customer.copy(columns = customer.columns ++ Vector(link, code),
      foreignKeys = Vector(ForeignKeyModel(Vector(link.id), category.id, Vector(id.id))),
      uniqueKeys = Vector(UniqueKeyModel(Vector(code.id, link.id))),
      indexes = Vector(IndexModel(Vector(IndexColumn(link.id)))))
    val sequence = SequenceModel(SchemaId("customer-sequence"), QualifiedName(SqlIdentifier("customer_seq")), 1, 50)
    val before = SchemaModel(Vector(linked, category, tag), Vector(sequence))
    val note = column("customer-note", "note")
    val renamed = QualifiedName(SqlIdentifier("customers"))
    val after = SchemaModel(Vector(customer.copy(name = renamed, columns = customer.columns ++ Vector(code, note))))
    assertEquals(DiffEngine.diff(before, after), Right(Vector(
      SchemaOperation.RenameTable(customer.id, customer.name, renamed),
      SchemaOperation.AddColumn(customer.id, renamed, note),
      SchemaOperation.DropColumn(customer.id, renamed, link.id, link.name),
      SchemaOperation.DropTables(Vector(SchemaOperation.DroppedTable(category.id, category.name),
        SchemaOperation.DroppedTable(tag.id, tag.name))),
      SchemaOperation.DropSequence(sequence.id, sequence.name)
    )))
    assert(DiffEngine.diff(before, after).toOption.get.takeRight(3).forall(_.risk == RiskLevel.Destructive))
    val keptColumn = SchemaModel(Vector(linked.copy(foreignKeys = Vector.empty), tag), Vector(sequence))
    assert(errors(DiffEngine.diff(before, keptColumn)).exists(_.contains("Dropping foreign key (customer-category)")))
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

  test("a new required column is added nullable and made required after foreign keys, before drops") {
    val extra = column("new-note", "note").copy(nullable = false)
    val gone = column("customer-gone", "gone")
    val before = SchemaModel(Vector(customer.copy(columns = customer.columns :+ gone)))
    val renamed = customer.name.copy(name = SqlIdentifier("customers"))
    val after = SchemaModel(Vector(customer.copy(name = renamed, columns = customer.columns :+ extra)))
    assertEquals(DiffEngine.diff(before, after), Right(Vector(
      SchemaOperation.RenameTable(customer.id, customer.name, renamed),
      SchemaOperation.AddColumn(customer.id, renamed, extra.copy(nullable = true)),
      SchemaOperation.SetNotNull(customer.id, renamed, extra.id, extra.name),
      SchemaOperation.DropColumn(customer.id, renamed, gone.id, gone.name)
    )))
    val identity = extra.copy(dataType = SqlType.BigInt, identity = true)
    assert(errors(DiffEngine.diff(initial, SchemaModel(Vector(customer.copy(columns = customer.columns :+ identity)))))
      .exists(_.contains("Adding identity column 'new-note' to existing table 'customer' is unsupported")))
  }

  test("a column becomes required or optional; a type change is still refused alongside a rename") {
    val name = customer.columns.head
    val required = SchemaModel(Vector(customer.copy(columns = Vector(name.copy(name = SqlIdentifier("full"), nullable = false)))))
    assertEquals(DiffEngine.diff(initial, required), Right(Vector(
      SchemaOperation.RenameColumn(customer.id, customer.name, name.id, name.name, SqlIdentifier("full")),
      SchemaOperation.SetNotNull(customer.id, customer.name, name.id, SqlIdentifier("full"))
    )))
    assertEquals(DiffEngine.diff(required, initial), Right(Vector(
      SchemaOperation.RenameColumn(customer.id, customer.name, name.id, SqlIdentifier("full"), name.name),
      SchemaOperation.DropNotNull(customer.id, customer.name, name.id, name.name)
    )))
    val retyped = customer.copy(columns = Vector(name.copy(name = SqlIdentifier("renamed"), dataType = SqlType.Text)))
    assert(errors(DiffEngine.diff(initial, SchemaModel(Vector(retyped)))).exists(_.contains("Changing type")))
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

  test("indexes are created after their table or added to an existing one; dropping or changing them is refused") {
    val code = column("customer-code", "code")
    val nameIndex = IndexModel(Vector(IndexColumn(customer.columns.head.id)))
    val codeIndex = IndexModel(Vector(IndexColumn(code.id, descending = true), IndexColumn(customer.columns.head.id)))
    val indexed = customer.copy(columns = customer.columns :+ code, indexes = Vector(codeIndex, nameIndex))
    val tag = table("tag", "tag", column("tag-name", "name")).copy(indexes = Vector(IndexModel(Vector(IndexColumn(SchemaId("tag-name"))))))
    assertEquals(DiffEngine.diff(initial, SchemaModel(Vector(indexed, tag))), Right(Vector(
      SchemaOperation.AddColumn(customer.id, customer.name, code),
      SchemaOperation.CreateIndex(customer.id, customer.name, Vector(
        SchemaOperation.IndexedColumn(SqlIdentifier("code"), descending = true),
        SchemaOperation.IndexedColumn(SqlIdentifier("name"), descending = false))),
      SchemaOperation.CreateIndex(customer.id, customer.name, Vector(SchemaOperation.IndexedColumn(SqlIdentifier("name"), false))),
      SchemaOperation.CreateTable(tag.copy(indexes = Vector.empty)),
      SchemaOperation.CreateIndex(tag.id, tag.name, Vector(SchemaOperation.IndexedColumn(SqlIdentifier("name"), false)))
    )))
    val before = SchemaModel(Vector(indexed))
    val flipped = SchemaModel(Vector(indexed.copy(indexes = Vector(codeIndex, IndexModel(Vector(IndexColumn(customer.columns.head.id, true)))))))
    val diagnostics = errors(DiffEngine.diff(before, flipped))
    assert(diagnostics.exists(_.contains("Dropping index (customer-name) from table 'customer'")), diagnostics)
  }

  test("changed column checks are replaced, also together with a rename, and new columns carry theirs") {
    val status = column("customer-status", "status").copy(check = Some(ColumnCheck.AllowedValues(Vector("NEW"))))
    val before = SchemaModel(Vector(customer.copy(columns = customer.columns :+ status)))
    val widened = status.copy(name = SqlIdentifier("state"), check = Some(ColumnCheck.AllowedValues(Vector("NEW", "OLD"))))
    val level = column("customer-level", "level").copy(dataType = SqlType.SmallInt, check = Some(ColumnCheck.Range(0, 2)))
    val after = SchemaModel(Vector(customer.copy(columns = customer.columns ++ Vector(widened, level))))
    assertEquals(DiffEngine.diff(before, after), Right(Vector(
      SchemaOperation.RenameColumn(customer.id, customer.name, status.id, SqlIdentifier("status"), SqlIdentifier("state")),
      SchemaOperation.ChangeCheck(customer.id, customer.name, status.id, SqlIdentifier("state"), status.check, widened.check),
      SchemaOperation.AddColumn(customer.id, customer.name, level)
    )))
    val unchecked = SchemaModel(Vector(customer.copy(columns = customer.columns :+ status.copy(check = None))))
    assertEquals(DiffEngine.diff(before, unchecked), Right(Vector(
      SchemaOperation.ChangeCheck(customer.id, customer.name, status.id, SqlIdentifier("status"), status.check, None)
    )))
  }

  test("sequences are created or renamed first and dropped last; changing or moving them is refused") {
    val sequence = SequenceModel(SchemaId("customer-sequence"), QualifiedName(SqlIdentifier("customer_seq")), 1, 50)
    val renamed = sequence.copy(name = QualifiedName(SqlIdentifier("client_seq")))
    assertEquals(DiffEngine.diff(initial, SchemaModel(initial.tables, Vector(sequence))),
      Right(Vector(SchemaOperation.CreateSequence(sequence))))
    val before = SchemaModel(initial.tables, Vector(sequence))
    assertEquals(DiffEngine.diff(before, SchemaModel(initial.tables, Vector(renamed))),
      Right(Vector(SchemaOperation.RenameSequence(sequence.id, sequence.name, renamed.name))))
    assertEquals(DiffEngine.diff(before, initial), Right(Vector(SchemaOperation.DropSequence(sequence.id, sequence.name))))
    assert(errors(DiffEngine.diff(before, SchemaModel(initial.tables, Vector(sequence.copy(increment = 1)))))
      .exists(_.contains("Changing start or increment")))
    assert(errors(DiffEngine.diff(before, SchemaModel(initial.tables,
      Vector(sequence.copy(name = sequence.name.copy(schema = Some(SqlIdentifier("other"))))))))
      .exists(_.contains("Moving sequence")))
    val taken = SequenceModel(SchemaId("other-sequence"), customer.name, 1, 1)
    val movedAway = Vector(customer.copy(name = QualifiedName(SqlIdentifier("customers"))))
    assert(errors(DiffEngine.diff(initial, SchemaModel(movedAway, Vector(taken))))
      .exists(_.contains("Creating sequence 'other-sequence' uses the previous name of 'customer'")))
    val identity = customer.columns.head.copy(dataType = SqlType.BigInt, nullable = false)
    assert(errors(DiffEngine.diff(SchemaModel(Vector(customer.copy(columns = Vector(identity)))),
      SchemaModel(Vector(customer.copy(columns = Vector(identity.copy(identity = true)))))))
      .exists(_.contains("Changing identity generation")))
  }

  test("renames expose their locking risk") {
    val renamed = customer.name.copy(name = SqlIdentifier("renamed"))
    assertEquals(SchemaOperation.RenameTable(customer.id, customer.name, renamed).risk, RiskLevel.Locking)
    assertEquals(SchemaOperation.RenameColumn(customer.id, customer.name, customer.columns.head.id,
      SqlIdentifier("name"), SqlIdentifier("renamed")).risk, RiskLevel.Locking)
  }

  private val retypedTable = table("retyped", "retyped",
    column("r-name", "name"),
    column("r-count", "visits").copy(dataType = SqlType.Integer),
    column("r-balance", "balance").copy(dataType = SqlType.Numeric(10, 2), nullable = false))

  private def retype(changes: (String, SqlType)*): TableModel =
    retypedTable.copy(columns = retypedTable.columns.map { column =>
      changes.collectFirst { case (id, dataType) if id == column.id.value => column.copy(dataType = dataType) }.getOrElse(column)
    })

  test("the three widenings change the column type in place, without dropping or adding a column") {
    val before = SchemaModel(Vector(retypedTable))
    val after = SchemaModel(Vector(retype("r-name" -> SqlType.Varchar(255), "r-count" -> SqlType.BigInt,
      "r-balance" -> SqlType.Numeric(14, 2))))
    def change(id: String, name: String, from: SqlType, to: SqlType) =
      SchemaOperation.ChangeColumnType(retypedTable.id, retypedTable.name, SchemaId(id), SqlIdentifier(name), from, to)
    assertEquals(DiffEngine.diff(before, after), Right(Vector(
      change("r-balance", "balance", SqlType.Numeric(10, 2), SqlType.Numeric(14, 2)),
      change("r-count", "visits", SqlType.Integer, SqlType.BigInt),
      change("r-name", "name", SqlType.Varchar(100), SqlType.Varchar(255))
    )))
    assertEquals(DiffEngine.diff(before, before), Right(Vector.empty))
    assertEquals(change("r-name", "name", SqlType.Varchar(100), SqlType.Varchar(255)).risk, RiskLevel.Locking)
  }

  test("a version jump is judged directly between the stored and the current type") {
    val before = SchemaModel(Vector(retypedTable))
    assertEquals(DiffEngine.diff(before, SchemaModel(Vector(retype("r-name" -> SqlType.Varchar(500))))).map(_.size), Right(1))
  }

  test("refused type changes name both types and the concrete reason") {
    val before = SchemaModel(Vector(retypedTable))
    Vector(
      Vector("r-name" -> SqlType.Varchar(50)) -> "from Varchar(100) to Varchar(50) is unsupported: the change shortens VARCHAR",
      Vector("r-count" -> SqlType.SmallInt) -> "from Integer to SmallInt is unsupported: the change is not a supported widening",
      Vector("r-balance" -> SqlType.Numeric(14, 4)) -> "the change changes the NUMERIC scale from 2 to 4",
      Vector("r-name" -> SqlType.Uuid) -> "from Varchar(100) to Uuid is unsupported"
    ).foreach { (changes, reason) =>
      val diagnostics = errors(DiffEngine.diff(before, SchemaModel(Vector(retype(changes*)))))
      assert(diagnostics.exists(message => message.startsWith("Changing type of column") && message.contains(reason)), diagnostics)
    }
    val narrowed = SchemaModel(Vector(retype("r-count" -> SqlType.BigInt)))
    assert(errors(DiffEngine.diff(narrowed, before)).exists(_.contains("narrows BIGINT to INTEGER")))
  }

  test("columns of primary keys, foreign keys on either side and identities keep their type; other columns may change") {
    val id = column("p-id", "id").copy(dataType = SqlType.Integer, nullable = false)
    val code = column("p-code", "code")
    val parent = table("parent", "parent", id, code).copy(primaryKey = Vector(id.id))
    val parentId = column("c-parent", "parent_id").copy(dataType = SqlType.Integer)
    val counter = column("c-counter", "counter").copy(dataType = SqlType.Integer, nullable = false, identity = true)
    val child = table("child", "child", parentId, counter)
      .copy(foreignKeys = Vector(ForeignKeyModel(Vector(parentId.id), parent.id, Vector(id.id))))
    val before = SchemaModel(Vector(parent, child))
    def widened(dataType: SqlType, columnIds: SchemaId*) = SchemaModel(Vector(parent, child).map { t =>
      t.copy(columns = t.columns.map(c => if columnIds.contains(c.id) then c.copy(dataType = dataType) else c))
    })
    // Both sides of a foreign key must keep equal types, so the key and its reference widen together.
    val keyErrors = errors(DiffEngine.diff(before, widened(SqlType.BigInt, id.id, parentId.id)))
    assert(keyErrors.exists(e => e.contains("'p-id'") && e.contains("belongs to a primary key and the column belongs to a " +
      "foreign key or is referenced by one")), keyErrors)
    assert(keyErrors.exists(e => e.contains("'c-parent'") && e.contains("belongs to a foreign key")), keyErrors)
    assert(errors(DiffEngine.diff(before, widened(SqlType.BigInt, counter.id))).exists(_.contains("identity column")))
    // A key in only one of the models counts as well: here the foreign key is new in the target.
    val unreferenced = SchemaModel(Vector(parent.copy(primaryKey = Vector.empty), child.copy(foreignKeys = Vector.empty)))
    val newKey = errors(DiffEngine.diff(unreferenced, widened(SqlType.BigInt, id.id, parentId.id)))
    assert(newKey.exists(e => e.contains("'c-parent'") && e.contains("belongs to a foreign key")), newKey)
    assertEquals(DiffEngine.diff(before, widened(SqlType.Varchar(200), code.id)), Right(Vector(
      SchemaOperation.ChangeColumnType(parent.id, parent.name, code.id, code.name, SqlType.Varchar(100), SqlType.Varchar(200)))))
  }

  test("a type change removes the column's check first and sets the target check once afterwards") {
    val level = column("r-count", "visits").copy(dataType = SqlType.Integer, check = Some(ColumnCheck.Range(0, 3)))
    val before = SchemaModel(Vector(retypedTable.copy(columns = Vector(level))))
    def after(check: Option[ColumnCheck]) = SchemaModel(Vector(retypedTable.copy(columns = Vector(
      level.copy(dataType = SqlType.BigInt, check = check)))))
    def checkChange(from: Option[ColumnCheck], to: Option[ColumnCheck]) =
      SchemaOperation.ChangeCheck(retypedTable.id, retypedTable.name, level.id, level.name, from, to)
    val retype = SchemaOperation.ChangeColumnType(retypedTable.id, retypedTable.name, level.id, level.name,
      SqlType.Integer, SqlType.BigInt)
    val same = level.check
    val wider = Some(ColumnCheck.Range(0, 5000000000L))
    assertEquals(DiffEngine.diff(before, after(same)), Right(Vector(checkChange(same, None), retype, checkChange(None, same))))
    assertEquals(DiffEngine.diff(before, after(wider)), Right(Vector(checkChange(same, None), retype, checkChange(None, wider))))
    assertEquals(DiffEngine.diff(before, after(None)), Right(Vector(checkChange(same, None), retype)))
    val unchecked = SchemaModel(Vector(retypedTable.copy(columns = Vector(level.copy(check = None)))))
    assertEquals(DiffEngine.diff(unchecked, after(wider)), Right(Vector(retype, checkChange(None, wider))))
  }

  test("renames come first, so the type change uses the new table and column names") {
    val before = SchemaModel(Vector(retypedTable))
    val renamed = retype("r-name" -> SqlType.Varchar(255))
    val newName = QualifiedName(SqlIdentifier("Renamed \"Table\""))
    val after = SchemaModel(Vector(renamed.copy(name = newName, columns = renamed.columns.map { c =>
      if c.id.value == "r-name" then c.copy(name = SqlIdentifier("Display \"Name\"")) else c
    })))
    assertEquals(DiffEngine.diff(before, after), Right(Vector(
      SchemaOperation.RenameTable(retypedTable.id, retypedTable.name, newName),
      SchemaOperation.RenameColumn(retypedTable.id, newName, SchemaId("r-name"), SqlIdentifier("name"),
        SqlIdentifier("Display \"Name\"")),
      SchemaOperation.ChangeColumnType(retypedTable.id, newName, SchemaId("r-name"), SqlIdentifier("Display \"Name\""),
        SqlType.Varchar(100), SqlType.Varchar(255))
    )))
  }
