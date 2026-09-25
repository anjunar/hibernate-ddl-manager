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
  private val EmptyModel = SchemaModel(Vector.empty)
  private val EmptyFingerprint = SchemaFingerprint.of(EmptyModel)

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

  /** Everything between the lock and the commit. */
  private def migrateLocked(
      connection: Connection,
      target: SchemaModel,
      targetFingerprint: String,
      backfills: Vector[Backfill]
  ): MigrationResult =
    val history = verified(backend.readHistory(connection))
    val records = verifiedBackfills(history, backend.readBackfills(connection), backfills)
    val pending = backfills.filterNot(backfill => records.exists(_.id == backfill.id))
    val revision = history.lastOption.fold(0L)(_.entry.revision)
    val previous = history.lastOption.fold(EmptyModel)(_.model)
    val previousFingerprint = history.lastOption.fold(EmptyFingerprint)(_.entry.targetFingerprint)
    if targetFingerprint == previousFingerprint then
      validateDatabase(connection, target, "Applied schema", TableLock.Shared)
      MigrationResult(revision, MigrationStatus.AlreadyApplied, 0, pendingBackfills = pending.map(_.id))
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
      if existing.nonEmpty then adopt(connection, target, targetFingerprint, existing, pending)
      else if options.acceptManualMigration.nonEmpty then
        acceptManual(connection, history, target, targetFingerprint, pending)
      else
        val planned = plan(history, previous, target, targetFingerprint, records, pending)
        validateDatabase(connection, previous, "Previous schema")
        val filled = planned.steps.flatMap(step => run(connection, step))
        validateDatabase(connection, target, "Target schema")
        val statements = planned.steps.collect {
          case Step.Statement(sql) => sql
          case Step.Fill(_, statement) => statement.sql
        }
        backend.recordHistory(connection, HistoryEntry(revision + 1, previousFingerprint, targetFingerprint,
          SchemaModelJson.encode(target), statements))
        val outcomes = filled.map((backfill, rows) => (backfill, BackfillResult.Executed, Some(rows))) ++
          planned.created.map(backfill => (backfill, BackfillResult.NotRequiredOnCreation, None))
        MigrationResult(revision + 1, MigrationStatus.Applied, statements.size,
          recordBackfills(connection, outcomes, revision + 1, previousFingerprint, targetFingerprint),
          pending.map(_.id).filterNot(id => outcomes.exists(_._1.id == id)))

  /** Checks every recorded backfill against the schema history and each registered backfill
    * against its record: a recorded ID must keep its definition.
    */
  private def verifiedBackfills(
      history: Vector[Applied],
      records: Vector[BackfillRecord],
      backfills: Vector[Backfill]
  ): Vector[BackfillRecord] =
    records.foreach { record =>
      def corrupt(reason: String): Nothing = throw new IllegalStateException(
        s"Backfill history is inconsistent at backfill '${record.id}': $reason; refusing to migrate")
      if record.format != BackfillChecksum.Format then
        corrupt(s"its definition format ${record.format} is unknown to this version")
      val applied = if record.revision < 1 || record.revision > history.size then None else Some(history(record.revision.toInt - 1))
      if !applied.exists(a => a.entry.previousFingerprint == record.previousFingerprint &&
          a.entry.targetFingerprint == record.targetFingerprint) then
        corrupt(s"revision ${record.revision} with its fingerprints is not in the schema history")
      if record.updatedRows.nonEmpty != (record.result == BackfillResult.Executed) then
        corrupt("only an executed backfill has a count of updated rows")
    }
    val changed = backfills.flatMap { backfill =>
      records.find(_.id == backfill.id).filter(_.checksum != BackfillChecksum.of(backfill)).map { record =>
        s"Backfill '${backfill.id}' was recorded in revision ${record.revision} with another definition; " +
          "a changed rule needs a new ID"
      }
    }
    if changed.nonEmpty then refuse(changed)
    records

  private def recordBackfills(
      connection: Connection,
      outcomes: Vector[(Backfill, BackfillResult, Option[Long])],
      revision: Long,
      previousFingerprint: String,
      targetFingerprint: String
  ): Vector[BackfillOutcome] =
    outcomes.sortBy(_._1.id).map { (backfill, result, rows) =>
      backend.recordBackfill(connection, BackfillRecord(backfill.id, BackfillChecksum.Format, BackfillChecksum.of(backfill),
        backfill.target, revision, previousFingerprint, targetFingerprint, result, rows))
      BackfillOutcome(backfill.id, result, rows)
    }

  /** Required columns of the target that were missing or nullable before. */
  private def newlyRequired(previous: SchemaModel, target: SchemaModel): Set[SchemaId] =
    val before = previous.tables.flatMap(_.columns).map(column => column.id -> column.nullable).toMap
    target.tables.flatMap(_.columns).filter(column => !column.nullable && before.getOrElse(column.id, true)).map(_.id).toSet

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
    try
      val errors = Vector.newBuilder[String]
      val maximumTimeoutMillis = 24 * 60 * 60 * 1000
      if options.lockTimeoutMillis <= 0 || options.lockTimeoutMillis > maximumTimeoutMillis then
        errors += "Lock timeout must be greater than zero and at most 24 hours"
      if options.statementTimeoutMillis <= 0 || options.statementTimeoutMillis > maximumTimeoutMillis then
        errors += "Statement timeout must be greater than zero and at most 24 hours"
      errors ++= (SchemaValidation.validate(target) ++ backend.validate(target)).distinct.map("Target schema: " + _)
      errors ++= BackfillValidation.validate(backfills)
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
      existing: Vector[QualifiedName],
      pending: Vector[Backfill]
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
    adopted(connection, EmptyModel, target, pending, 1, EmptyFingerprint, targetFingerprint, MigrationStatus.Adopted)

  /** Records the pending backfills whose columns the database already has as required: the
    * backfill did not run, the database was found that way.
    */
  private def adopted(
      connection: Connection,
      previous: SchemaModel,
      target: SchemaModel,
      pending: Vector[Backfill],
      revision: Long,
      previousFingerprint: String,
      targetFingerprint: String,
      status: MigrationStatus
  ): MigrationResult =
    val required = newlyRequired(previous, target)
    val (found, left) = pending.partition(backfill => required.contains(backfill.target))
    val outcomes = recordBackfills(connection, found.map((_, BackfillResult.Adopted, None)), revision, previousFingerprint,
      targetFingerprint)
    MigrationResult(revision, status, 0, outcomes, left.map(_.id))

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
      targetFingerprint: String,
      pending: Vector[Backfill]
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
    adopted(connection, previous, target, pending, latest.revision + 1, latest.targetFingerprint, targetFingerprint,
      MigrationStatus.ManuallyMigrated)

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

  /** One step of a migration: a DDL statement, a backfill filling a column's NULLs, or a check
    * that a column has no NULL left before it becomes required.
    */
  private enum Step:
    case Statement(sql: String)
    case Fill(backfill: Backfill, statement: BoundStatement)
    case NoNulls(columnId: SchemaId, column: String, query: String, hint: String)

  /** The steps, and the pending backfills whose columns new tables create required. */
  private final case class Planned(steps: Vector[Step], created: Vector[Backfill])

  private def plan(
      history: Vector[Applied],
      previous: SchemaModel,
      target: SchemaModel,
      targetFingerprint: String,
      records: Vector[BackfillRecord],
      pending: Vector[Backfill]
  ): Planned =
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
    val fills = planFills(previous, target, operations, pending)
    val created = operations.collect { case SchemaOperation.CreateTable(table) => table.columns.filterNot(_.nullable).map(_.id) }
      .flatten.toSet
    val steps = operations.zip(statements).flatMap {
      case (SchemaOperation.SetNotNull(_, table, columnId, column), sql) =>
        val fill = fills.get(columnId)
        val hint = fill.map((backfill, _) => s"backfill '${backfill.id}' left them NULL")
          .orElse(records.find(_.target == columnId).map(record => s"backfill '${record.id}' was recorded in revision " +
            s"${record.revision} and does not run again; a new rule for it needs a new ID"))
          .getOrElse("register a backfill for it or fill the rows before the migration")
        fill.toVector.map(Step.Fill(_, _)) ++ Vector(Step.NoNulls(columnId, s"${table.display}.${column.value}", backend.nullCount(table, column),
          hint), Step.Statement(sql))
      case (_, sql) => Vector(Step.Statement(sql))
    }
    Planned(steps, pending.filter(backfill => created.contains(backfill.target)))

  /** Resolves the one pending backfill for each column that becomes required in an existing
    * table. The columns it may read are the target table's, plus the previous ones that are
    * dropped only after the fill.
    */
  private def planFills(
      previous: SchemaModel,
      target: SchemaModel,
      operations: Vector[SchemaOperation],
      pending: Vector[Backfill]
  ): Map[SchemaId, (Backfill, BoundStatement)] =
    val required = operations.collect { case operation: SchemaOperation.SetNotNull => operation.columnId -> operation }.toMap
    val applicable = pending.filter(backfill => required.contains(backfill.target))
    val conflicts = applicable.groupBy(_.target).toVector.sortBy(_._1.value).collect {
      case (column, rules) if rules.size > 1 =>
        s"Backfills ${rules.map(rule => s"'${rule.id}'").sorted.mkString(", ")} all fill column '${column.value}'; " +
          "only one may apply"
    }
    if conflicts.nonEmpty then refuse(conflicts)
    val filled = applicable.map(_.target).toSet
    val resolved = applicable.map { backfill =>
      val operation = required(backfill.target)
      val table = target.tables.find(_.id == operation.tableId).get
      val earlier = previous.tables.find(_.id == operation.tableId).toVector.flatMap(_.columns)
        .filterNot(column => table.columns.exists(_.id == column.id))
      val column = table.columns.find(_.id == backfill.target).get
      BackfillValidation.resolve(backfill, table.name, column, table.columns ++ earlier, filled - backfill.target)
        .flatMap(backend.renderFill)
        .map(statement => backfill.target -> (backfill, statement))
    }
    val errors = resolved.flatMap(_.left.toOption).flatten
    if errors.nonEmpty then refuse(errors)
    resolved.flatMap(_.toOption).toMap

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
  private def run(connection: Connection, step: Step): Option[(Backfill, Long)] = step match
    case Step.Statement(sql) =>
      withStatement(connection.createStatement())(_.execute(sql))
      None
    case Step.Fill(backfill, bound) =>
      val rows = withStatement(connection.prepareStatement(bound.sql)) { statement =>
        bound.parameters.zipWithIndex.foreach((value, index) => statement.setObject(index + 1, value))
        statement.executeUpdate()
      }
      Some(backfill -> rows.toLong)
    case Step.NoNulls(columnId, column, query, hint) =>
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
