package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.sql.Connection

/** `adoptExistingSchema` lets a database without history whose tables and sequences already
  * exist be recorded as revision 1 without DDL, provided it matches the target exactly.
  * Without it such a database is refused. `approvals` permit the changes that are refused by
  * default because they delete data or look like an older server; each names what it permits.
  * `allowedRisks` limits the operation classes that may run at all. `acceptManualMigration`
  * names the fingerprint of a target that an operator has applied to the database by hand,
  * for a change the executor cannot plan; that target is verified and recorded without DDL.
  */
final case class ExecutionOptions(
    lockTimeoutMillis: Int = 5000,
    statementTimeoutMillis: Int = 30000,
    allowedRisks: Set[RiskLevel] = Set(RiskLevel.Safe, RiskLevel.Locking, RiskLevel.Destructive),
    adoptExistingSchema: Boolean = false,
    approvals: Set[Approval] = Set.empty,
    acceptManualMigration: Option[String] = None
)

/** An explicit permission for one change that is refused by default. An approval only permits:
  * a change that is not planned leaves it unused.
  */
enum Approval:
  /** Drop the table, column or sequence with this stable ID, and its data. */
  case Drop(id: SchemaId)
  /** Rename the table, column or sequence with this stable ID back to a name it had in an
    * earlier revision.
    */
  case RenameBack(id: SchemaId)
  /** Migrate to a target equal to the model of this earlier revision. */
  case Revert(revision: Long)

/** Adopted: an existing database matched the target and was recorded as revision 1.
  * ManuallyMigrated: the database matched a target migrated by hand and was recorded as the
  * next revision.
  */
enum MigrationStatus:
  case Applied, AlreadyApplied, Adopted, ManuallyMigrated

/** Revision 0 is the empty model of a database without history. `backfills` lists what this
  * start recorded for backfills; `pendingBackfills` names the registered backfills that are
  * not recorded yet and did not apply, because none of their columns became required.
  */
final case class MigrationResult(
    revision: Long,
    status: MigrationStatus,
    statementCount: Int,
    backfills: Vector[BackfillOutcome] = Vector.empty,
    pendingBackfills: Vector[String] = Vector.empty
)

/** How a database reached the state a backfill's column is required in. */
enum BackfillResult:
  /** The backfill filled the column's NULLs while it became required. */
  case Executed
  /** The column was created required with a new table, so there was nothing to fill. */
  case NotRequiredOnCreation
  /** The column was found required when the database was adopted or migrated by hand. */
  case Adopted

/** `updatedRows` is known only for an executed backfill. */
final case class BackfillOutcome(id: String, result: BackfillResult, updatedRows: Option[Long])

/** A recorded backfill: its definition's checksum in the given format, and the schema
  * revision whose migration recorded it. Each ID is recorded once.
  */
final case class BackfillRecord(
    id: String,
    format: Int,
    checksum: String,
    target: SchemaId,
    revision: Long,
    previousFingerprint: String,
    targetFingerprint: String,
    result: BackfillResult,
    updatedRows: Option[Long]
)

/** SQL with JDBC parameters, bound in order. */
final case class BoundStatement(sql: String, parameters: Vector[AnyRef])

/** Unknown means the server must stop and reconcile history before retrying. Committed means
  * the migration committed, but the connection could not be restored to the state it arrived
  * in and was aborted; the schema is migrated, and the next start finds it applied.
  */
enum FailureState:
  case NotStarted, RolledBack, OutcomeUnknown, Committed

final class MigrationException(
    message: String,
    val state: FailureState,
    cause: Throwable = null
) extends RuntimeException(message, cause)

/** One applied migration. Revisions count up from 1 without gaps; the first entry's previous
  * fingerprint is that of the empty model. `model` is the applied target in
  * [[SchemaModelJson]] form, which the next server start diffs against.
  */
final case class HistoryEntry(
    revision: Long,
    previousFingerprint: String,
    targetFingerprint: String,
    model: String,
    statements: Vector[String]
)

/** How [[TransactionalMigrationBackend.lockAndValidate]] locks the tables it inspects until the
  * transaction ends. Exclusive keeps everyone out, as before and after DDL. Shared only keeps
  * schema changes out and lets the application read and write, for checking a schema that is
  * already applied at every server start.
  */
enum TableLock:
  case Exclusive, Shared

/** What planning needs from a backend: checks and SQL rendering without a connection. */
trait PlanningBackend extends SchemaDialect:
  def validate(model: SchemaModel): Vector[String]
  /** A query whose single row and column counts the rows in which the column is NULL. */
  def nullCount(table: QualifiedName, column: SqlIdentifier): String
  /** An UPDATE that fills the column's NULLs and binds every constant as a parameter. */
  def renderFill(fill: NullFill): Either[Vector[String], BoundStatement]

/** What the database knows about a column beyond the model. */
trait ColumnDependencies:
  /** The objects outside the model, such as views, that depend on the column and keep its type
    * from changing; each described for a message. Neither a migration nor a preview removes them.
    */
  def typeChangeBlockers(connection: Connection, table: QualifiedName, column: SqlIdentifier): Vector[String]

/** What a read-only comparison of the database with a model found: differences, and what it
  * could not decide.
  */
final case class Inspection(differences: Vector[String], undecided: Vector[String])

/** Read-only access for a preview. Every method runs inside the transaction that
  * `beginReadOnly` made read-only on the server; nothing is created, altered, written or
  * explicitly locked, and no migration lock is taken.
  */
trait PreviewBackend extends PlanningBackend with ColumnDependencies:
  def beginReadOnly(connection: Connection, options: ExecutionOptions): Unit
  /** The schema history, or None when its table does not exist. */
  def readHistoryIfPresent(connection: Connection): Option[Vector[HistoryEntry]]
  /** The recorded backfills; none when their table does not exist. */
  def readBackfillsIfPresent(connection: Connection): Vector[BackfillRecord]
  /** The model's tables and sequences whose names are taken by any relation in the database. */
  def existingRelations(connection: Connection, model: SchemaModel): Vector[QualifiedName]
  def inspect(connection: Connection, expected: SchemaModel): Inspection
  /** Answers a data question: 1 or 0 for whether a matching row exists, or the number of
    * matching rows when `count` is set.
    */
  def dataCheck(connection: Connection, query: DataQuery, count: Boolean): Long
  /** The table's row count as the database last estimated it, if it has one. */
  def estimateRows(connection: Connection, table: QualifiedName): Option[Long]
  /** The bytes the table takes with its indexes and out-of-line storage, if known. */
  def tableSize(connection: Connection, table: QualifiedName): Option[Long]

/** Connection-taking methods run inside one executor-owned transaction.
  * validate and render run before a connection is acquired or while planning under
  * the lock. Implementations must support transactional DDL and never commit or close
  * the supplied connection.
  */
trait TransactionalMigrationBackend extends PlanningBackend with ColumnDependencies:
  def acquireLock(connection: Connection, options: ExecutionOptions): Unit
  def initializeHistory(connection: Connection): Unit
  /** Every history entry, ordered by revision. */
  def readHistory(connection: Connection): Vector[HistoryEntry]
  /** The model's tables and sequences whose names are taken by any relation in the database. */
  def existingRelations(connection: Connection, model: SchemaModel): Vector[QualifiedName]
  def lockAndValidate(connection: Connection, expected: SchemaModel, lock: TableLock): Vector[String]
  def recordHistory(connection: Connection, entry: HistoryEntry): Unit
  /** Every recorded backfill, ordered by revision and ID. */
  def readBackfills(connection: Connection): Vector[BackfillRecord]
  def recordBackfill(connection: Connection, record: BackfillRecord): Unit
