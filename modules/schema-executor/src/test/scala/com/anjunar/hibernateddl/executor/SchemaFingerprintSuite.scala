package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*

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
      column.copy(dataType = SqlType.Boolean), column.copy(dataType = SqlType.Text),
      column.copy(dataType = SqlType.Uuid), column.copy(dataType = SqlType.Timestamp(6)),
      column.copy(dataType = SqlType.Timestamp(3)), column.copy(dataType = SqlType.TimestampWithTimeZone(6)),
      column.copy(dataType = SqlType.Char(100)), column.copy(dataType = SqlType.Char(101)),
      column.copy(dataType = SqlType.Numeric(38, 2)), column.copy(dataType = SqlType.Numeric(38, 3)),
      column.copy(dataType = SqlType.Numeric(39, 2)), column.copy(dataType = SqlType.Time(0)),
      column.copy(dataType = SqlType.Time(6)), column.copy(dataType = SqlType.SmallInt),
      column.copy(dataType = SqlType.Real), column.copy(dataType = SqlType.DoublePrecision),
      column.copy(dataType = SqlType.Date), column.copy(dataType = SqlType.Binary)
    ).map(c => table.copy(columns = Vector(c)))
    val variants = changedTables.map(t => SchemaModel(Vector(t))) :+ SchemaModel(Vector.empty)
    val hashes = variants.map(SchemaFingerprint.of) :+ SchemaFingerprint.of(model)
    assertEquals(hashes.distinct.size, hashes.size)
    assert(hashes.forall(_.matches("[0-9a-f]{64}")))
  }

  private val keyed =
    val id = ColumnModel(SchemaId("t/id"), SqlIdentifier("id"), SqlType.Uuid, nullable = false)
    SchemaModel(Vector(TableModel(SchemaId("t"), QualifiedName(SqlIdentifier("t"), Some(SqlIdentifier("public"))),
      Vector(id, ColumnModel(SchemaId("t/at"), SqlIdentifier("at"), SqlType.Timestamp(6)),
        ColumnModel(SchemaId("t/name"), SqlIdentifier("name"), SqlType.Varchar(80))), Vector(id.id))))

  test("models without foreign keys keep the fingerprints that earlier versions stored") {
    assertEquals(SchemaFingerprint.of(keyed), "085d2a77eff81e4854196e811715cfedfb1248fa6809e4cf86d5875f14cc990b")
  }

  test("models with foreign keys but without unique keys keep the fingerprints that earlier versions stored") {
    val table = keyed.tables.head
    val parent = ColumnModel(SchemaId("t/parent"), SqlIdentifier("parent"), SqlType.Uuid)
    val model = SchemaModel(Vector(table.copy(columns = Vector(table.columns.head, parent),
      foreignKeys = Vector(ForeignKeyModel(Vector(parent.id), table.id, table.primaryKey)))))
    assertEquals(SchemaFingerprint.of(model), "21ad02d6ea5bab3bdc4eac7924a109696d92ef1fc741f5070e04d581e5d18aa4")
  }

  test("models with unique keys but without indexes keep the fingerprints that earlier versions stored") {
    val table = keyed.tables.head
    val parent = ColumnModel(SchemaId("t/parent"), SqlIdentifier("parent"), SqlType.Uuid)
    val model = SchemaModel(Vector(table.copy(columns = Vector(table.columns.head, parent),
      foreignKeys = Vector(ForeignKeyModel(Vector(parent.id), table.id, table.primaryKey)),
      uniqueKeys = Vector(UniqueKeyModel(Vector(parent.id))))))
    assertEquals(SchemaFingerprint.of(model), "1f76c2babda21275093a280117c171471900dd4fb6ab259d7fc1b655119b951e")
  }

  test("models with indexes but without column checks keep the fingerprints that earlier versions stored") {
    val table = keyed.tables.head
    val parent = ColumnModel(SchemaId("t/parent"), SqlIdentifier("parent"), SqlType.Uuid)
    val model = SchemaModel(Vector(table.copy(columns = Vector(table.columns.head, parent),
      foreignKeys = Vector(ForeignKeyModel(Vector(parent.id), table.id, table.primaryKey)),
      uniqueKeys = Vector(UniqueKeyModel(Vector(parent.id))),
      indexes = Vector(IndexModel(Vector(IndexColumn(parent.id, descending = true)))))))
    assertEquals(SchemaFingerprint.of(model), "2e078d3ab32f3cd52cb02314f213bcce5e9d22aba3700cd4ff457cb1d883afb7")
  }

  test("column checks change the fingerprint") {
    val table = keyed.tables.head
    def withCheck(check: Option[ColumnCheck]) =
      SchemaFingerprint.of(SchemaModel(Vector(table.copy(columns = table.columns.map(c =>
        if c.id == SchemaId("t/name") then c.copy(check = check) else c)))))
    val fingerprints = Vector(None, Some(ColumnCheck.AllowedValues(Vector("A"))), Some(ColumnCheck.AllowedValues(Vector("A", "B"))),
      Some(ColumnCheck.AllowedValues(Vector("B", "A"))), Some(ColumnCheck.Range(0, 1)), Some(ColumnCheck.Range(0, 2))).map(withCheck)
    assertEquals(fingerprints.head, SchemaFingerprint.of(keyed))
    assertEquals(fingerprints.distinct.size, fingerprints.size)
  }

  test("indexes and their directions change the fingerprint, but their order does not") {
    val table = keyed.tables.head
    val indexes = table.columns.drop(1).map(column => IndexModel(Vector(IndexColumn(column.id))))
    val fingerprint = SchemaFingerprint.of(SchemaModel(Vector(table.copy(indexes = indexes))))
    assertNotEquals(fingerprint, SchemaFingerprint.of(keyed))
    assertEquals(SchemaFingerprint.of(SchemaModel(Vector(table.copy(indexes = indexes.reverse)))), fingerprint)
    val descending = indexes.map(index => index.copy(columns = index.columns.map(_.copy(descending = true))))
    assertNotEquals(SchemaFingerprint.of(SchemaModel(Vector(table.copy(indexes = descending)))), fingerprint)
    val asUniqueKeys = table.columns.drop(1).map(column => UniqueKeyModel(Vector(column.id)))
    assertNotEquals(SchemaFingerprint.of(SchemaModel(Vector(table.copy(uniqueKeys = asUniqueKeys)))), fingerprint)
  }

  test("unique keys change the fingerprint, but their order does not") {
    val table = keyed.tables.head
    val keys = table.columns.drop(1).map(column => UniqueKeyModel(Vector(column.id)))
    val fingerprint = SchemaFingerprint.of(SchemaModel(Vector(table.copy(uniqueKeys = keys))))
    assertNotEquals(fingerprint, SchemaFingerprint.of(keyed))
    assertEquals(SchemaFingerprint.of(SchemaModel(Vector(table.copy(uniqueKeys = keys.reverse)))), fingerprint)
    assertNotEquals(SchemaFingerprint.of(SchemaModel(Vector(table.copy(uniqueKeys = keys.take(1))))), fingerprint)
    val composite = UniqueKeyModel(table.columns.drop(1).map(_.id))
    assertNotEquals(SchemaFingerprint.of(SchemaModel(Vector(table.copy(uniqueKeys = Vector(composite))))), fingerprint)
  }

  test("foreign keys change the fingerprint, but their order does not") {
    val table = keyed.tables.head
    val parent = ColumnModel(SchemaId("t/parent"), SqlIdentifier("parent"), SqlType.Uuid)
    val other = ColumnModel(SchemaId("t/other"), SqlIdentifier("other"), SqlType.Uuid)
    val keys = Vector(parent, other).map(c => ForeignKeyModel(Vector(c.id), table.id, table.primaryKey))
    val linked = table.copy(columns = table.columns ++ Vector(parent, other), foreignKeys = keys)
    val unlinked = SchemaFingerprint.of(SchemaModel(Vector(linked.copy(foreignKeys = Vector.empty))))
    val fingerprint = SchemaFingerprint.of(SchemaModel(Vector(linked)))
    assertNotEquals(fingerprint, unlinked)
    assertEquals(SchemaFingerprint.of(SchemaModel(Vector(linked.copy(foreignKeys = keys.reverse)))), fingerprint)
    assertNotEquals(SchemaFingerprint.of(SchemaModel(Vector(linked.copy(foreignKeys = keys.take(1))))), fingerprint)
  }

  test("length-prefixing distinguishes delimiter-containing IDs and names") {
    val left = SchemaModel(Vector(table.copy(id = SchemaId("a|b"), name = QualifiedName(SqlIdentifier("c")))))
    val right = SchemaModel(Vector(table.copy(id = SchemaId("a"), name = QualifiedName(SqlIdentifier("b|c")))))
    assertNotEquals(SchemaFingerprint.of(left), SchemaFingerprint.of(right))
  }
