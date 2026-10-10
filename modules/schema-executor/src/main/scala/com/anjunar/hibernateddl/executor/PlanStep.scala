package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*

/** One step of a migration, in execution order. */
enum PlanStep:
  /** DDL for one operation; `approval` names what the step needs, `approved` whether it has it. */
  case Statement(operation: SchemaOperation, sql: String, approval: Option[Approval], approved: Boolean)

  /** A backfill filling a column's NULLs. */
  case Fill(backfill: Backfill, column: SchemaId, statement: BoundStatement)

  /** A check that a column has no NULL left before it becomes required. */
  case NoNulls(columnId: SchemaId, column: String, query: String, hint: String)
