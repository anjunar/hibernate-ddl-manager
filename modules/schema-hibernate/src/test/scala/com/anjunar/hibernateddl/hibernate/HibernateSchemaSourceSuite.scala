package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.core.*
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder

class HibernateSchemaSourceSuite extends munit.FunSuite:
  private def read(classes: Class[?]*): Either[Vector[String], SchemaModel] =
    val registry = new StandardServiceRegistryBuilder()
      .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
      .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
      .applySetting("hibernate.default_schema", "public")
      .applySetting("hibernate.implicit_naming_strategy", "component-path")
      .build()
    try
      val sources = new MetadataSources(registry)
      classes.foreach(sources.addAnnotatedClass)
      HibernateSchemaSource.read(sources.buildMetadata())
    finally StandardServiceRegistryBuilder.destroy(registry)

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
    assert(diagnostics.exists(_.contains("Unsupported.id has SQL type 'numeric(38,2)'")), diagnostics)
    assert(diagnostics.exists(_.contains("of entity Unsupported has ON DELETE CASCADE; unsupported")), diagnostics)
    assert(diagnostics.exists(_.contains("collection and join tables are unsupported")), diagnostics)
    assert(diagnostics.exists(_.contains("unique")), diagnostics)
    assert(errors(classOf[Generated]).exists(_.startsWith("Sequence")))
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

  test("fresh IDs have the required format") {
    assert(Vector.fill(20)(HibernateSchemaSource.newId()).forall(_.matches("[0-9a-f]{8}")))
  }
