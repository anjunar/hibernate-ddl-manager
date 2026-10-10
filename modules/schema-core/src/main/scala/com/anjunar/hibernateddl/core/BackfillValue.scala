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

/** A value computed per row, from constants and other columns of the same row. */
enum BackfillValue:
  case Literal(value: BackfillLiteral)

  /** Another column of the target's table, by its stable ID. */
  case Column(id: SchemaId)

  /** The first of the values that is not NULL. */
  case Coalesce(values: Vector[BackfillValue])

  /** The values joined as text; NULL if any of them is NULL. */
  case Concat(values: Vector[BackfillValue])

object BackfillValue:
  def literal(value: String): BackfillValue = Literal(BackfillLiteral.Text(value))
  def literal(value: Long): BackfillValue = Literal(BackfillLiteral.WholeNumber(value))
  def literal(value: JavaBigDecimal): BackfillValue = Literal(BackfillLiteral.Decimal(value))
  def literal(value: Double): BackfillValue = Literal(BackfillLiteral.FloatingPoint(value))
  def literal(value: Boolean): BackfillValue = Literal(BackfillLiteral.Bool(value))
  def literal(value: util.UUID): BackfillValue = Literal(BackfillLiteral.Uuid(value))
  def literal(value: LocalDate): BackfillValue = Literal(BackfillLiteral.Date(value))
  def literal(value: LocalTime): BackfillValue = Literal(BackfillLiteral.Time(value))
  def literal(value: LocalDateTime): BackfillValue = Literal(BackfillLiteral.Timestamp(value))
  def literal(value: OffsetDateTime): BackfillValue = Literal(BackfillLiteral.TimestampWithTimeZone(value))
  def column(id: SchemaId): BackfillValue = Column(id)
  def coalesce(values: BackfillValue*): BackfillValue = Coalesce(values.toVector)
  def concat(values: BackfillValue*): BackfillValue = Concat(values.toVector)
