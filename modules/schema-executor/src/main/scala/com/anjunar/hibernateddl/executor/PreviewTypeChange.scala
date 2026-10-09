package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*

/** What a type change does, apart from its SQL. Keeping the values follows from the rule; a
  * rewrite of the table and its indexes is `possible` or `expected` and holds the locks longer.
  * `affected` lists the unique keys, indexes and check the database rebuilds with the column.
  */
final case class PreviewTypeChange(
    column: String,
    from: String,
    to: String,
    rule: String,
    valuesPreserved: Boolean,
    rewrite: String,
    affected: Vector[String]
)

