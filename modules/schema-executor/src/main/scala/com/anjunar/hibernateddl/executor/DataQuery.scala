package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*

/** A read-only question about the data, asked of the table as it is now. */
enum DataQuery:
  /** Rows in which the value is NULL. */
  case Nulls(table: QualifiedName, value: Projection)
  /** Rows sharing a key whose parts are all not NULL. */
  case Duplicates(table: QualifiedName, key: Vector[Projection])
  /** Rows whose key, with every part not NULL, finds no referenced row; `referenced` is None
    * when the referenced table is new and empty.
    */
  case Unresolved(table: QualifiedName, key: Vector[Projection], referenced: Option[(QualifiedName, Vector[SqlIdentifier])])
  /** Rows for which the check on a column of this type is FALSE; NULL satisfies a check. */
  case Violations(table: QualifiedName, value: Projection, dataType: SqlType, check: ColumnCheck)

