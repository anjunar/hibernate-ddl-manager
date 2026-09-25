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

/** Revision 0 is the empty model of a database without history. */
final case class MigrationResult(revision: Long, status: MigrationStatus, statementCount: Int)

/** Unknown means the server must stop and reconcile history before retrying. */
enum FailureState:
  case NotStarted, RolledBack, OutcomeUnknown

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

/** Connection-taking methods run inside one executor-owned transaction.
  * validate and render run before a connection is acquired or while planning under
  * the lock. Implementations must support transactional DDL and never commit or close
  * the supplied connection.
  */
trait TransactionalMigrationBackend extends SchemaDialect:
  def validate(model: SchemaModel): Vector[String]
  def acquireLock(connection: Connection, options: ExecutionOptions): Unit
  def initializeHistory(connection: Connection): Unit
  /** Every history entry, ordered by revision. */
  def readHistory(connection: Connection): Vector[HistoryEntry]
  /** The model's tables and sequences whose names are taken by any relation in the database. */
  def existingRelations(connection: Connection, model: SchemaModel): Vector[QualifiedName]
  def lockAndValidate(connection: Connection, expected: SchemaModel): Vector[String]
  def recordHistory(connection: Connection, entry: HistoryEntry): Unit
