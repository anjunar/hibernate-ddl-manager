package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.sql.Connection

/** Unknown means the server must stop and reconcile history before retrying. Committed means
  * the migration committed, but the connection could not be restored to the state it arrived
  * in and was aborted; the schema is migrated, and the next start finds it applied.
  */
enum FailureState:
  case NotStarted, RolledBack, OutcomeUnknown, Committed
