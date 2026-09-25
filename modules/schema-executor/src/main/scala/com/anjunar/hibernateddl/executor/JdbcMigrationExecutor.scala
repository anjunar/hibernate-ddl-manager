package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.sql.Connection
import javax.sql.DataSource
import scala.util.control.NonFatal

/** Migrates the database to the target model during server startup, using one owned transaction.
  * The previous model is the one the latest history entry stored; without history it is the
  * empty model, unless the database already contains the target's tables: such a database is
  * adopted only when enabled and matching exactly, and refused otherwise. Because stable IDs
  * never change, a server may skip releases. A target equal to an earlier revision, or
  * renaming something back to an earlier name, is what a server with an older model would do,
  * and dropping a table, column or sequence deletes data: each is refused unless the options
  * carry an explicit [[Approval]] for it.
  *
  * A successful return permits startup. Any exception must prevent server startup.
  * Once the transaction has ended with a known outcome, the connection gets back the
  * auto-commit mode and isolation level it arrived with, so a pool never hands it on
  * changed; if that fails, it is aborted and the failure is reported. A failure to close the
  * connection after a successful commit does not change the known migration outcome. No
  * caller transaction is committed or rolled back.
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
    var isolation = Connection.TRANSACTION_NONE
    var transactionStarted = false
    var commitAttempted = false
    var committed = false
    var primaryFailure: Throwable = null
    try
      connection = dataSource.getConnection()
      if !connection.getAutoCommit then
        throw new IllegalStateException("Migration requires a fresh connection with auto-commit enabled")
      isolation = connection.getTransactionIsolation
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
          validateDatabase(connection, target, "Applied schema", TableLock.Shared)
          MigrationResult(revision, MigrationStatus.AlreadyApplied, 0)
        else
          history.find(_.entry.targetFingerprint == targetFingerprint).foreach { older =>
            if !options.approvals.contains(Approval.Revert(older.entry.revision)) then
              refuse(Vector(s"Target schema equals revision ${older.entry.revision}, but the database is at revision " +
                s"$revision; a server with an older schema must not start after a newer migration. If returning to " +
                s"it is intended, approve it with Approval.Revert(${older.entry.revision})"))
          }
          val reused = retired(history, target)
          if reused.nonEmpty then refuse(reused)
          val existing = if history.isEmpty then backend.existingRelations(connection, target) else Vector.empty
          if existing.nonEmpty then adopt(connection, target, targetFingerprint, existing)
          else if options.acceptManualMigration.nonEmpty then
            acceptManual(connection, history, target, targetFingerprint)
          else
            val steps = plan(history, previous, target, targetFingerprint)
            validateDatabase(connection, previous, "Previous schema")
            steps.foreach(step => run(connection, step))
            validateDatabase(connection, target, "Target schema")
            val statements = steps.collect { case Step.Statement(sql) => sql }
            backend.recordHistory(connection, HistoryEntry(revision + 1, previousFingerprint, targetFingerprint,
              SchemaModelJson.encode(target), statements))
            MigrationResult(revision + 1, MigrationStatus.Applied, statements.size)
      commitAttempted = true
      connection.commit()
      committed = true
      restore(connection, isolation).foreach { error =>
        abort(connection, error)
        throw new MigrationException(s"Migration committed revision ${result.revision}, but the connection could not " +
          s"be restored and was aborted: ${error.getMessage}", FailureState.Committed, error)
      }
      result
    catch
      case failure: MigrationException if committed =>
        primaryFailure = failure
        throw failure
      case NonFatal(cause) =>
        var rollbackFailed = false
        if transactionStarted then
          try connection.rollback()
          catch
            case NonFatal(rollbackError) =>
              rollbackFailed = true
              cause.addSuppressed(rollbackError)
              // Never restore auto-commit here: it could commit the unresolved transaction.
              abort(connection, cause)
          if !rollbackFailed then
            restore(connection, isolation).foreach { error =>
              cause.addSuppressed(error)
              abort(connection, cause)
            }
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

  /** Gives the connection back the auto-commit mode and isolation level it arrived with; only
    * after the transaction ended, so nothing pending can be committed. Returns the failure.
    */
  private def restore(connection: Connection, isolation: Int): Option[Throwable] =
    try
      connection.setAutoCommit(true)
      if isolation != Connection.TRANSACTION_NONE then connection.setTransactionIsolation(isolation)
      None
    catch case NonFatal(error) => Some(error)

  /** Closes the physical connection, so that no pool reuses it in an unknown state. */
  private def abort(connection: Connection, failure: Throwable): Unit =
    try connection.abort((command: Runnable) => command.run())
    catch case NonFatal(abortError) => failure.addSuppressed(abortError)

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

  /** Records a database without history as revision 1 without DDL. Every table and sequence of
    * the target must exist and the database must match the target exactly; nothing is created,
    * altered or guessed.
    */
  private def adopt(
      connection: Connection,
      target: SchemaModel,
      targetFingerprint: String,
      existing: Vector[QualifiedName]
  ): MigrationResult =
    def show(names: Vector[QualifiedName]) = names.map(_.display).distinct.sorted.mkString(", ")
    if !options.adoptExistingSchema then
      refuse(Vector(s"The database has no schema history but already contains ${show(existing)} of the target schema; " +
        "enable adoptExistingSchema to adopt a database that matches the target exactly"))
    val missing = (target.tables.map(_.name) ++ target.sequences.map(_.name)).filterNot(existing.contains)
    if missing.nonEmpty then
      refuse(Vector(s"Adopting the existing schema requires every table and sequence of the target; missing: ${show(missing)}"))
    validateDatabase(connection, target, "Adopted schema")
    backend.recordHistory(connection, HistoryEntry(1, EmptyFingerprint, targetFingerprint,
      SchemaModelJson.encode(target), Vector.empty))
    MigrationResult(1, MigrationStatus.Adopted, 0)

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

  /** Records a target that an operator migrated to by hand as the next revision, without DDL.
    * The option must name exactly this target's fingerprint, and the database must match it:
    * the target's tables exactly, and no table or sequence of the previous model may remain
    * under a name the target no longer has, since it would silently leave management.
    */
  private def acceptManual(
      connection: Connection,
      history: Vector[Applied],
      target: SchemaModel,
      targetFingerprint: String
  ): MigrationResult =
    val accepted = options.acceptManualMigration.get
    if accepted != targetFingerprint then
      refuse(Vector(s"acceptManualMigration names the target $accepted, but this target is $targetFingerprint; " +
        "remove the option or name this target"))
    if history.isEmpty then
      refuse(Vector("A database without schema history has no previous revision to migrate by hand; " +
        "adopt an existing database with adoptExistingSchema instead"))
    val previous = history.last.model
    val targetNames = (target.tables.map(_.name) ++ target.sequences.map(_.name)).toSet
    val left = SchemaModel(previous.tables.filterNot(t => targetNames.contains(t.name)),
      previous.sequences.filterNot(s => targetNames.contains(s.name)))
    val remaining = backend.existingRelations(connection, left)
    if remaining.nonEmpty then
      refuse(Vector(s"Manually migrated schema does not match database: ${remaining.map(_.display).sorted.mkString(", ")} " +
        "of the previous schema still exist, but the target no longer has them; drop or rename them first"))
    validateDatabase(connection, target, "Manually migrated schema")
    val latest = history.last.entry
    backend.recordHistory(connection, HistoryEntry(latest.revision + 1, latest.targetFingerprint, targetFingerprint,
      SchemaModelJson.encode(target), Vector.empty))
    MigrationResult(latest.revision + 1, MigrationStatus.ManuallyMigrated, 0)

  /** A table, column or sequence that an earlier revision had and the latest does not was
    * dropped. Its ID is retired: reusing it, for example copied from the version history of the
    * code, would attach the old identity to a new object, so no approval permits it.
    */
  private def retired(history: Vector[Applied], target: SchemaModel): Vector[String] =
    def ids(model: SchemaModel): Set[SchemaId] =
      (model.tables.flatMap(table => table.id +: table.columns.map(_.id)) ++ model.sequences.map(_.id)).toSet
    val latest = history.lastOption.fold(Set.empty[SchemaId])(applied => ids(applied.model))
    (ids(target) -- latest).toVector.sortBy(_.value).flatMap { id =>
      history.findLast(applied => ids(applied.model).contains(id)).map { applied =>
        s"Stable ID '${id.value}' was dropped after revision ${applied.entry.revision} and is retired; " +
          "a retired ID must never be reused, generate a new one"
      }
    }

  /** One step of a migration: a DDL statement, or a check that a column has no NULL left
    * before it becomes required.
    */
  private enum Step:
    case Statement(sql: String)
    case NoNulls(columnId: SchemaId, column: String, query: String)

  private def plan(
      history: Vector[Applied],
      previous: SchemaModel,
      target: SchemaModel,
      targetFingerprint: String
  ): Vector[Step] =
    val operations = DiffEngine.diff(previous, target).fold(errors => refuse(errors :+
      ("To migrate by hand instead, change the database to exactly the target schema and start once with " +
        s"acceptManualMigration = \"$targetFingerprint\"")), identity)
    def earlier(matches: TableModel => Boolean): Option[Long] =
      history.find(_.model.tables.exists(matches)).map(_.entry.revision)
    def renameBack(kind: String, id: SchemaId, revision: Option[Long]) =
      revision.filterNot(_ => options.approvals.contains(Approval.RenameBack(id))).map { revision =>
        s"Renaming $kind '${id.value}' back to its name from revision $revision is what an older server would do; " +
          s"if intended, approve it with Approval.RenameBack(\"${id.value}\")"
      }
    def drop(kind: String, id: SchemaId, name: String) =
      Option.when(!options.approvals.contains(Approval.Drop(id))) {
        s"Dropping $kind '${id.value}' ($name) deletes its data; if intended, approve it with Approval.Drop(\"${id.value}\")"
      }
    val unapproved = operations.flatMap {
      case SchemaOperation.RenameTable(id, _, to) =>
        renameBack("table", id, earlier(table => table.id == id && table.name == to)).toVector
      case SchemaOperation.RenameColumn(tableId, _, columnId, _, to) =>
        renameBack("column", columnId,
          earlier(table => table.id == tableId && table.columns.exists(c => c.id == columnId && c.name == to))).toVector
      case SchemaOperation.RenameSequence(id, _, to) =>
        renameBack("sequence", id,
          history.find(_.model.sequences.exists(s => s.id == id && s.name == to)).map(_.entry.revision)).toVector
      case SchemaOperation.DropColumn(_, table, columnId, column) =>
        drop("column", columnId, s"${table.display}.${column.value}").toVector
      case SchemaOperation.DropTables(tables) =>
        tables.flatMap(table => drop("table", table.tableId, table.table.display))
      case SchemaOperation.DropSequence(id, sequence) =>
        drop("sequence", id, sequence.display).toVector
      case _ => Vector.empty
    }
    if unapproved.nonEmpty then refuse(unapproved)
    val rejectedRisks = operations.map(_.risk).filterNot(options.allowedRisks.contains).distinct
    if rejectedRisks.nonEmpty then refuse(Vector(s"Migration risks are not allowed: ${rejectedRisks.mkString(", ")}"))
    val statements = backend.render(operations).fold(refuse, identity)
    if statements.exists(sql => sql == null || sql.trim.isEmpty) || statements.size != operations.size then
      refuse(Vector("Backend must render exactly one non-empty SQL statement per operation"))
    operations.zip(statements).flatMap {
      case (SchemaOperation.SetNotNull(_, table, columnId, column), sql) =>
        Vector(Step.NoNulls(columnId, s"${table.display}.${column.value}", backend.nullCount(table, column)),
          Step.Statement(sql))
      case (_, sql) => Vector(Step.Statement(sql))
    }

  private def refuse(messages: Vector[String]): Nothing =
    throw new IllegalStateException(messages.mkString("; "))

  private def validateDatabase(
      connection: Connection,
      model: SchemaModel,
      label: String,
      lock: TableLock = TableLock.Exclusive
  ): Unit =
    val errors = backend.lockAndValidate(connection, model, lock)
    if errors.nonEmpty then throw new IllegalStateException(s"$label does not match database: ${errors.mkString("; ")}")

  private def run(connection: Connection, step: Step): Unit = step match
    case Step.Statement(sql) => withStatement(connection)(_.execute(sql): Unit)
    case Step.NoNulls(columnId, column, query) =>
      val nulls = withStatement(connection) { statement =>
        val rows = statement.executeQuery(query)
        try
          if !rows.next() then throw new IllegalStateException(s"Counting NULLs of $column returned no row")
          rows.getLong(1)
        finally rows.close()
      }
      if nulls > 0 then
        val rows = if nulls == 1 then "1 row holds" else s"$nulls rows hold"
        refuse(Vector(s"Column '${columnId.value}' ($column) becomes required, but $rows NULL; " +
          "register a backfill for it or fill the rows before the migration"))

  /** Runs one statement with the statement timeout and closes it without masking a failure. */
  private def withStatement[A](connection: Connection)(body: java.sql.Statement => A): A =
    val statement = connection.createStatement()
    var primaryFailure: Throwable = null
    try
      statement.setQueryTimeout(((options.statementTimeoutMillis.toLong + 999) / 1000).toInt)
      body(statement)
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
