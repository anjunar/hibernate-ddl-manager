package io.github.hibernateddl.executor

import io.github.hibernateddl.core.*
import java.sql.Connection
import javax.sql.DataSource
import scala.util.control.NonFatal

/** Migrates the database to the target model during server startup, using one owned transaction.
  * The previous model is the one the latest history entry stored; without history it is the
  * empty model. Because stable IDs never change, a server may skip releases. A target equal
  * to an earlier revision, or renaming something back to an earlier name, is what a server
  * with an older model would do and is refused.
  *
  * A successful return permits startup. Any exception must prevent server startup.
  * A failure to close the connection after a successful commit does not change the
  * known migration outcome. No caller transaction is committed or rolled back.
  */
final class JdbcMigrationExecutor(
    backend: TransactionalMigrationBackend,
    options: ExecutionOptions = ExecutionOptions()
):
  private val EmptyModel = SchemaModel(Vector.empty)
  private val EmptyFingerprint = SchemaFingerprint.of(EmptyModel)

  def migrate(dataSource: DataSource, target: SchemaModel): MigrationResult =
    val targetFingerprint = prepare(target)
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
      val history = verified(backend.readHistory(connection))
      val revision = history.lastOption.fold(0L)(_.entry.revision)
      val previous = history.lastOption.fold(EmptyModel)(_.model)
      val previousFingerprint = history.lastOption.fold(EmptyFingerprint)(_.entry.targetFingerprint)
      val result =
        if targetFingerprint == previousFingerprint then
          validateDatabase(connection, target, "Applied schema")
          MigrationResult(revision, MigrationStatus.AlreadyApplied, 0)
        else
          history.find(_.entry.targetFingerprint == targetFingerprint).foreach { older =>
            throw new IllegalStateException(s"Target schema equals revision ${older.entry.revision}, but the database " +
              s"is at revision $revision; a server with an older schema must not start after a newer migration")
          }
          val statements = plan(history, previous, target)
          validateDatabase(connection, previous, "Previous schema")
          statements.foreach(sql => execute(connection, sql))
          validateDatabase(connection, target, "Target schema")
          backend.recordHistory(connection, HistoryEntry(revision + 1, previousFingerprint, targetFingerprint,
            SchemaModelJson.encode(target), statements))
          MigrationResult(revision + 1, MigrationStatus.Applied, statements.size)
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
        val failure = new MigrationException(s"Migration failed: ${cause.getMessage}", state, cause)
        primaryFailure = failure
        throw failure
    finally
      if connection != null then
        try connection.close()
        catch
          case NonFatal(closeError) =>
            if primaryFailure != null then primaryFailure.addSuppressed(closeError)

  /** Checks everything that does not depend on the database and returns the target fingerprint. */
  private def prepare(target: SchemaModel): String =
    try
      val errors = Vector.newBuilder[String]
      val maximumTimeoutMillis = 24 * 60 * 60 * 1000
      if options.lockTimeoutMillis <= 0 || options.lockTimeoutMillis > maximumTimeoutMillis then
        errors += "Lock timeout must be greater than zero and at most 24 hours"
      if options.statementTimeoutMillis <= 0 || options.statementTimeoutMillis > maximumTimeoutMillis then
        errors += "Statement timeout must be greater than zero and at most 24 hours"
      errors ++= (SchemaValidation.validate(target) ++ backend.validate(target)).distinct.map("Target schema: " + _)
      val messages = errors.result()
      if messages.nonEmpty then throw new MigrationException(messages.mkString("; "), FailureState.NotStarted)
      SchemaFingerprint.of(target)
    catch
      case error: MigrationException => throw error
      case NonFatal(error) =>
        throw new MigrationException(s"Migration planning failed: ${error.getMessage}", FailureState.NotStarted, error)

  private final case class Applied(entry: HistoryEntry, model: SchemaModel)

  /** Decodes every stored model and checks that the entries form one unbroken chain. */
  private def verified(entries: Vector[HistoryEntry]): Vector[Applied] =
    entries.foldLeft(Vector.empty[Applied]) { (chain, entry) =>
      def corrupt(reason: String): Nothing = throw new IllegalStateException(
        s"Schema history is inconsistent at revision ${entry.revision}: $reason; refusing to migrate")
      if entry.revision != chain.size + 1 then corrupt(s"expected revision ${chain.size + 1}")
      if entry.previousFingerprint != chain.lastOption.fold(EmptyFingerprint)(_.entry.targetFingerprint) then
        corrupt("previous fingerprint does not match the preceding revision")
      val model = SchemaModelJson.decode(entry.model).fold(error => corrupt(s"stored model is unreadable ($error)"), identity)
      if SchemaFingerprint.of(model) != entry.targetFingerprint then corrupt("stored model does not match its fingerprint")
      chain :+ Applied(entry, model)
    }

  private def plan(history: Vector[Applied], previous: SchemaModel, target: SchemaModel): Vector[String] =
    val operations = DiffEngine.diff(previous, target).fold(refuse, identity)
    def earlier(matches: TableModel => Boolean): Option[Long] =
      history.find(_.model.tables.exists(matches)).map(_.entry.revision)
    val reverted = operations.flatMap {
      case SchemaOperation.RenameTable(id, _, to) =>
        earlier(table => table.id == id && table.name == to).map { revision =>
          s"Renaming table '${id.value}' back to its name from revision $revision is what an older server would do; manual migration required"
        }
      case SchemaOperation.RenameColumn(tableId, _, columnId, _, to) =>
        earlier(table => table.id == tableId && table.columns.exists(c => c.id == columnId && c.name == to)).map { revision =>
          s"Renaming column '${columnId.value}' back to its name from revision $revision is what an older server would do; manual migration required"
        }
      case _ => None
    }
    if reverted.nonEmpty then refuse(reverted)
    val rejectedRisks = operations.map(_.risk).filterNot(options.allowedRisks.contains).distinct
    if rejectedRisks.nonEmpty then refuse(Vector(s"Migration risks are not allowed: ${rejectedRisks.mkString(", ")}"))
    val statements = backend.render(operations).fold(refuse, identity)
    if statements.exists(sql => sql == null || sql.trim.isEmpty) || (operations.nonEmpty && statements.isEmpty) then
      refuse(Vector("Backend returned an empty SQL statement or omitted the migration SQL"))
    statements

  private def refuse(messages: Vector[String]): Nothing =
    throw new IllegalStateException(messages.mkString("; "))

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
