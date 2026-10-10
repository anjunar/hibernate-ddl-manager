package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.sql.Connection

/** A recorded backfill: its definition's checksum in the given format, and the schema
  * revision whose migration recorded it. Each ID is recorded once.
  */
final case class BackfillRecord(
  id: String,
  format: Int,
  checksum: String,
  target: SchemaId,
  revision: Long,
  previousFingerprint: String,
  targetFingerprint: String,
  result: BackfillResult,
  updatedRows: Option[Long]
)
