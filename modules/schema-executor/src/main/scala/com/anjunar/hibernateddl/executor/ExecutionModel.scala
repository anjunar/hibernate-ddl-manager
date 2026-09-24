package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.sql.Connection

final case class ExecutionOptions(
    lockTimeoutMillis: Int = 5000,
    statementTimeoutMillis: Int = 30000,
    allowedRisks: Set[RiskLevel] = Set(RiskLevel.Safe, RiskLevel.Locking)
)

enum MigrationStatus:
  case Applied, AlreadyApplied

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
  def lockAndValidate(connection: Connection, expected: SchemaModel): Vector[String]
  def recordHistory(connection: Connection, entry: HistoryEntry): Unit
