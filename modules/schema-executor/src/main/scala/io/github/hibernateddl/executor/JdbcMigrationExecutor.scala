package io.github.hibernateddl.executor

import io.github.hibernateddl.core.*
import java.sql.Connection
import javax.sql.DataSource
import scala.util.control.NonFatal

/** Executes a validated migration during server startup, using one owned transaction.
  * A successful return permits startup. Any exception must prevent server startup.
  * A failure to close the connection after a successful commit does not change the
  * known migration outcome. No caller transaction is committed or rolled back.
  */
final class JdbcMigrationExecutor(
    backend: TransactionalMigrationBackend,
    options: ExecutionOptions = ExecutionOptions()
):
  def migrate(dataSource: DataSource, request: MigrationRequest): MigrationResult =
    val prepared = prepare(request)
    var connection: Connection = null
    var transactionStarted = false
    var commitAttempted = false
    var primaryFailure: Throwable = null
    try
      connection = dataSource.getConnection()
      if !connection.getAutoCommit then
        throw new IllegalStateException("Migration requires a fresh connection with auto-commit enabled")
      connection.setAutoCommit(false)
      transactionStarted = true
      // A transaction waiting for the migration lock must see the preceding commit.
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED)
      backend.acquireLock(connection, options)
      backend.initializeHistory(connection)
      val existing = backend.findHistory(connection, request.id)
      val latest = backend.latestHistory(connection)
      val result = existing match
        case Some(entry) =>
          if entry != prepared.history then
            throw new IllegalStateException(s"Migration ID '${request.id}' was already used for a different plan")
          if !latest.contains(entry) then
            throw new IllegalStateException(s"Migration '${request.id}' is no longer the latest revision; refusing stale server startup")
          validateDatabase(connection, request.target.model, "Already-applied target")
          MigrationResult(request.id, request.target.revision, MigrationStatus.AlreadyApplied, 0)
        case None =>
          latest match
            case None if request.previous.revision != 0 =>
              throw new IllegalStateException("First migration must start at revision 0")
            case Some(entry) if entry.toRevision != request.previous.revision ||
                entry.targetFingerprint != prepared.history.previousFingerprint =>
              throw new IllegalStateException("Previous snapshot does not match the latest applied migration")
            case _ => ()
          validateDatabase(connection, request.previous.model, "Previous schema")
          prepared.statements.foreach(sql => execute(connection, sql))
          validateDatabase(connection, request.target.model, "Target schema")
          backend.recordHistory(connection, prepared.history)
          MigrationResult(request.id, request.target.revision, MigrationStatus.Applied, prepared.statements.size)
      commitAttempted = true
      connection.commit()
      result
    catch
      case NonFatal(cause) =>
        var rollbackFailed = false
        if transactionStarted then
          try connection.rollback()
          catch
            case NonFatal(rollbackError) =>
              rollbackFailed = true
              cause.addSuppressed(rollbackError)
              // Never restore auto-commit here: it could commit the unresolved transaction.
              try connection.abort((command: Runnable) => command.run())
              catch case NonFatal(abortError) => cause.addSuppressed(abortError)
        val state =
          if commitAttempted || rollbackFailed then FailureState.OutcomeUnknown
          else if transactionStarted then FailureState.RolledBack
          else FailureState.NotStarted
        val failure = new MigrationException(s"Migration '${request.id}' failed: ${cause.getMessage}", state, cause)
        primaryFailure = failure
        throw failure
    finally
      if connection != null then
        try connection.close()
        catch
          case NonFatal(closeError) =>
            if primaryFailure != null then primaryFailure.addSuppressed(closeError)

  private case class Prepared(history: HistoryEntry, statements: Vector[String])

  private def prepare(request: MigrationRequest): Prepared =
    try
      val errors = Vector.newBuilder[String]
      if request.id == null || request.id.trim.isEmpty || request.id.length > 200 then
        errors += "Migration ID must contain 1 to 200 characters and must not be blank"
      val maximumTimeoutMillis = 24 * 60 * 60 * 1000
      if options.lockTimeoutMillis <= 0 || options.lockTimeoutMillis > maximumTimeoutMillis then
        errors += "Lock timeout must be greater than zero and at most 24 hours"
      if options.statementTimeoutMillis <= 0 || options.statementTimeoutMillis > maximumTimeoutMillis then
        errors += "Statement timeout must be greater than zero and at most 24 hours"
      if request.previous.formatVersion != 1 || request.target.formatVersion != 1 then
        errors += "Only snapshot format version 1 is supported"
      if request.previous.revision < 0 || request.previous.revision == Long.MaxValue ||
          request.target.revision != request.previous.revision + 1 then
        errors += "Target revision must be exactly previous revision + 1, with a non-negative previous revision"
      val initialErrors = errors.result()
      if initialErrors.nonEmpty then reject(initialErrors)
      val operations = DiffEngine.diff(request.previous.model, request.target.model) match
        case Left(messages) => reject(messages)
        case Right(value) => value
      val backendErrors = backend.validate(request)
      if backendErrors.nonEmpty then reject(backendErrors)
      val rejectedRisks = operations.map(_.risk).filterNot(options.allowedRisks.contains).distinct
      if rejectedRisks.nonEmpty then reject(Vector(s"Migration risks are not allowed: ${rejectedRisks.mkString(", ")}"))
      val statements = backend.render(operations) match
        case Left(messages) => reject(messages)
        case Right(value) => value
      if statements.exists(sql => sql == null || sql.trim.isEmpty) ||
          (operations.nonEmpty && statements.isEmpty) then
        reject(Vector("Backend returned an empty SQL statement or omitted the migration SQL"))
      val previousFingerprint = SchemaFingerprint.of(request.previous)
      val targetFingerprint = SchemaFingerprint.of(request.target)
      val checksum = BinaryFingerprint.digest { out =>
        BinaryFingerprint.string(out, "hibernate-ddl-plan-v1")
        BinaryFingerprint.string(out, request.id)
        BinaryFingerprint.string(out, previousFingerprint)
        BinaryFingerprint.string(out, targetFingerprint)
        BinaryFingerprint.string(out, backend.name)
        out.writeInt(statements.size)
        statements.foreach(BinaryFingerprint.string(out, _))
      }
      Prepared(HistoryEntry(request.id, checksum, request.previous.revision, request.target.revision,
        previousFingerprint, targetFingerprint), statements)
    catch
      case error: MigrationException => throw error
      case NonFatal(error) =>
        throw new MigrationException(s"Migration planning failed: ${error.getMessage}", FailureState.NotStarted, error)

  private def reject(messages: Vector[String]): Nothing =
    throw new MigrationException(messages.mkString("; "), FailureState.NotStarted)

  private def validateDatabase(connection: Connection, model: SchemaModel, label: String): Unit =
    val errors = backend.lockAndValidate(connection, model)
    if errors.nonEmpty then throw new IllegalStateException(s"$label does not match database: ${errors.mkString("; ")}")

  private def execute(connection: Connection, sql: String): Unit =
    val statement = connection.createStatement()
    var primaryFailure: Throwable = null
    try
      statement.setQueryTimeout(((options.statementTimeoutMillis.toLong + 999) / 1000).toInt)
      statement.execute(sql)
      ()
    catch
      case NonFatal(error) =>
        primaryFailure = error
        throw error
    finally
      try statement.close()
      catch
        case NonFatal(closeError) =>
          if primaryFailure != null then primaryFailure.addSuppressed(closeError)
          else throw closeError
