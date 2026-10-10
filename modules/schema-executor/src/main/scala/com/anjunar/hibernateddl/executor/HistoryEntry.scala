package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import java.sql.Connection

/** One applied migration. Revisions count up from 1 without gaps; the first entry's previous
  * fingerprint is that of the empty model. `model` is the applied target in
  * [[SchemaModelJson]] form, which the next server start diffs against.
  */
final case class HistoryEntry(
  revision: Long,
  previousFingerprint: String,
  targetFingerprint: String,
  model: String,
  statements: Vector[String]
)
