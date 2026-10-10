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
      ClasslessEntitySchemaIds(
        SchemaId("a31b4c20/translation"),
        Map(
          "page_id" -> SchemaId("a31b4c20/translation/page_id"),
          "locale" -> SchemaId("a31b4c20/translation/locale"),
          "title" -> SchemaId("a31b4c20/translation/e63a0101")
        )
      )
    )
