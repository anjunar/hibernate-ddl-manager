package com.anjunar.hibernateddl.core

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** A typed constant. Each kind fills only the column types it matches without conversion. */
enum BackfillLiteral:
  case Text(value: String)
  case WholeNumber(value: Long)
  case Decimal(value: java.math.BigDecimal)
  case FloatingPoint(value: Double)
  case Bool(value: Boolean)
  case Uuid(value: java.util.UUID)
  case Date(value: java.time.LocalDate)
  case Time(value: java.time.LocalTime)
  case Timestamp(value: java.time.LocalDateTime)
  case TimestampWithTimeZone(value: java.time.OffsetDateTime)
