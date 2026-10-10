package com.anjunar.hibernateddl.core

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.math.{BigDecimal as JavaBigDecimal}
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util

/** A typed constant. Each kind fills only the column types it matches without conversion. */
enum BackfillLiteral:
  case Text(value: String)
  case WholeNumber(value: Long)
  case Decimal(value: JavaBigDecimal)
  case FloatingPoint(value: Double)
  case Bool(value: Boolean)
  case Uuid(value: util.UUID)
  case Date(value: LocalDate)
  case Time(value: LocalTime)
  case Timestamp(value: LocalDateTime)
  case TimestampWithTimeZone(value: OffsetDateTime)
