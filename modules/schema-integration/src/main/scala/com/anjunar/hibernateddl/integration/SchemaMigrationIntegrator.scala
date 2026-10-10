package com.anjunar.hibernateddl.integration

import com.anjunar.hibernateddl.executor.{FailureState, MigrationException}
import org.hibernate.boot.Metadata
import org.hibernate.boot.spi.BootstrapContext
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.integrator.spi.Integrator
import org.hibernate.resource.transaction.spi.TransactionCoordinatorBuilder

import java.io.PrintWriter
import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.sql.{Connection, SQLFeatureNotSupportedException}
import java.util.logging.Logger
import javax.sql.DataSource
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Migrates the database while Hibernate builds the SessionFactory, before its own schema
  * validation, when `hibernate.ddl_manager.enabled` is true; otherwise it does nothing.
  * Registered through `META-INF/services`. It borrows one connection from Hibernate's
  * ConnectionProvider, so it refuses JTA and multi-tenancy; there, call
  * [[HibernateSchemaMigration.migrate]] with a suitable DataSource before building the
  * SessionFactory instead.
  */
final class SchemaMigrationIntegrator extends Integrator:
  override def integrate(
    metadata: Metadata,
    bootstrapContext: BootstrapContext,
    sessionFactory: SessionFactoryImplementor
  ): Unit =
    // Every SessionFactory on the classpath runs this; only an enabled one reads more.
    val settings = sessionFactory.getProperties.asScala
    if MigrationSettings.enabled(settings).fold(errors => refuse(errors.mkString("; ")), identity) then
      val registry = sessionFactory.getServiceRegistry
      if registry.requireService(classOf[TransactionCoordinatorBuilder]).isJta then
        refuse("JTA transactions are configured; call HibernateSchemaMigration.migrate with a DataSource " +
          "without JTA enlistment before building the SessionFactory")
      val provider = Option(registry.getService(classOf[ConnectionProvider])).getOrElse(
        refuse("Hibernate has no single ConnectionProvider (multi-tenancy?); call HibernateSchemaMigration.migrate " +
          "with a DataSource before building the SessionFactory")
      )
      val dataSource = ProviderDataSource(provider)
      val result = HibernateSchemaMigration.migrate(metadata, dataSource)
      dataSource.resetFailure.foreach { error =>
        throw new MigrationException(
          s"Migration ended at revision ${result.revision}, but the connection could not " +
            s"be given back to Hibernate as it was lent and was aborted: ${error.getMessage}",
          FailureState.Committed,
          error
        )
      }

  private def refuse(message: String): Nothing =
    throw new MigrationException(s"Migration failed: $message", FailureState.NotStarted)
