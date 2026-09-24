package com.anjunar.hibernateddl.core

enum RiskLevel:
  case Safe, Locking, PotentiallyDestructive, Destructive, Manual

sealed trait SchemaOperation:
  def risk: RiskLevel

object SchemaOperation:
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

  final case class RenameColumn(
      tableId: SchemaId,
      table: QualifiedName,
      columnId: SchemaId,
      from: SqlIdentifier,
      to: SqlIdentifier
  ) extends SchemaOperation:
    val risk: RiskLevel = RiskLevel.Locking
