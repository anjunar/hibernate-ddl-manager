package com.anjunar.hibernateddl.core

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
enum FillValue:
  case Literal(value: BackfillLiteral, as: SqlType)
  case Column(name: SqlIdentifier)

  /** A column that does not exist yet and is NULL when the backfill runs. */
  case Null(as: SqlType)
  case Coalesce(values: Vector[FillValue])
  case Concat(values: Vector[FillValue])
