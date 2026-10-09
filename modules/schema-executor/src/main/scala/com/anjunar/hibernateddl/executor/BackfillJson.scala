package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import SchemaModelJson.{Json, InvalidJson, Parser, array, fields, invalid, string}

/** The versioned JSON form of backfill definitions, for tools such as the preview command that
  * cannot load the application's providers. Constants keep their type and are written as text,
  * so numbers survive exactly; reading is strict like [[SchemaModelJson]].
  */
object BackfillJson:
  val FormatVersion = 1

  def encode(backfills: Vector[Backfill]): String =
    def text(value: String): String =
      val out = new StringBuilder("\"")
      value.foreach {
        case '"' => out ++= "\\\""
        case '\\' => out ++= "\\\\"
        case c if c < ' ' || c > '~' => out ++= f"\\u${c.toInt}%04x"
        case c => out += c
      }
      out.append('"').toString
    def literal(kind: String, value: String) = s"""{"literal":{"type":${text(kind)},"value":${text(value)}}}"""
    def value(node: BackfillValue): String = node match
      case BackfillValue.Literal(BackfillLiteral.Text(v)) => literal("text", v)
      case BackfillValue.Literal(BackfillLiteral.WholeNumber(v)) => literal("whole number", v.toString)
      case BackfillValue.Literal(BackfillLiteral.Decimal(v)) => literal("decimal", v.toString)
      case BackfillValue.Literal(BackfillLiteral.FloatingPoint(v)) => literal("floating point", v.toString)
      case BackfillValue.Literal(BackfillLiteral.Bool(v)) => literal("boolean", v.toString)
      case BackfillValue.Literal(BackfillLiteral.Uuid(v)) => literal("uuid", v.toString)
      case BackfillValue.Literal(BackfillLiteral.Date(v)) => literal("date", v.toString)
      case BackfillValue.Literal(BackfillLiteral.Time(v)) => literal("time", v.toString)
      case BackfillValue.Literal(BackfillLiteral.Timestamp(v)) => literal("timestamp", v.toString)
      case BackfillValue.Literal(BackfillLiteral.TimestampWithTimeZone(v)) => literal("timestamp with time zone", v.toString)
      case BackfillValue.Column(id) => s"""{"column":${text(id.value)}}"""
      case BackfillValue.Coalesce(values) => s"""{"coalesce":${values.map(value).mkString("[", ",", "]")}}"""
      case BackfillValue.Concat(values) => s"""{"concat":${values.map(value).mkString("[", ",", "]")}}"""
    val items = backfills.map { backfill =>
      val when = backfill.when match { case BackfillTrigger.BecomesRequired => "becomes required" }
      s"""{"id":${text(backfill.id)},"target":${text(backfill.target.value)},"when":${text(when)},"value":${value(backfill.value)}}"""
    }
    s"""{"format":$FormatVersion,"backfills":${items.mkString("[", ",", "]")}}"""

  def decode(json: String): Either[String, Vector[Backfill]] =
    try
      val root = fields(new Parser(json).document(), "Backfills", Set("format", "backfills"))
      root("format") match
        case Json.Num(format) if format == FormatVersion => ()
        case other => invalid(s"Unsupported backfill format $other")
      Right(array(root("backfills"), "backfills").map(read))
    catch
      case error: InvalidJson => Left(error.getMessage)
      case error: IllegalArgumentException => Left(error.getMessage)
      case error: java.time.DateTimeException => Left(error.getMessage)

  private def read(json: Json): Backfill =
    val item = fields(json, "Backfill", Set("id", "target", "when", "value"))
    val id = string(item("id"), "Backfill id")
    val when = string(item("when"), s"Backfill '$id' when") match
      case "becomes required" => BackfillTrigger.BecomesRequired
      case other => invalid(s"Backfill '$id' has the unknown trigger '$other'")
    Backfill(id, SchemaId(string(item("target"), s"Backfill '$id' target")), when, value(item("value"), id))

  private def value(json: Json, id: String): BackfillValue = json match
    case Json.Obj(values) if values.keySet == Set("literal") =>
      val literal = fields(values("literal"), s"Backfill '$id' literal", Set("type", "value"))
      val text = string(literal("value"), s"Backfill '$id' literal value")
      def number[A](parse: String => A) =
        try parse(text) catch case _: NumberFormatException => invalid(s"Backfill '$id' has the invalid number '$text'")
      BackfillValue.Literal(string(literal("type"), s"Backfill '$id' literal type") match
        case "text" => BackfillLiteral.Text(text)
        case "whole number" => BackfillLiteral.WholeNumber(number(_.toLong))
        case "decimal" => BackfillLiteral.Decimal(number(java.math.BigDecimal(_)))
        case "floating point" => BackfillLiteral.FloatingPoint(number(_.toDouble))
        case "boolean" => BackfillLiteral.Bool(text match
          case "true" => true
          case "false" => false
          case other => invalid(s"Backfill '$id' has the invalid boolean '$other'"))
        case "uuid" => BackfillLiteral.Uuid(java.util.UUID.fromString(text))
        case "date" => BackfillLiteral.Date(java.time.LocalDate.parse(text))
        case "time" => BackfillLiteral.Time(java.time.LocalTime.parse(text))
        case "timestamp" => BackfillLiteral.Timestamp(java.time.LocalDateTime.parse(text))
        case "timestamp with time zone" => BackfillLiteral.TimestampWithTimeZone(java.time.OffsetDateTime.parse(text))
        case other => invalid(s"Backfill '$id' has the unknown constant type '$other'"))
    case Json.Obj(values) if values.keySet == Set("column") => BackfillValue.Column(SchemaId(string(values("column"), "column")))
    case Json.Obj(values) if values.keySet == Set("coalesce") =>
      BackfillValue.Coalesce(array(values("coalesce"), "coalesce").map(value(_, id)))
    case Json.Obj(values) if values.keySet == Set("concat") =>
      BackfillValue.Concat(array(values("concat"), "concat").map(value(_, id)))
    case _ => invalid(s"Backfill '$id' has a value that is no literal, column, coalesce or concat")
