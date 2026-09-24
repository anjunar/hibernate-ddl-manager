package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.core.SchemaModel
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder

/** Reads annotated test entities the way a PostgreSQL server would boot them. */
object TestMetadata:
  def read(classes: Class[?]*): Either[Vector[String], SchemaModel] =
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
