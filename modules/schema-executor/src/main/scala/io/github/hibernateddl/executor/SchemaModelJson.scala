package io.github.hibernateddl.executor

import io.github.hibernateddl.core.*
import scala.collection.mutable
import scala.util.control.NoStackTrace

/** The JSON form of a schema model that every history entry stores, so that the next server
  * start plans against the model applied last. Writing escapes every non-ASCII character, so
  * the text survives any transport unchanged. Reading is strict: another format version, a
  * missing or unknown field and an unknown type are errors, never skipped.
  */
object SchemaModelJson:
  val FormatVersion = 1
  private val VarcharType = """varchar\((\d+)\)""".r

  def encode(model: SchemaModel): String =
    def string(value: String): String =
      val out = new StringBuilder("\"")
      value.foreach {
        case '"' => out ++= "\\\""
        case '\\' => out ++= "\\\\"
        case c if c < ' ' || c > '~' => out ++= f"\\u${c.toInt}%04x"
        case c => out += c
      }
      out.append('"').toString
    def optional(identifier: Option[SqlIdentifier]) = identifier.fold("null")(value => string(value.value))
    def list(values: Vector[String]) = values.mkString("[", ",", "]")
    val tables = model.tables.map { table =>
      val columns = table.columns.map { column =>
        s"""{"id":${string(column.id.value)},"name":${string(column.name.value)},""" +
          s""""type":${string(typeName(column.dataType))},"nullable":${column.nullable}}"""
      }
      s"""{"id":${string(table.id.value)},"catalog":${optional(table.name.catalog)},""" +
        s""""schema":${optional(table.name.schema)},"name":${string(table.name.name.value)},""" +
        s""""columns":${list(columns)},"primaryKey":${list(table.primaryKey.map(id => string(id.value)))}}"""
    }
    s"""{"format":$FormatVersion,"tables":${list(tables)}}"""

  def decode(json: String): Either[String, SchemaModel] =
    try Right(readModel(new Parser(json).document()))
    catch
      case error: InvalidJson => Left(error.getMessage)
      case error: IllegalArgumentException => Left(error.getMessage)

  private def typeName(dataType: SqlType): String = dataType match
    case SqlType.Varchar(length) => s"varchar($length)"
    case SqlType.Integer => "integer"
    case SqlType.BigInt => "bigint"
    case SqlType.Boolean => "boolean"
    case SqlType.Text => "text"

  private enum Json:
    case Obj(fields: Map[String, Json])
    case Arr(items: Vector[Json])
    case Str(value: String)
    case Num(value: BigInt)
    case Bool(value: Boolean)
    case Null

  private final class InvalidJson(message: String) extends RuntimeException(message) with NoStackTrace

  private def invalid(message: String): Nothing = throw new InvalidJson(message)

  private def readModel(json: Json): SchemaModel =
    json match
      case Json.Obj(values) => values.get("format") match
        case Some(Json.Num(version)) if version == FormatVersion => ()
        case Some(Json.Num(version)) => invalid(s"Model format $version is unsupported; expected format $FormatVersion")
        case _ => invalid("Model format is missing")
      case _ => ()
    val model = fields(json, "Model", Set("format", "tables"))
    SchemaModel(array(model("tables"), "Model tables").map(readTable))

  private def readTable(json: Json): TableModel =
    val table = fields(json, "Table", Set("id", "catalog", "schema", "name", "columns", "primaryKey"))
    val id = string(table("id"), "Table id")
    def label(field: String) = s"Table '$id' $field"
    TableModel(
      SchemaId(id),
      QualifiedName(
        SqlIdentifier(string(table("name"), label("name"))),
        optional(table("schema"), label("schema")).map(SqlIdentifier(_)),
        optional(table("catalog"), label("catalog")).map(SqlIdentifier(_))
      ),
      array(table("columns"), label("columns")).map(readColumn(_, id)),
      array(table("primaryKey"), label("primary key")).map(key => SchemaId(string(key, label("primary key"))))
    )

  private def readColumn(json: Json, tableId: String): ColumnModel =
    val column = fields(json, s"Column in table '$tableId'", Set("id", "name", "type", "nullable"))
    val id = string(column("id"), s"Column id in table '$tableId'")
    val dataType = string(column("type"), s"Column '$id' type") match
      case "integer" => SqlType.Integer
      case "bigint" => SqlType.BigInt
      case "boolean" => SqlType.Boolean
      case "text" => SqlType.Text
      case VarcharType(length) => SqlType.Varchar(length.toIntOption.getOrElse(invalid(s"Column '$id' has VARCHAR length $length")))
      case other => invalid(s"Column '$id' has unknown type '$other'")
    val nullable = column("nullable") match
      case Json.Bool(value) => value
      case _ => invalid(s"Column '$id' nullable must be true or false")
    ColumnModel(SchemaId(id), SqlIdentifier(string(column("name"), s"Column '$id' name")), dataType, nullable)

  private def fields(json: Json, label: String, expected: Set[String]): Map[String, Json] = json match
    case Json.Obj(values) if values.keySet == expected => values
    case Json.Obj(values) =>
      invalid(s"$label has fields ${values.keySet.toVector.sorted.mkString(", ")}; expected ${expected.toVector.sorted.mkString(", ")}")
    case _ => invalid(s"$label must be an object")

  private def array(json: Json, label: String): Vector[Json] = json match
    case Json.Arr(items) => items
    case _ => invalid(s"$label must be an array")

  private def string(json: Json, label: String): String = json match
    case Json.Str(value) => value
    case _ => invalid(s"$label must be a string")

  private def optional(json: Json, label: String): Option[String] = json match
    case Json.Null => None
    case other => Some(string(other, label))

  /** RFC 8259 JSON, restricted to integer numbers and rejecting duplicate fields. */
  private final class Parser(text: String):
    private var position = 0

    def document(): Json =
      val result = value()
      skipWhitespace()
      if position != text.length then fail("unexpected trailing content")
      result

    private def fail(message: String): Nothing = invalid(s"Invalid JSON at offset $position: $message")

    private def value(): Json =
      skipWhitespace()
      if position >= text.length then fail("unexpected end")
      text.charAt(position) match
        case '{' => obj()
        case '[' => arr()
        case '"' => Json.Str(str())
        case 't' => literal("true", Json.Bool(true))
        case 'f' => literal("false", Json.Bool(false))
        case 'n' => literal("null", Json.Null)
        case c if c == '-' || (c >= '0' && c <= '9') => num()
        case c => fail(s"unexpected character '$c'")

    private def obj(): Json =
      val values = mutable.LinkedHashMap.empty[String, Json]
      elements('}') { () =>
        skipWhitespace()
        if position >= text.length || text.charAt(position) != '"' then fail("expected a field name")
        val key = str()
        if values.contains(key) then fail(s"duplicate field '$key'")
        skipWhitespace()
        expect(':')
        values(key) = value()
      }
      Json.Obj(values.toMap)

    private def arr(): Json =
      val items = Vector.newBuilder[Json]
      elements(']')(() => items += value())
      Json.Arr(items.result())

    private def elements(close: Char)(element: () => Unit): Unit =
      position += 1
      skipWhitespace()
      if !consume(close) then
        element()
        skipWhitespace()
        while consume(',') do
          element()
          skipWhitespace()
        expect(close)

    private def str(): String =
      position += 1
      val out = new StringBuilder
      var closed = false
      while !closed do
        if position >= text.length then fail("unterminated string")
        val c = text.charAt(position)
        position += 1
        c match
          case '"' => closed = true
          case '\\' =>
            if position >= text.length then fail("unterminated escape")
            val escape = text.charAt(position)
            position += 1
            escape match
              case '"' | '\\' | '/' => out += escape
              case 'b' => out += '\b'
              case 'f' => out += '\f'
              case 'n' => out += '\n'
              case 'r' => out += '\r'
              case 't' => out += '\t'
              case 'u' =>
                val hex = text.slice(position, position + 4)
                if !hex.matches("[0-9a-fA-F]{4}") then fail("invalid unicode escape")
                out += Integer.parseInt(hex, 16).toChar
                position += 4
              case other => fail(s"invalid escape '\\$other'")
          case c if c < ' ' => fail("unescaped control character in string")
          case c => out += c
      out.toString

    private def num(): Json =
      val start = position
      if text.charAt(position) == '-' then position += 1
      while position < text.length && text.charAt(position) >= '0' && text.charAt(position) <= '9' do position += 1
      val digits = text.substring(start, position)
      if !digits.matches("-?(?:0|[1-9][0-9]*)") then fail(s"invalid number '$digits'")
      if position < text.length && ".eE".indexOf(text.charAt(position)) >= 0 then fail("only integers are supported")
      Json.Num(BigInt(digits))

    private def literal(word: String, result: Json): Json =
      if !text.startsWith(word, position) then fail("unknown literal")
      position += word.length
      result

    private def skipWhitespace(): Unit =
      while position < text.length && " \t\n\r".indexOf(text.charAt(position)) >= 0 do position += 1

    private def consume(c: Char): Boolean =
      val found = position < text.length && text.charAt(position) == c
      if found then position += 1
      found

    private def expect(c: Char): Unit = if !consume(c) then fail(s"expected '$c'")
