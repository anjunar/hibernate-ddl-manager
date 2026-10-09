package com.anjunar.hibernateddl.core

/** A plain, non-unique B-tree index over columns in key order; the column list, including
  * each column's direction, is its identity. Unique indexes are unique keys.
  */
final case class IndexModel(columns: Vector[IndexColumn]):
  def display: String =
    columns.map(c => if c.descending then s"${c.column.value} DESC" else c.column.value).mkString("(", ", ", ")")
