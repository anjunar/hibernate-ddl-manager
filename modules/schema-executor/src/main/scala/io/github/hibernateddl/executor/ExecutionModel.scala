package io.github.hibernateddl.executor

import io.github.hibernateddl.core.*
import java.sql.Connection

final case class MigrationRequest(id: String, previous: SchemaSnapshot, target: SchemaSnapshot)

final case class ExecutionOptions(
    lockTimeoutMillis: Int = 5000,
    statementTimeoutMillis: Int = 30000,
    allowedRisks: Set[RiskLevel] = Set(RiskLevel.Safe, RiskLevel.Locking)
)

enum MigrationStatus:
  case Applied, AlreadyApplied

final case class MigrationResult(id: String, revision: Long, status: MigrationStatus, statementCount: Int)

/** Unknown means the server must stop and reconcile history before retrying. */
enum FailureState:
  case NotStarted, RolledBack, OutcomeUnknown

final class MigrationException(
    message: String,
    val state: FailureState,
    cause: Throwable = null
) extends RuntimeException(message, cause)

final case class HistoryEntry(
    id: String,
    checksum: String,
    fromRevision: Long,
    toRevision: Long,
    previousFingerprint: String,
    targetFingerprint: String
)

/** Connection-taking methods run inside one executor-owned transaction.
  * validate and render run before a connection is acquired. Implementations must
  * support transactional DDL and never commit or close the supplied connection.
  */
trait TransactionalMigrationBackend extends SchemaDialect:
  def name: String
  def validate(request: MigrationRequest): Vector[String]
  def acquireLock(connection: Connection, options: ExecutionOptions): Unit
  def initializeHistory(connection: Connection): Unit
  def findHistory(connection: Connection, id: String): Option[HistoryEntry]
  def latestHistory(connection: Connection): Option[HistoryEntry]
  def lockAndValidate(connection: Connection, expected: SchemaModel): Vector[String]
  def recordHistory(connection: Connection, entry: HistoryEntry): Unit
