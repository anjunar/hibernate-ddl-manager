package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.sql.Connection

/** Read-only access for a preview. Every method runs inside the transaction that
  * `beginReadOnly` made read-only on the server; nothing is created, altered, written or
  * explicitly locked, and no migration lock is taken.
  */
trait PreviewBackend extends PlanningBackend with CatalogLookups:
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
