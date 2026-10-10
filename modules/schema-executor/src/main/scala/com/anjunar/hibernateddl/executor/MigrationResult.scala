package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.sql.Connection

/** Revision 0 is the empty model of a database without history. `backfills` lists what this
  * start recorded for backfills; `pendingBackfills` names the registered backfills that are
  * not recorded yet and did not apply, because none of their columns became required.
  */
final case class MigrationResult(
  revision: Long,
  status: MigrationStatus,
  statementCount: Int,
  backfills: Vector[BackfillOutcome] = Vector.empty,
  pendingBackfills: Vector[String] = Vector.empty
)
