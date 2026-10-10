package com.anjunar.hibernateddl.core

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** A rule that fills the NULLs of one column while the column becomes required. The ID names
  * the rule for good, independently of any schema ID; a changed rule needs a new ID. Rows that
  * are not NULL keep their value, and no default is left behind for later inserts.
  */
final case class Backfill(id: String, target: SchemaId, when: BackfillTrigger, value: BackfillValue)

object Backfill:
  def fillNulls(id: String, target: SchemaId, when: BackfillTrigger, value: BackfillValue): Backfill =
    Backfill(id, target, when, value)
