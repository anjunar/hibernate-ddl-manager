package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.sql.Connection

/** `adoptExistingSchema` lets a database without history whose tables and sequences already
  * exist be recorded as revision 1 without DDL, provided it matches the target exactly.
  * Without it such a database is refused. `approvals` permit the changes that are refused by
  * default because they delete data or look like an older server; each names what it permits.
  * `allowedRisks` limits the operation classes that may run at all. `acceptManualMigration`
  * names the fingerprint of a target that an operator has applied to the database by hand,
  * for a change the executor cannot plan; that target is verified and recorded without DDL.
  */
final case class ExecutionOptions(
    lockTimeoutMillis: Int = 5000,
    statementTimeoutMillis: Int = 30000,
    allowedRisks: Set[RiskLevel] = Set(RiskLevel.Safe, RiskLevel.Locking, RiskLevel.Destructive),
    adoptExistingSchema: Boolean = false,
    approvals: Set[Approval] = Set.empty,
    acceptManualMigration: Option[String] = None
)
