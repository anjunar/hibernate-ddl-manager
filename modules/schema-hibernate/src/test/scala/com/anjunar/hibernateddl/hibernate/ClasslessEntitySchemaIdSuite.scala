package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.core.*
import org.hibernate.boot.{Metadata, MetadataSources}
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.mapping.PersistentClass

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

final class TestClasslessSchemaIds extends ClasslessEntitySchemaIdProvider:
  override def identify(entity: PersistentClass, metadata: Metadata): Option[ClasslessEntitySchemaIds] =
    Option.when(entity.getEntityName == "generated.test.translation")(
      ClasslessEntitySchemaIds(SchemaId("a31b4c20/translation"), Map(
        "page_id" -> SchemaId("a31b4c20/translation/page_id"),
        "locale" -> SchemaId("a31b4c20/translation/locale"),
        "title" -> SchemaId("a31b4c20/translation/e63a0101")
      ))
    )

class ClasslessEntitySchemaIdSuite extends munit.FunSuite:
  test("service provider gives a classless Hibernate entity stable table and column IDs") {
    val registry = new StandardServiceRegistryBuilder()
      .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
      .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
      .applySetting("hibernate.default_schema", "public")
      .build()
    try
      val xml = """<entity-mappings xmlns="http://www.hibernate.org/xsd/orm/mapping" version="7.0">
        |  <entity name="generated.test.translation" metadata-complete="true">
        |    <table name="generated_translation"/>
        |    <attributes>
        |      <id name="pageId"><column name="page_id"/><target>java.util.UUID</target></id>
        |      <id name="locale"><column name="locale"/><target>java.lang.String</target></id>
        |      <basic name="title"><column name="title" column-definition="text"/><target>java.lang.String</target></basic>
        |    </attributes>
        |  </entity>
        |</entity-mappings>""".stripMargin
      val metadata = new MetadataSources(registry)
        .addInputStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
        .buildMetadata()
      val table = HibernateSchemaSource.read(metadata).toOption.get.tables.head
      assertEquals(table.id, SchemaId("a31b4c20/translation"))
      assertEquals(table.primaryKey, Vector(
        SchemaId("a31b4c20/translation/page_id"), SchemaId("a31b4c20/translation/locale")))
      assertEquals(table.columns.find(_.name.value == "title").map(_.dataType), Some(SqlType.Text))
      assertEquals(SchemaValidation.validate(SchemaModel(Vector(table))), Vector.empty)
    finally StandardServiceRegistryBuilder.destroy(registry)
  }
