package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import scala.collection.mutable
import scala.util.control.NoStackTrace

/** The JSON form of a schema model that every history entry stores, so that the next server
  * start plans against the model applied last. Writing escapes every non-ASCII character, so
  * the text survives any transport unchanged. Reading is strict: an unknown format version, a
  * missing or unknown field and an unknown type are errors, never skipped. Format 2 added
  * foreign keys, format 3 unique keys and format 4 indexes; the earlier formats are still read.
  */
object SchemaModelJson:
  val FormatVersion = 4
  private val VarcharType = """varchar\((\d+)\)""".r
  private val TimestampType = """timestamp\((\d+)\)""".r
  private val TimestampWithTimeZoneType = """timestamp\((\d+)\) with time zone""".r
  private val CharType = """char\((\d+)\)""".r
  private val NumericType = """numeric\((\d+),(\d+)\)""".r
  private val TimeType = """time\((\d+)\)""".r

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
    def ids(values: Vector[SchemaId]) = list(values.map(id => string(id.value)))
    def indexColumn(column: IndexColumn) =
      s"""{"id":${string(column.column.value)},"descending":${column.descending}}"""
    def foreignKey(key: ForeignKeyModel) =
      s"""{"columns":${ids(key.columns)},"referencedTable":${string(key.referencedTable.value)},""" +
        s""""referencedColumns":${ids(key.referencedColumns)}}"""
    val tables = model.tables.map { table =>
      val columns = table.columns.map { column =>
        s"""{"id":${string(column.id.value)},"name":${string(column.name.value)},""" +
          s""""type":${string(typeName(column.dataType))},"nullable":${column.nullable}}"""
      }
      s"""{"id":${string(table.id.value)},"catalog":${optional(table.name.catalog)},""" +
        s""""schema":${optional(table.name.schema)},"name":${string(table.name.name.value)},""" +
        s""""columns":${list(columns)},"primaryKey":${ids(table.primaryKey)},""" +
        s""""foreignKeys":${list(table.foreignKeys.map(foreignKey))},""" +
        s""""uniqueKeys":${list(table.uniqueKeys.map(key => s"""{"columns":${ids(key.columns)}}"""))},""" +
        s""""indexes":${list(table.indexes.map(index => s"""{"columns":${list(index.columns.map(indexColumn))}}"""))}}"""
    }
    s"""{"format":$FormatVersion,"tables":${list(tables)}}"""

  def decode(json: String): Either[String, SchemaModel] =
    try Right(readModel(new Parser(json).document()))
    catch
      case error: InvalidJson => Left(error.getMessage)
      case error: IllegalArgumentException => Left(error.getMessage)

  private def typeName(dataType: SqlType): String = dataType match
    case SqlType.Varchar(length) => s"varchar($length)"
    case SqlType.Timestamp(precision) => s"timestamp($precision)"
    case SqlType.TimestampWithTimeZone(precision) => s"timestamp($precision) with time zone"
    case SqlType.Integer => "integer"
    case SqlType.BigInt => "bigint"
    case SqlType.Boolean => "boolean"
    case SqlType.Text => "text"
    case SqlType.Uuid => "uuid"
    case SqlType.Char(length) => s"char($length)"
    case SqlType.Numeric(precision, scale) => s"numeric($precision,$scale)"
    case SqlType.Time(precision) => s"time($precision)"
    case SqlType.SmallInt => "smallint"
    case SqlType.Real => "real"
    case SqlType.DoublePrecision => "double precision"
    case SqlType.Date => "date"
    case SqlType.Binary => "binary"

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
    val format = json match
      case Json.Obj(values) => values.get("format") match
        case Some(Json.Num(version)) if version >= 1 && version <= FormatVersion => version.toInt
        case Some(Json.Num(version)) => invalid(s"Model format $version is unsupported; expected format 1 to $FormatVersion")
        case _ => invalid("Model format is missing")
      case _ => 0
    val model = fields(json, "Model", Set("format", "tables"))
    SchemaModel(array(model("tables"), "Model tables").map(readTable(_, format)))

  private def readTable(json: Json, format: Int): TableModel =
    val common = Set("id", "catalog", "schema", "name", "columns", "primaryKey")
    val table = fields(json, "Table",
      common ++ Option.when(format >= 2)("foreignKeys") ++ Option.when(format >= 3)("uniqueKeys") ++
        Option.when(format >= 4)("indexes"))
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
      ids(table("primaryKey"), label("primary key")),
      table.get("foreignKeys").fold(Vector.empty)(keys => array(keys, label("foreign keys")).map(readForeignKey(_, id))),
      table.get("uniqueKeys").fold(Vector.empty)(keys => array(keys, label("unique keys")).map { key =>
        val label = s"Unique key in table '$id'"
        UniqueKeyModel(ids(fields(key, label, Set("columns"))("columns"), s"$label columns"))
      }),
      table.get("indexes").fold(Vector.empty)(indexes => array(indexes, label("indexes")).map(readIndex(_, id)))
    )

  private def readIndex(json: Json, tableId: String): IndexModel =
    val label = s"Index in table '$tableId'"
    IndexModel(array(fields(json, label, Set("columns"))("columns"), s"$label columns").map { column =>
      val entry = fields(column, s"$label column", Set("id", "descending"))
      val descending = entry("descending") match
        case Json.Bool(value) => value
        case _ => invalid(s"$label column descending must be true or false")
      IndexColumn(SchemaId(string(entry("id"), s"$label column id")), descending)
    })

  private def readForeignKey(json: Json, tableId: String): ForeignKeyModel =
    val key = fields(json, s"Foreign key in table '$tableId'", Set("columns", "referencedTable", "referencedColumns"))
    val label = s"Foreign key in table '$tableId'"
    ForeignKeyModel(
      ids(key("columns"), s"$label columns"),
      SchemaId(string(key("referencedTable"), s"$label referenced table")),
      ids(key("referencedColumns"), s"$label referenced columns")
    )

  private def ids(json: Json, label: String): Vector[SchemaId] =
    array(json, label).map(id => SchemaId(string(id, label)))

  private def readColumn(json: Json, tableId: String): ColumnModel =
    val column = fields(json, s"Column in table '$tableId'", Set("id", "name", "type", "nullable"))
    val id = string(column("id"), s"Column id in table '$tableId'")
    val dataType = string(column("type"), s"Column '$id' type") match
      case "integer" => SqlType.Integer
      case "bigint" => SqlType.BigInt
      case "boolean" => SqlType.Boolean
      case "text" => SqlType.Text
      case "uuid" => SqlType.Uuid
      case "smallint" => SqlType.SmallInt
      case "real" => SqlType.Real
      case "double precision" => SqlType.DoublePrecision
      case "date" => SqlType.Date
      case "binary" => SqlType.Binary
      case CharType(length) => SqlType.Char(number(length, s"Column '$id' CHAR length"))
      case NumericType(precision, scale) =>
        SqlType.Numeric(number(precision, s"Column '$id' NUMERIC precision"), number(scale, s"Column '$id' NUMERIC scale"))
      case TimeType(precision) => SqlType.Time(number(precision, s"Column '$id' TIME precision"))
      case VarcharType(length) => SqlType.Varchar(number(length, s"Column '$id' VARCHAR length"))
      case TimestampType(precision) => SqlType.Timestamp(number(precision, s"Column '$id' TIMESTAMP precision"))
      case TimestampWithTimeZoneType(precision) =>
        SqlType.TimestampWithTimeZone(number(precision, s"Column '$id' TIMESTAMP precision"))
      case other => invalid(s"Column '$id' has unknown type '$other'")
    val nullable = column("nullable") match
      case Json.Bool(value) => value
      case _ => invalid(s"Column '$id' nullable must be true or false")
    ColumnModel(SchemaId(id), SqlIdentifier(string(column("name"), s"Column '$id' name")), dataType, nullable)

  private def number(digits: String, label: String): Int =
    digits.toIntOption.getOrElse(invalid(s"$label $digits is out of range"))

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
