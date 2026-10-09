package com.anjunar.hibernateddl.core

/** Tables and sequences share one name space, as relations do in PostgreSQL. */
final case class SchemaModel(tables: Vector[TableModel], sequences: Vector[SequenceModel] = Vector.empty)
