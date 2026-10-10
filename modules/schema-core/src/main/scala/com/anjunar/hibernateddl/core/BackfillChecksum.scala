package com.anjunar.hibernateddl.core

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.lang.{Double as JavaDouble}

/** The canonical SHA-256 of a backfill's definition: target, trigger, the value's structure
  * and its typed constants, but not its ID. The encoding is versioned by `Format`.
  */
object BackfillChecksum:
  val Format: Int = 1

  def of(backfill: Backfill): String =
    val bytes = new ByteArrayOutputStream()
    val out = new DataOutputStream(bytes)
    def string(value: String): Unit =
      val encoded = value.getBytes(StandardCharsets.UTF_8)
      out.writeInt(encoded.length)
      out.write(encoded)
    def value(node: BackfillValue): Unit = node match
      case BackfillValue.Literal(literal) =>
        string("literal")
        literal match
          case BackfillLiteral.Text(text)            => string("text"); string(text)
          case BackfillLiteral.WholeNumber(number)   => string("whole number"); out.writeLong(number)
          case BackfillLiteral.Decimal(number)       => string("decimal"); string(number.toString)
          case BackfillLiteral.FloatingPoint(number) =>
            string("floating point"); out.writeLong(JavaDouble.doubleToLongBits(number))
          case BackfillLiteral.Bool(flag)                       => string("boolean"); out.writeBoolean(flag)
          case BackfillLiteral.Uuid(uuid)                       => string("uuid"); string(uuid.toString)
          case BackfillLiteral.Date(date)                       => string("date"); string(date.toString)
          case BackfillLiteral.Time(time)                       => string("time"); string(time.toString)
          case BackfillLiteral.Timestamp(timestamp)             => string("timestamp"); string(timestamp.toString)
          case BackfillLiteral.TimestampWithTimeZone(timestamp) =>
            string("timestamp with time zone"); string(timestamp.toString)
      case BackfillValue.Column(id)       => string("column"); string(id.value)
      case BackfillValue.Coalesce(values) => string("coalesce"); out.writeInt(values.size); values.foreach(value)
      case BackfillValue.Concat(values)   => string("concat"); out.writeInt(values.size); values.foreach(value)
    string(s"hibernate-ddl-backfill-v$Format")
    string(backfill.target.value)
    string(backfill.when match { case BackfillTrigger.BecomesRequired => "becomes required" })
    value(backfill.value)
    out.flush()
    MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray).map(byte => f"${byte & 0xff}%02x").mkString
