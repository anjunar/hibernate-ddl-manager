package com.anjunar.hibernateddl.core

/** A named table CHECK with a trusted SQL predicate from the application mapping.
  * Its name and expression are retained in history. Changes to the predicate, or column
  * renames/drops in its table, require a manual migration because the SQL is not parsed.
  */
final case class TableCheck(name: SqlIdentifier, expression: String)
