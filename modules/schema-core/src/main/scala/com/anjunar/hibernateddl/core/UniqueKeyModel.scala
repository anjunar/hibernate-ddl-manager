package com.anjunar.hibernateddl.core

/** A unique constraint over column IDs in key order; the column list is its identity. */
final case class UniqueKeyModel(columns: Vector[SchemaId]):
  def display: String = columns.map(_.value).mkString("(", ", ", ")")
