package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*

/** A number of rows. Unknown is never zero. */
enum RowCount:
  case Exact(rows: Long)
  case Estimate(rows: Long)
  case Unknown
