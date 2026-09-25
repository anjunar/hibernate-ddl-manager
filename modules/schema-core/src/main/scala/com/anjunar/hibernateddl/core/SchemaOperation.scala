package com.anjunar.hibernateddl.core

enum RiskLevel:
  case Safe, Locking, PotentiallyDestructive, Destructive, Manual

sealed trait SchemaOperation:
  def risk: RiskLevel

object SchemaOperation:
  final case class CreateSequence(sequence: SequenceModel) extends SchemaOperation:
    val risk: RiskLevel = RiskLevel.Safe

  final case class RenameSequence(
      sequenceId: SchemaId,
      from: QualifiedName,
      to: QualifiedName
  ) extends SchemaOperation:
    val risk: RiskLevel = RiskLevel.Locking

  final case class CreateTable(table: TableModel) extends SchemaOperation:
    val risk: RiskLevel = RiskLevel.Locking

  final case class AddColumn(
      tableId: SchemaId,
      table: QualifiedName,
      column: ColumnModel
  ) extends SchemaOperation:
    val risk: RiskLevel = RiskLevel.Locking

  final case class RenameTable(
      tableId: SchemaId,
      from: QualifiedName,
      to: QualifiedName
  ) extends SchemaOperation:
    val risk: RiskLevel = RiskLevel.Locking

  final case class AddUniqueKey(
      tableId: SchemaId,
      table: QualifiedName,
      columns: Vector[SqlIdentifier]
  ) extends SchemaOperation:
    val risk: RiskLevel = RiskLevel.Locking

  /** Replaces a column's CHECK constraint; either side may be absent. Adding a check
    * validates the existing rows.
    */
  final case class ChangeCheck(
      tableId: SchemaId,
      table: QualifiedName,
      columnId: SchemaId,
      column: SqlIdentifier,
      from: Option[ColumnCheck],
      to: Option[ColumnCheck]
  ) extends SchemaOperation:
    val risk: RiskLevel = RiskLevel.Locking

  /** Changes the type of an existing column along one of the widenings of [[TypeChangeRules]],
    * which keep every value; name, nullability, unique keys and indexes stay. A check on the
    * column is removed before and set again after, so that the database builds it for the new
    * type.
    */
  final case class ChangeColumnType(
      tableId: SchemaId,
      table: QualifiedName,
      columnId: SchemaId,
      column: SqlIdentifier,
      from: SqlType,
      to: SqlType
  ) extends SchemaOperation:
    val risk: RiskLevel = RiskLevel.Locking

  final case class IndexedColumn(name: SqlIdentifier, descending: Boolean)

  final case class CreateIndex(
      tableId: SchemaId,
      table: QualifiedName,
      columns: Vector[IndexedColumn]
  ) extends SchemaOperation:
    val risk: RiskLevel = RiskLevel.Locking

  /** Runs after every table exists, so new tables may reference each other or themselves. */
  final case class AddForeignKey(
      tableId: SchemaId,
      table: QualifiedName,
      columns: Vector[SqlIdentifier],
      referencedTable: QualifiedName,
      referencedColumns: Vector[SqlIdentifier]
  ) extends SchemaOperation:
    val risk: RiskLevel = RiskLevel.Locking

  final case class RenameColumn(
      tableId: SchemaId,
      table: QualifiedName,
      columnId: SchemaId,
      from: SqlIdentifier,
      to: SqlIdentifier
  ) extends SchemaOperation:
    val risk: RiskLevel = RiskLevel.Locking

  /** Makes an existing column required. PostgreSQL checks every row; the executor first fills
    * NULLs from a registered backfill and counts what is left, so that a failure names the
    * column instead of a constraint.
    */
  final case class SetNotNull(
      tableId: SchemaId,
      table: QualifiedName,
      columnId: SchemaId,
      column: SqlIdentifier
  ) extends SchemaOperation:
    val risk: RiskLevel = RiskLevel.Locking

  final case class DropNotNull(
      tableId: SchemaId,
      table: QualifiedName,
      columnId: SchemaId,
      column: SqlIdentifier
  ) extends SchemaOperation:
    val risk: RiskLevel = RiskLevel.Locking

  /** Deletes a column and its data. The database drops the column's own checks, indexes, unique
    * keys and foreign keys with it.
    */
  final case class DropColumn(
      tableId: SchemaId,
      table: QualifiedName,
      columnId: SchemaId,
      column: SqlIdentifier
  ) extends SchemaOperation:
    val risk: RiskLevel = RiskLevel.Destructive

  final case class DroppedTable(tableId: SchemaId, table: QualifiedName)

  /** Deletes tables and their data in one statement, so they may reference each other. */
  final case class DropTables(tables: Vector[DroppedTable]) extends SchemaOperation:
    val risk: RiskLevel = RiskLevel.Destructive

  final case class DropSequence(sequenceId: SchemaId, sequence: QualifiedName) extends SchemaOperation:
    val risk: RiskLevel = RiskLevel.Destructive
