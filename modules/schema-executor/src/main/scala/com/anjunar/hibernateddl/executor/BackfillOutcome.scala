package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.sql.Connection

/** `updatedRows` is known only for an executed backfill. */
final case class BackfillOutcome(id: String, result: BackfillResult, updatedRows: Option[Long])
