package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*

/** A column's value as the migration will leave it, over the table as it is now. */
enum Projection:
  /** An existing column the migration keeps as it is, by its current name. */
  case Current(column: SqlIdentifier)
  /** A new column without a backfill, NULL in every row. */
  case Absent(dataType: SqlType)
  /** A column whose NULLs a backfill fills; `current` is None for a new column. */
  case Filled(current: Option[SqlIdentifier], value: FillValue, dataType: SqlType)
