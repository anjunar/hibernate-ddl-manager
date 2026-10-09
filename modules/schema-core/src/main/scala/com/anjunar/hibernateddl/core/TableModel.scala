package com.anjunar.hibernateddl.core

/** The primary key lists column IDs in key order, so column renames keep it intact. */
final case class TableModel(
    id: SchemaId,
    name: QualifiedName,
    columns: Vector[ColumnModel],
    primaryKey: Vector[SchemaId] = Vector.empty,
    foreignKeys: Vector[ForeignKeyModel] = Vector.empty,
    uniqueKeys: Vector[UniqueKeyModel] = Vector.empty,
    indexes: Vector[IndexModel] = Vector.empty,
    checks: Vector[TableCheck] = Vector.empty
)
