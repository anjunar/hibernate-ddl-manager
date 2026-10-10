package com.anjunar.hibernateddl.core

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** A backfill resolved against the schema at the moment it runs: physical names, and the type
  * each constant takes.
  */
final case class NullFill(backfillId: String, table: QualifiedName, column: SqlIdentifier, value: FillValue)
