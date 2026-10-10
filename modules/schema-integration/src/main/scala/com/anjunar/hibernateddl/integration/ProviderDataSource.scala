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

/** Hands out connections of a Hibernate ConnectionProvider with auto-commit enabled, and gives
  * them back to the provider when closed. A connection that arrives without auto-commit is
  * rolled back first, so nothing left pending on it can be committed, and gets its mode back
  * on close; the executor restores everything else it changed. A connection whose mode cannot
  * be restored is aborted, and the failure stays readable in `resetFailure`, because the
  * executor does not let a close failure turn a successful migration into a failed one.
  */
private final class ProviderDataSource(provider: ConnectionProvider) extends DataSource:
  @volatile var resetFailure: Option[Throwable] = None

  override def getConnection(): Connection =
    val raw = provider.getConnection()
    try
      val autoCommit = raw.getAutoCommit
      if !autoCommit then
        raw.rollback()
        raw.setAutoCommit(true)
      Proxy.newProxyInstance(
        classOf[Connection].getClassLoader,
        Array(classOf[Connection]),
        new InvocationHandler:
          private var closed = false
          def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef =
            method.getName match
              case "close" =>
                if !closed then
                  closed = true
                  try
                    // The executor ends its transaction first, or aborts the connection.
                    if !raw.isClosed && raw.getAutoCommit != autoCommit then raw.setAutoCommit(autoCommit)
                  catch
                    case NonFatal(error) =>
                      resetFailure = Some(error)
                      try raw.abort((command: Runnable) => command.run())
                      catch case NonFatal(abortError) => error.addSuppressed(abortError)
                      throw error
                  finally provider.closeConnection(raw)
                null
              case "isClosed" => Boolean.box(closed || raw.isClosed)
              case _          =>
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
