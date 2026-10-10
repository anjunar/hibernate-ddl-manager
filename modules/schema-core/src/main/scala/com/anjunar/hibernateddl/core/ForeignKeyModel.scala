package com.anjunar.hibernateddl.core

/** A foreign key references the primary key or a unique key of a table in the same model. Columns and
  * referenced columns pair up in key order; both are IDs, so renames keep the key intact.
  * A table has at most one foreign key per column list, which is its identity.
  */
final case class ForeignKeyModel(
  columns: Vector[SchemaId],
  referencedTable: SchemaId,
  referencedColumns: Vector[SchemaId],
  onDeleteCascade: Boolean = false
):
  def display: String = columns.map(_.value).mkString("(", ", ", ")")
