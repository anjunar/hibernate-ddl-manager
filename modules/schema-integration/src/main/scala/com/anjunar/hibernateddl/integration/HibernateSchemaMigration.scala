package com.anjunar.hibernateddl.integration

import com.anjunar.hibernateddl.executor.*
import com.anjunar.hibernateddl.hibernate.HibernateSchemaSource
import com.anjunar.hibernateddl.postgresql.PostgreSqlMigrationBackend
import org.hibernate.boot.Metadata
import org.hibernate.boot.spi.MetadataImplementor
import org.hibernate.dialect.PostgreSQLDialect
import org.hibernate.engine.config.spi.ConfigurationService
import org.hibernate.tool.schema.Action
import javax.sql.DataSource
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Migrates the database to the schema of Hibernate's boot metadata. Call it after
  * `MetadataSources.buildMetadata()` and before building the SessionFactory, with a DataSource
  * whose connections are not enlisted in JTA. Any exception must stop the server.
  *
  * The metadata must use a PostgreSQL dialect, and Hibernate's own schema management may at
  * most validate: `hibernate.hbm2ddl.auto` and
  * `jakarta.persistence.schema-generation.database.action` must be unset, `none` or `validate`.
  * The `hibernate.ddl_manager.*` settings of [[MigrationSettings]] refine `options`.
  */
object HibernateSchemaMigration:
  private val SchemaActions = Vector("hibernate.hbm2ddl.auto", "jakarta.persistence.schema-generation.database.action")

  def migrate(metadata: Metadata, dataSource: DataSource, options: ExecutionOptions = ExecutionOptions()): MigrationResult =
    val settings = configuration(metadata)
    val executionOptions = MigrationSettings.options(settings, options).fold(refuse, identity)
    val errors = checkSetup(metadata, settings)
    if errors.nonEmpty then refuse(errors)
    val target = HibernateSchemaSource.read(metadata).fold(errors => refuse(errors.map("Entity mapping: " + _)), identity)
    JdbcMigrationExecutor(PostgreSqlMigrationBackend, executionOptions).migrate(dataSource, target)

  private[integration] def configuration(metadata: Metadata): Map[String, Any] =
    metadata match
      case implementor: MetadataImplementor =>
        implementor.getMetadataBuildingOptions.getServiceRegistry.requireService(classOf[ConfigurationService])
          .getSettings.asScala.toMap
      case other => refuse(Vector(s"Unsupported metadata implementation ${other.getClass.getName}"))

  private def checkSetup(metadata: Metadata, settings: Map[String, Any]): Vector[String] =
    val dialect = metadata.getDatabase.getDialect
    Option.when(!dialect.isInstanceOf[PostgreSQLDialect])(
      s"Hibernate uses the dialect ${dialect.getClass.getName}; the migration supports PostgreSQL only"
    ).toVector ++ SchemaActions.flatMap { key =>
      val action =
        try Right(if key.startsWith("hibernate") then Action.interpretHbm2ddlSetting(settings.get(key).orNull)
          else Action.interpretJpaSetting(settings.get(key).orNull))
        catch case NonFatal(error) => Left(error.getMessage)
      action match
        case Right(Action.NONE | Action.VALIDATE) => None
        case Right(_) =>
          Some(s"$key is ${settings(key)}; Hibernate must not change the schema itself, use none or validate")
        case Left(message) => Some(message)
    }

  private def refuse(errors: Vector[String]): Nothing =
    throw new MigrationException(s"Migration failed: ${errors.mkString("; ")}", FailureState.NotStarted)
