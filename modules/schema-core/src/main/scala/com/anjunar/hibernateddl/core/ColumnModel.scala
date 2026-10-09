package com.anjunar.hibernateddl.core
final case class ColumnModel(
    id: SchemaId,
    name: SqlIdentifier,
    dataType: SqlType,
    nullable: Boolean = true,
    check: Option[ColumnCheck] = None,
    identity: Boolean = false
)
