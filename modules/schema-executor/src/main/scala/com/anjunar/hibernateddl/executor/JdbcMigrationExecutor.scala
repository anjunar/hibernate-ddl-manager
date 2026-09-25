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
  * A column that becomes required gets its NULLs filled by the one registered [[Backfill]]
  * that targets it and is not recorded yet; what is left must be no NULL at all. Each backfill
  * is recorded once, with its definition's checksum, and never runs again; a recorded one with
  * another definition blocks the start, even when the schema is already applied.
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
  def migrate(dataSource: DataSource, target: SchemaModel, backfills: Vector[Backfill] = Vector.empty): MigrationResult =
    val targetFingerprint = prepare(target, backfills)
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
      val result = migrateLocked(connection, target, targetFingerprint, backfills)
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

  /** Everything between the lock and the commit: the shared planner decides, the executor
    * checks the database, executes and records.
    */
  private def migrateLocked(
      connection: Connection,
      target: SchemaModel,
      targetFingerprint: String,
      backfills: Vector[Backfill]
  ): MigrationResult =
    val history = MigrationPlanner.verifyHistory(backend.readHistory(connection)).fold(problem => refuse(Vector(problem.message)), identity)
    val records = backend.readBackfills(connection)
    val existing = if history.isEmpty then backend.existingRelations(connection, target) else Vector.empty
    val plan = MigrationPlanner.plan(backend, options, history, records, target, targetFingerprint, backfills, existing)
    if !plan.executable then refuse(plan.problems.map(_.message) ++ plan.notes)
    val revision = plan.revision
    val next = revision + 1
    def record(statements: Vector[String]): Unit =
      backend.recordHistory(connection, HistoryEntry(next, plan.previousFingerprint, targetFingerprint,
        SchemaModelJson.encode(target), statements))
    plan.mode match
      case PlanMode.NoChange =>
        validateDatabase(connection, target, "Applied schema", TableLock.Shared)
        MigrationResult(revision, MigrationStatus.AlreadyApplied, 0, pendingBackfills = plan.pending)
      case PlanMode.Adoption =>
        validateDatabase(connection, target, "Adopted schema")
        record(Vector.empty)
        MigrationResult(next, MigrationStatus.Adopted, 0, recordBackfills(connection, plan, plan.adopted.map((_,
          BackfillResult.Adopted, None))), plan.pending)
      case PlanMode.ManualMigration =>
        val remaining = backend.existingRelations(connection, SchemaModel(
          plan.previous.tables.filter(table => plan.absent.contains(table.name)),
          plan.previous.sequences.filter(sequence => plan.absent.contains(sequence.name))))
        if remaining.nonEmpty then
          refuse(Vector(s"Manually migrated schema does not match database: ${remaining.map(_.display).sorted.mkString(", ")} " +
            "of the previous schema still exist, but the target no longer has them; drop or rename them first"))
        validateDatabase(connection, target, "Manually migrated schema")
        record(Vector.empty)
        MigrationResult(next, MigrationStatus.ManuallyMigrated, 0, recordBackfills(connection, plan, plan.adopted.map((_,
          BackfillResult.Adopted, None))), plan.pending)
      case PlanMode.Migration =>
        validateDatabase(connection, plan.previous, "Previous schema")
        // Known dependents are named before any DDL; the database may still refuse others.
        val blocked = MigrationPlanner.typeChanges(plan).flatMap { (change, table, column) =>
          val blockers = backend.typeChangeBlockers(connection, table, column)
          Option.when(blockers.nonEmpty)(MigrationPlanner.blockedTypeChange(change.columnId, table, column, blockers))
        }
        if blocked.nonEmpty then refuse(blocked)
        val filled = plan.steps.flatMap(step => run(connection, step))
        validateDatabase(connection, target, "Target schema")
        val statements = plan.steps.collect {
          case step: PlanStep.Statement => step.sql
          case step: PlanStep.Fill => step.statement.sql
        }
        record(statements)
        val outcomes = filled.map((backfill, rows) => (backfill, BackfillResult.Executed, Some(rows))) ++
          plan.created.map(backfill => (backfill, BackfillResult.NotRequiredOnCreation, None))
        MigrationResult(next, MigrationStatus.Applied, statements.size, recordBackfills(connection, plan, outcomes), plan.pending)

  private def recordBackfills(
      connection: Connection,
      plan: MigrationPlan,
      outcomes: Vector[(Backfill, BackfillResult, Option[Long])]
  ): Vector[BackfillOutcome] =
    outcomes.sortBy(_._1.id).map { (backfill, result, rows) =>
      backend.recordBackfill(connection, BackfillRecord(backfill.id, BackfillChecksum.Format, BackfillChecksum.of(backfill),
        backfill.target, plan.revision + 1, plan.previousFingerprint, plan.targetFingerprint, result, rows))
      BackfillOutcome(backfill.id, result, rows)
    }

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
  private def prepare(target: SchemaModel, backfills: Vector[Backfill]): String =
    try MigrationPlanner.prepare(backend, options, target, backfills).fold(
      messages => throw new MigrationException(messages.mkString("; "), FailureState.NotStarted), identity)
    catch
      case error: MigrationException => throw error
      case NonFatal(error) =>
        throw new MigrationException(s"Migration planning failed: ${error.getMessage}", FailureState.NotStarted, error)

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

  /** Runs a step and returns the backfill it executed with the number of rows it filled. */
  private def run(connection: Connection, step: PlanStep): Option[(Backfill, Long)] = step match
    case PlanStep.Statement(_, sql, _, _) =>
      withStatement(connection.createStatement())(_.execute(sql))
      None
    case PlanStep.Fill(backfill, _, bound) =>
      val rows = withStatement(connection.prepareStatement(bound.sql)) { statement =>
        bound.parameters.zipWithIndex.foreach((value, index) => statement.setObject(index + 1, value))
        statement.executeUpdate()
      }
      Some(backfill -> rows.toLong)
    case PlanStep.NoNulls(columnId, column, query, hint) =>
      val nulls = withStatement(connection.createStatement()) { statement =>
        val rows = statement.executeQuery(query)
        try
          if !rows.next() then throw new IllegalStateException(s"Counting NULLs of $column returned no row")
          rows.getLong(1)
        finally rows.close()
      }
      if nulls > 0 then
        val rows = if nulls == 1 then "1 row holds" else s"$nulls rows hold"
        refuse(Vector(s"Column '${columnId.value}' ($column) becomes required, but $rows NULL; $hint"))
      None

  /** Runs one statement with the statement timeout and closes it without masking a failure. */
  private def withStatement[S <: java.sql.Statement, A](open: => S)(body: S => A): A =
    val statement = open
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
