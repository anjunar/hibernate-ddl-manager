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
          "with a DataSource before building the SessionFactory"))
      HibernateSchemaMigration.migrate(metadata, ProviderDataSource(provider))

  private def refuse(message: String): Nothing =
    throw new MigrationException(s"Migration failed: $message", FailureState.NotStarted)

/** Hands out connections of a Hibernate ConnectionProvider with auto-commit enabled, and gives
  * them back to the provider when closed. A connection that arrives without auto-commit is
  * rolled back first, so nothing left pending on it can be committed.
  */
private final class ProviderDataSource(provider: ConnectionProvider) extends DataSource:
  override def getConnection(): Connection =
    val raw = provider.getConnection()
    try
      val autoCommit = raw.getAutoCommit
      if !autoCommit then
        raw.rollback()
        raw.setAutoCommit(true)
      Proxy.newProxyInstance(classOf[Connection].getClassLoader, Array(classOf[Connection]), new InvocationHandler:
        private var closed = false
        def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef =
          method.getName match
            case "close" =>
              if !closed then
                closed = true
                try
                  if !raw.isClosed && raw.getAutoCommit != autoCommit then
                    raw.rollback()
                    raw.setAutoCommit(autoCommit)
                finally provider.closeConnection(raw)
              null
            case "isClosed" => Boolean.box(closed || raw.isClosed)
            case _ =>
              try method.invoke(raw, Option(args).getOrElse(Array.empty[AnyRef])*)
              catch case error: InvocationTargetException => throw error.getCause
      ).asInstanceOf[Connection]
    catch
      case NonFatal(error) =>
        try provider.closeConnection(raw)
        catch case NonFatal(closeError) => error.addSuppressed(closeError)
        throw error

  override def getConnection(username: String, password: String): Connection =
    throw new SQLFeatureNotSupportedException("Connections come from Hibernate's ConnectionProvider")
  override def getLogWriter: PrintWriter = null
  override def setLogWriter(out: PrintWriter): Unit = ()
  override def setLoginTimeout(seconds: Int): Unit = ()
  override def getLoginTimeout: Int = 0
  override def getParentLogger: Logger = throw new SQLFeatureNotSupportedException()
  override def unwrap[T](iface: Class[T]): T = throw new SQLFeatureNotSupportedException()
  override def isWrapperFor(iface: Class[?]): Boolean = false
