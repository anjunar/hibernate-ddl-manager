package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.core.*

class HibernateSchemaSourceSuite extends munit.FunSuite:
  private def read(classes: Class[?]*): Either[Vector[String], SchemaModel] = TestMetadata.read(classes*)

  private def errors(classes: Class[?]*): Vector[String] =
    read(classes*).swap.getOrElse(fail("Expected diagnostics"))

  test("entities become tables with stable, entity-scoped column IDs and a primary key") {
    val table = read(classOf[Customer]).toOption.get.tables.head
    assertEquals(table.id, SchemaId("7f3a9c21"))
    assertEquals(table.name, QualifiedName(SqlIdentifier("customer"), Some(SqlIdentifier("public"))))
    assertEquals(table.primaryKey, Vector(SchemaId("7f3a9c21/0a1b2c3d")))
    assertEquals(
      table.columns.map(c => c.id.value -> (c.name.value, c.dataType, c.nullable)).toMap,
      Map(
        "7f3a9c21/0a1b2c3d" -> ("id", SqlType.BigInt, false),
        "7f3a9c21/f34e45b6" -> ("nick_name", SqlType.Varchar(80), true),
        "7f3a9c21/1c2d3e4f" -> ("LoginName", SqlType.Varchar(255), false),
        "7f3a9c21/2d3e4f5a" -> ("active", SqlType.Boolean, false),
        "7f3a9c21/6b7c8d9e" -> ("visits", SqlType.Integer, true),
        "7f3a9c21/3e4f5a6b/5a6b7c8d" -> ("billing_street", SqlType.Varchar(255), true),
        "7f3a9c21/4f5a6b7c/5a6b7c8d" -> ("shipping_street", SqlType.Varchar(255), true)
      )
    )
  }

  test("renaming classes, tables and columns keeps IDs, so the diff plans renames") {
    val before = read(classOf[LegacyCustomer]).toOption.get
    val after = read(classOf[Client]).toOption.get
    val table = after.tables.head
    assertEquals(DiffEngine.diff(before, after), Right(Vector(
      SchemaOperation.RenameTable(table.id, before.tables.head.name, table.name),
      SchemaOperation.RenameColumn(table.id, table.name, SchemaId("7f3a9c21/f34e45b6"),
        SqlIdentifier("nick_name"), SqlIdentifier("alias"))
    )))
  }

  test("associations become foreign keys on the referenced primary key; disabled constraints stay plain columns") {
    val model = read(classOf[Invoice], classOf[LegacyCustomer]).toOption.get
    val invoice = model.tables.find(_.id == SchemaId("5c6d7e8f")).get
    assertEquals(
      invoice.columns.map(c => c.id.value -> (c.name.value, c.dataType, c.nullable)).toMap,
      Map(
        "5c6d7e8f/0a1b2c3d" -> ("id", SqlType.BigInt, false),
        "5c6d7e8f/1d2e3f40" -> ("customer_id", SqlType.BigInt, false),
        "5c6d7e8f/2e3f4051" -> ("reviewer_id", SqlType.BigInt, true),
        "5c6d7e8f/3f405162" -> ("correction_id", SqlType.BigInt, true)
      )
    )
    assertEquals(invoice.foreignKeys.toSet, Set(
      ForeignKeyModel(Vector(SchemaId("5c6d7e8f/1d2e3f40")), SchemaId("7f3a9c21"), Vector(SchemaId("7f3a9c21/0a1b2c3d"))),
      ForeignKeyModel(Vector(SchemaId("5c6d7e8f/3f405162")), SchemaId("5c6d7e8f"), Vector(SchemaId("5c6d7e8f/0a1b2c3d")))
    ))
    assertEquals(SchemaValidation.validate(model), Vector.empty)
  }

  test("unique columns, unique constraints, natural IDs and one-to-one join columns become unique keys") {
    val model = read(classOf[Account], classOf[LegacyCustomer]).toOption.get
    val account = model.tables.find(_.id == SchemaId("6d7e8f90")).get
    def key(properties: String*) = UniqueKeyModel(properties.toVector.map(p => SchemaId(s"6d7e8f90/$p")))
    assertEquals(account.uniqueKeys.toSet, Set(
      key("1a2b3c4d"), key("2b3c4d5e", "3c4d5e6f"), key("4d5e6f70"), key("5e6f7081")
    ))
    assertEquals(account.foreignKeys.map(_.referencedTable), Vector(SchemaId("7f3a9c21")))
    assertEquals(SchemaValidation.validate(model), Vector.empty)
  }

  test("@Index becomes a plain index with column directions; a unique @Index becomes a unique key") {
    val shipment = read(classOf[Shipment]).toOption.get.tables.head
    def id(property: String) = SchemaId(s"7e8f90a1/$property")
    assertEquals(shipment.indexes.toSet, Set(
      IndexModel(Vector(IndexColumn(id("1b2c3d4e")))),
      IndexModel(Vector(IndexColumn(id("2c3d4e5f")), IndexColumn(id("1b2c3d4e"), descending = true)))
    ))
    assertEquals(shipment.uniqueKeys, Vector(UniqueKeyModel(Vector(id("3d4e5f60")))))
  }

  test("dates, times, numerics, floating point, small integers, characters and binaries are mapped") {
    val table = read(classOf[Measurement]).toOption.get.tables.head
    assertEquals(
      table.columns.map(c => c.name.value -> c.dataType).toMap,
      Map(
        "id" -> SqlType.SmallInt, "day" -> SqlType.Date, "takenat" -> SqlType.Time(0),
        "price" -> SqlType.Numeric(10, 2), "total" -> SqlType.Numeric(38, 0), "ratio" -> SqlType.DoublePrecision,
        "weight" -> SqlType.Real, "unit" -> SqlType.Char(1), "raw" -> SqlType.Binary, "level" -> SqlType.SmallInt
      )
    )
  }

  test("enum columns carry their allowed values or ordinal range as a column check") {
    val letter = read(classOf[Letter]).toOption.get.tables.head
    assertEquals(
      letter.columns.map(c => c.name.value -> (c.dataType, c.check)).toMap,
      Map(
        "id" -> (SqlType.BigInt, None),
        "status" -> (SqlType.Varchar(255), Some(ColumnCheck.AllowedValues(Vector("Draft", "Sent", "Paid")))),
        "priority" -> (SqlType.SmallInt, Some(ColumnCheck.Range(0, 2))),
        "Stage" -> (SqlType.Varchar(10), Some(ColumnCheck.AllowedValues(Vector("Draft", "Sent", "Paid"))))
      )
    )
  }

  test("checks other than Hibernate's enum checks are reported") {
    val diagnostics = errors(classOf[OddChecks])
    assert(diagnostics.exists(_.contains("OddChecks.quote has the check constraint")), diagnostics)
    assert(diagnostics.exists(_.contains("OddChecks.amount has the check constraint 'amount >= 0'")), diagnostics)
  }

  test("collections map to their own tables with IDs derived from the owning property") {
    val model = read(classOf[Article], classOf[Label]).toOption.get
    def table(id: String) = model.tables.find(_.id == SchemaId(id)).getOrElse(fail(s"No table $id in ${model.tables.map(_.id)}"))
    def ids(table: TableModel) = table.columns.map(c => c.id.value -> c.name.value).toMap
    val article = SchemaId("c3d4e5f6")
    val articleKey = Vector(SchemaId("c3d4e5f6/0a1b2c3d"))
    assertEquals(model.tables.size, 6)

    val keywords = table("c3d4e5f6/1b2c3d4e")
    assertEquals(keywords.name.name.value, "article_keyword")
    assertEquals(ids(keywords), Map("c3d4e5f6/1b2c3d4e/key" -> "article_id", "c3d4e5f6/1b2c3d4e/element" -> "keywords"))
    assertEquals(keywords.foreignKeys, Vector(ForeignKeyModel(Vector(SchemaId("c3d4e5f6/1b2c3d4e/key")), article, articleKey)))

    val lines = table("c3d4e5f6/2c3d4e5f")
    assertEquals(ids(lines), Map("c3d4e5f6/2c3d4e5f/key" -> "article_id",
      "c3d4e5f6/2c3d4e5f/6a7b8c9d" -> "lines_text", "c3d4e5f6/2c3d4e5f/7b8c9d0e" -> "lines_amount"))

    val labels = table("c3d4e5f6/3d4e5f60")
    val labelKey = SchemaId("c3d4e5f6/3d4e5f60/key")
    val labelElement = SchemaId("c3d4e5f6/3d4e5f60/element")
    assertEquals(labels.primaryKey.toSet, Set(labelKey, labelElement))
    assertEquals(labels.foreignKeys.toSet, Set(
      ForeignKeyModel(Vector(labelKey), article, articleKey),
      ForeignKeyModel(Vector(labelElement), SchemaId("b2c3d4e5"), Vector(SchemaId("b2c3d4e5/0a1b2c3d")))
    ))

    val statuses = table("c3d4e5f6/4e5f6071")
    assertEquals(statuses.columns.find(_.id == SchemaId("c3d4e5f6/4e5f6071/element")).flatMap(_.check),
      Some(ColumnCheck.AllowedValues(Vector("Draft", "Sent", "Paid"))))
    assertEquals(SchemaValidation.validate(model), Vector.empty)
  }

  test("collections sharing a table, ordered lists and maps are reported") {
    val diagnostics = errors(classOf[Crowded], classOf[Label], classOf[Article])
    assert(diagnostics.exists(d => d.contains("Crowded.favorites, ") && d.contains("Crowded.pinned use table Crowded_Label")),
      diagnostics)
    assert(diagnostics.exists(_.contains("Crowded.ranking is a list collection")), diagnostics)
  }

  test("renaming a referenced entity keeps the foreign key, so the diff plans only the rename") {
    val before = read(classOf[Invoice], classOf[LegacyCustomer]).toOption.get
    val after = before.copy(tables = before.tables.map { table =>
      if table.id == SchemaId("7f3a9c21") then table.copy(name = table.name.copy(name = SqlIdentifier("client"))) else table
    })
    assertEquals(DiffEngine.diff(before, after).map(_.map(_.getClass.getSimpleName)), Right(Vector("RenameTable")))
  }

  test("missing and readable IDs are rejected with a generated suggestion") {
    val diagnostics = errors(classOf[Unidentified])
    assertEquals(diagnostics.size, 3)
    assert(diagnostics.exists(_.startsWith("Entity Unidentified has @SchemaId(\"USER\"); expected eight lowercase hex digits")))
    assert(diagnostics.exists(_.startsWith("Unidentified.name has @SchemaId(\"abc\")")))
    assert(diagnostics.exists(_.matches("""Unidentified\.id has no @SchemaId; add e\.g\. @SchemaId\("[0-9a-f]{8}"\)""")))
  }

  test("IDs are unique per entity and across entities, but may repeat in different entities' properties") {
    assertEquals(errors(classOf[DuplicateA], classOf[DuplicateB]), Vector(
      "DuplicateA.id and DuplicateA.name share @SchemaId(\"0a1b2c3d\")",
      "Entities DuplicateA, DuplicateB share @SchemaId(\"aaaaaaaa\")"
    ))
  }

  test("mappings the model cannot represent are reported instead of dropped") {
    val diagnostics = errors(classOf[Unsupported], classOf[LegacyCustomer])
    assert(diagnostics.exists(_.contains("Unsupported.document has SQL type 'oid'")), diagnostics)
    assert(diagnostics.exists(_.contains("of entity Unsupported has ON DELETE CASCADE; unsupported")), diagnostics)
    assert(diagnostics.exists(_.contains("Unsupported.tags is a map collection")), diagnostics)
    assert(diagnostics.exists(_.contains("of entity Unsupported has options 'WHERE code IS NOT NULL'; unsupported")), diagnostics)
  }

  test("generated UUID keys and timestamps with and without time zone are mapped") {
    val table = read(classOf[Purchase]).toOption.get.tables.head
    assertEquals(table.primaryKey, Vector(SchemaId("9d8e7f60/0a1b2c3d")))
    assertEquals(
      table.columns.map(c => c.name.value -> (c.dataType, c.nullable)).toMap,
      Map(
        "id" -> (SqlType.Uuid, false),
        "createdat" -> (SqlType.Timestamp(6), false),
        "paidat" -> (SqlType.TimestampWithTimeZone(6), true),
        "shippedat" -> (SqlType.TimestampWithTimeZone(6), true),
        "deliveredat" -> (SqlType.Timestamp(3), true)
      )
    )
  }

  test("generated keys become key sequences or identity columns") {
    val model = read(classOf[Generated], classOf[Ticket], classOf[Voucher]).toOption.get
    assertEquals(model.sequences.toSet, Set(
      SequenceModel(SchemaId("cccccccc/0a1b2c3d/sequence"), QualifiedName(SqlIdentifier("generated_seq"), Some(SqlIdentifier("public"))), 1, 50),
      SequenceModel(SchemaId("f6071829/0a1b2c3d/sequence"), QualifiedName(SqlIdentifier("voucher_numbers"), Some(SqlIdentifier("public"))), 100, 10)
    ))
    val ticketKey = model.tables.find(_.id == SchemaId("e5f60718")).get.columns.head
    assertEquals((ticketKey.dataType, ticketKey.nullable, ticketKey.identity), (SqlType.BigInt, false, true))
    assert(model.tables.filterNot(_.id == SchemaId("e5f60718")).flatMap(_.columns).forall(!_.identity))
    assertEquals(SchemaValidation.validate(model), Vector.empty)
  }

  test("a sequence shared by several entity keys is reported") {
    assert(errors(classOf[Voucher], classOf[Coupon]).exists(_.contains(
      "Sequence voucher_numbers generates the keys of entities Coupon, Voucher; give each entity its own sequence")))
  }

  test("a single-table hierarchy shares one table with a discriminator; subclass columns carry the subclass ID") {
    val animal = read(classOf[Animal], classOf[Cat], classOf[Dog]).toOption.get.tables match
      case Vector(table) => table
      case tables => fail(s"Expected one table, got ${tables.map(_.id)}")
    assertEquals(animal.id, SchemaId("1a2b3c4e"))
    assertEquals(
      animal.columns.map(c => c.id.value -> (c.name.value, c.nullable)).toMap,
      Map(
        "1a2b3c4e/discriminator" -> ("dtype", false), "1a2b3c4e/0a1b2c3d" -> ("id", false),
        "1a2b3c4e/1b2c3d4e" -> ("name", true), "2b3c4d5f/2c3d4e5f" -> ("lives", true), "3c4d5e60/3d4e5f60" -> ("breed", true)
      )
    )
    assertEquals(animal.columns.find(_.id == SchemaId("1a2b3c4e/discriminator")).flatMap(_.check),
      Some(ColumnCheck.AllowedValues(Vector("Animal", "Cat", "Dog"))))
  }

  test("a joined subclass has its own table whose key references the parent table") {
    val model = read(classOf[Vehicle], classOf[Car]).toOption.get
    val car = model.tables.find(_.id == SchemaId("5e6f7082")).get
    assertEquals(car.columns.map(c => c.id.value -> c.name.value).toMap,
      Map("5e6f7082/key" -> "id", "5e6f7082/2c3d4e5f" -> "seats"))
    assertEquals(car.primaryKey, Vector(SchemaId("5e6f7082/key")))
    assertEquals(car.foreignKeys, Vector(ForeignKeyModel(Vector(SchemaId("5e6f7082/key")), SchemaId("4d5e6f71"),
      Vector(SchemaId("4d5e6f71/0a1b2c3d")))))
    assertEquals(SchemaValidation.validate(model), Vector.empty)
  }

  test("table-per-class subclasses repeat inherited columns under their own ID and share the root's sequence") {
    val model = read(classOf[Payment], classOf[CardPayment], classOf[TransferPayment]).toOption.get
    assertEquals(model.tables.map(_.id).toSet, Set(SchemaId("708192a4"), SchemaId("8192a3b5")))
    val card = model.tables.find(_.id == SchemaId("708192a4")).get
    assertEquals(card.columns.map(c => c.id.value -> c.name.value).toMap,
      Map("708192a4/0a1b2c3d" -> "id", "708192a4/1b2c3d4e" -> "amount", "708192a4/2c3d4e5f" -> "card"))
    assertEquals(model.sequences.map(s => s.id.value -> s.name.name.value), Vector("6f708193/0a1b2c3d/sequence" -> "payment_seq"))
  }

  test("fresh IDs have the required format") {
    assert(Vector.fill(20)(HibernateSchemaSource.newId()).forall(_.matches("[0-9a-f]{8}")))
  }
