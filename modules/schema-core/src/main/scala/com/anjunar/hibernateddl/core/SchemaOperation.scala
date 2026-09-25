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
