package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.sql.Connection

/** Connection-taking methods run inside one executor-owned transaction.
  * validate and render run before a connection is acquired or while planning under
  * the lock. Implementations must support transactional DDL and never commit or close
  * the supplied connection.
  */
trait TransactionalMigrationBackend extends PlanningBackend with CatalogLookups:
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
