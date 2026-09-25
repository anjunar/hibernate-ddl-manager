package com.anjunar.hibernateddl.hibernate

import com.anjunar.hibernateddl.core.SchemaModel
import org.hibernate.boot.{Metadata, MetadataSources}
import org.hibernate.boot.registry.{StandardServiceRegistry, StandardServiceRegistryBuilder}
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator

/** Reads annotated test entities the way a PostgreSQL server would boot them. */
object TestMetadata:
  def read(classes: Class[?]*): Either[Vector[String], SchemaModel] =
    withMetadata(classes)((metadata, _) => HibernateSchemaSource.read(metadata))

  /** The DDL that Hibernate's own schema generation (hbm2ddl `create`) would execute. */
  def createScript(classes: Class[?]*): String =
    withMetadata(classes) { (metadata, registry) =>
      val script = new java.io.StringWriter()
      val settings = new java.util.HashMap[String, AnyRef]()
      settings.put("jakarta.persistence.schema-generation.database.action", "none")
      settings.put("jakarta.persistence.schema-generation.scripts.action", "create")
      settings.put("jakarta.persistence.schema-generation.scripts.create-target", script)
      settings.put("hibernate.hbm2ddl.delimiter", ";")
      SchemaManagementToolCoordinator.process(metadata, registry, settings, _ => ())
      script.toString
    }

  private def withMetadata[A](classes: Seq[Class[?]])(body: (Metadata, StandardServiceRegistry) => A): A =
    val registry = new StandardServiceRegistryBuilder()
      .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
      .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
      .applySetting("hibernate.default_schema", "public")
      .applySetting("hibernate.implicit_naming_strategy", "component-path")
      .build()
    try
      val sources = new MetadataSources(registry)
      classes.foreach(sources.addAnnotatedClass)
      body(sources.buildMetadata(), registry)
    finally StandardServiceRegistryBuilder.destroy(registry)
