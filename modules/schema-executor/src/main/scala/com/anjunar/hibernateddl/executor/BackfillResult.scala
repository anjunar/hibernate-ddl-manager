package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.sql.Connection

/** How a database reached the state a backfill's column is required in. */
enum BackfillResult:
  /** The backfill filled the column's NULLs while it became required. */
  case Executed
  /** The column was created required with a new table, so there was nothing to fill. */
  case NotRequiredOnCreation
  /** The column was found required when the database was adopted or migrated by hand. */
  case Adopted
