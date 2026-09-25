package com.anjunar.hibernateddl.integration

import com.anjunar.hibernateddl.core.{Backfill, SchemaModel}
import com.anjunar.hibernateddl.executor.*
import com.anjunar.hibernateddl.hibernate.HibernateSchemaSource
import com.anjunar.hibernateddl.postgresql.PostgreSqlMigrationBackend
import org.hibernate.boot.Metadata
import org.hibernate.boot.registry.classloading.spi.ClassLoaderService
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
  * The `hibernate.ddl_manager.*` settings of [[MigrationSettings]] refine `options`. The
  * backfills are those of every [[BackfillProvider]] plus `backfills`; an ID found twice is
  * refused.
  */
object HibernateSchemaMigration:
  private val SchemaActions = Vector("hibernate.hbm2ddl.auto", "jakarta.persistence.schema-generation.database.action")

  def migrate(
      metadata: Metadata,
      dataSource: DataSource,
      options: ExecutionOptions = ExecutionOptions(),
      backfills: Vector[Backfill] = Vector.empty
  ): MigrationResult =
    val (executionOptions, target, all) = resolve(metadata, options, backfills)
    JdbcMigrationExecutor(PostgreSqlMigrationBackend, executionOptions).migrate(dataSource, target, all)

  /** What [[migrate]] would do, read-only, with the same options, target and backfills. The
    * report never replaces the migration: a server must still start with [[migrate]] or the
    * integrator, which checks everything again under its locks.
    */
  def preview(
      metadata: Metadata,
      dataSource: DataSource,
      options: ExecutionOptions = ExecutionOptions(),
      backfills: Vector[Backfill] = Vector.empty,
      previewOptions: PreviewOptions = PreviewOptions()
  ): PreviewReport =
    val (executionOptions, target, all) = resolve(metadata, options, backfills)
    JdbcMigrationPreview(PostgreSqlMigrationBackend, executionOptions).preview(dataSource, target, all, previewOptions)

  /** The target model in the history's JSON form, for `schema-cli preview --target`. */
  def exportTarget(metadata: Metadata): String =
    SchemaModelJson.encode(resolve(metadata, ExecutionOptions(), Vector.empty)._2)

  /** The providers' backfills plus `backfills` in their JSON form, for `schema-cli preview --backfills`. */
  def exportBackfills(metadata: Metadata, backfills: Vector[Backfill] = Vector.empty): String =
    BackfillJson.encode(resolve(metadata, ExecutionOptions(), backfills)._3)

  /** Options refined by the settings, the target read from the entities, and every backfill. */
  private def resolve(
      metadata: Metadata,
      options: ExecutionOptions,
      backfills: Vector[Backfill]
  ): (ExecutionOptions, SchemaModel, Vector[Backfill]) =
    val settings = configuration(metadata)
    val executionOptions = MigrationSettings.options(settings, options).fold(refuse, identity)
    val errors = checkSetup(metadata, settings)
    if errors.nonEmpty then refuse(errors)
    val target = HibernateSchemaSource.read(metadata).fold(errors => refuse(errors.map("Entity mapping: " + _)), identity)
    (executionOptions, target, provided(metadata) ++ backfills)

  /** The backfills of every provider on the application's class path. */
  private[integration] def provided(metadata: Metadata): Vector[Backfill] =
    val registry = metadata match
      case implementor: MetadataImplementor => implementor.getMetadataBuildingOptions.getServiceRegistry
      case other => refuse(Vector(s"Unsupported metadata implementation ${other.getClass.getName}"))
    try
      registry.requireService(classOf[ClassLoaderService]).loadJavaServices(classOf[BackfillProvider]).asScala.toVector
        .sortBy(_.getClass.getName).flatMap(_.backfills)
    catch case NonFatal(error) => refuse(Vector(s"Loading the backfill providers failed: ${error.getMessage}"))

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
