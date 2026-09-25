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

enum BackfillTrigger:
  /** The column is added to an existing table as a required column, or a nullable column
    * becomes required.
    */
  case BecomesRequired

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
  def literal(value: java.math.BigDecimal): BackfillValue = Literal(BackfillLiteral.Decimal(value))
  def literal(value: Double): BackfillValue = Literal(BackfillLiteral.FloatingPoint(value))
  def literal(value: Boolean): BackfillValue = Literal(BackfillLiteral.Bool(value))
  def literal(value: java.util.UUID): BackfillValue = Literal(BackfillLiteral.Uuid(value))
  def literal(value: java.time.LocalDate): BackfillValue = Literal(BackfillLiteral.Date(value))
  def literal(value: java.time.LocalTime): BackfillValue = Literal(BackfillLiteral.Time(value))
  def literal(value: java.time.LocalDateTime): BackfillValue = Literal(BackfillLiteral.Timestamp(value))
  def literal(value: java.time.OffsetDateTime): BackfillValue = Literal(BackfillLiteral.TimestampWithTimeZone(value))
  def column(id: SchemaId): BackfillValue = Column(id)
  def coalesce(values: BackfillValue*): BackfillValue = Coalesce(values.toVector)
  def concat(values: BackfillValue*): BackfillValue = Concat(values.toVector)

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

/** A backfill resolved against the schema at the moment it runs: physical names, and the type
  * each constant takes.
  */
final case class NullFill(backfillId: String, table: QualifiedName, column: SqlIdentifier, value: FillValue)

enum FillValue:
  case Literal(value: BackfillLiteral, as: SqlType)
  case Column(name: SqlIdentifier)
  /** A column that does not exist yet and is NULL when the backfill runs. */
  case Null(as: SqlType)
  case Coalesce(values: Vector[FillValue])
  case Concat(values: Vector[FillValue])

object BackfillValidation:
  /** Checks what does not depend on a schema: IDs, duplicates and well-formed values. */
  def validate(backfills: Vector[Backfill]): Vector[String] =
    val duplicates = backfills.groupBy(_.id).collect { case (id, rules) if rules.size > 1 =>
      s"Backfill ID '$id' is registered ${rules.size} times"
    }
    (duplicates.toVector ++ backfills.flatMap { backfill =>
      val label = s"Backfill '${backfill.id}'"
      Vector(
        Option.when(backfill.id.trim.isEmpty)("A backfill ID must not be blank"),
        Option.when(backfill.id.exists(_.isControl))(s"$label has a control character in its ID")
      ).flatten ++ wellFormed(label, backfill.target, backfill.value)
    }).distinct.sorted

  private def wellFormed(label: String, target: SchemaId, value: BackfillValue): Vector[String] = value match
    case BackfillValue.Literal(BackfillLiteral.Text(text)) if text.contains('\u0000') =>
      Vector(s"$label has a text constant containing NUL")
    case BackfillValue.Literal(BackfillLiteral.FloatingPoint(number)) if number.isNaN || number.isInfinite =>
      Vector(s"$label has the constant $number, which is not a finite number")
    case BackfillValue.Literal(_) => Vector.empty
    case BackfillValue.Column(id) if id == target => Vector(s"$label reads its own target column")
    case BackfillValue.Column(_) => Vector.empty
    case BackfillValue.Coalesce(values) =>
      Option.when(values.isEmpty)(s"$label has an empty coalesce").toVector ++ values.flatMap(wellFormed(label, target, _))
    case BackfillValue.Concat(values) =>
      Option.when(values.isEmpty)(s"$label has an empty concat").toVector ++ values.flatMap(wellFormed(label, target, _))

  /** Resolves a backfill for its target column. `columns` are the table's columns when the
    * backfill runs: the target's columns plus previous ones that are dropped only afterwards.
    * `filled` are the columns that other backfills of the same plan fill, which a backfill
    * must not read. `absent` are columns that do not exist yet where the value is evaluated; they
    * resolve to NULL.
    */
  def resolve(
      backfill: Backfill,
      table: QualifiedName,
      target: ColumnModel,
      columns: Vector[ColumnModel],
      filled: Set[SchemaId],
      absent: Set[SchemaId] = Set.empty
  ): Either[Vector[String], NullFill] =
    val label = s"Backfill '${backfill.id}' for column '${target.id.value}'"
    def source(id: SchemaId): Either[Vector[String], ColumnModel] =
      columns.find(_.id == id) match
        case None => Left(Vector(s"$label reads column '${id.value}', which is no column of the target's table"))
        case Some(_) if filled.contains(id) =>
          Left(Vector(s"$label reads column '${id.value}', which another backfill fills; chained backfills are unsupported"))
        case Some(column) if column.dataType == SqlType.LargeObject =>
          Left(Vector(s"$label reads the large object column '${id.value}'; rows must not share large objects"))
        case Some(column) => Right(column)
    def reference(column: ColumnModel) =
      if absent.contains(column.id) then FillValue.Null(column.dataType) else FillValue.Column(column.name)
    def text(dataType: SqlType) = dataType match
      case SqlType.Varchar(_) | SqlType.Text => true
      case _ => false
    def typed(value: BackfillValue, expected: SqlType): Either[Vector[String], FillValue] = value match
      case BackfillValue.Literal(literal) =>
        fits(literal, expected).map(problem => Vector(s"$label: $problem")).toLeft(FillValue.Literal(literal, expected))
      case BackfillValue.Column(id) =>
        source(id).flatMap { column =>
          if column.dataType == expected || text(column.dataType) && text(expected) then Right(reference(column))
          else Left(Vector(s"$label reads column '${id.value}' of type ${column.dataType}, which cannot fill $expected " +
            "without a conversion"))
        }
      case BackfillValue.Coalesce(values) => all(values.map(typed(_, expected))).map(FillValue.Coalesce(_))
      case BackfillValue.Concat(values) =>
        if !text(expected) then Left(Vector(s"$label joins text, which cannot fill $expected"))
        else all(values.map(textual)).map(FillValue.Concat(_))
    def textual(value: BackfillValue): Either[Vector[String], FillValue] = value match
      case BackfillValue.Literal(literal: BackfillLiteral.Text) => Right(FillValue.Literal(literal, SqlType.Text))
      case BackfillValue.Literal(literal) => Left(Vector(s"$label joins the non-text constant ${show(literal)}"))
      case BackfillValue.Column(id) =>
        source(id).flatMap { column =>
          if text(column.dataType) then Right(reference(column))
          else Left(Vector(s"$label joins column '${id.value}' of type ${column.dataType}, which is not text"))
        }
      case BackfillValue.Coalesce(values) => all(values.map(textual)).map(FillValue.Coalesce(_))
      case BackfillValue.Concat(values) => all(values.map(textual)).map(FillValue.Concat(_))
    if target.dataType == SqlType.LargeObject then
      Left(Vector(s"$label fills a large object column; rows must not share large objects"))
    else typed(backfill.value, target.dataType).map(NullFill(backfill.id, table, target.name, _))

  private def all(results: Vector[Either[Vector[String], FillValue]]): Either[Vector[String], Vector[FillValue]] =
    val errors = results.flatMap(_.left.toOption).flatten
    if errors.nonEmpty then Left(errors) else Right(results.flatMap(_.toOption))

  /** Why a constant cannot fill a column of this type without conversion or rounding. */
  private def fits(literal: BackfillLiteral, dataType: SqlType): Option[String] =
    def fractionDigits(nanos: Int) = java.math.BigDecimal.valueOf(nanos.toLong, 9).stripTrailingZeros.scale.max(0)
    val fits = (literal, dataType) match
      case (BackfillLiteral.Text(value), SqlType.Varchar(length)) => value.codePointCount(0, value.length) <= length
      case (BackfillLiteral.Text(value), SqlType.Char(length)) => value.codePointCount(0, value.length) <= length
      case (BackfillLiteral.Text(_), SqlType.Text) => true
      case (BackfillLiteral.WholeNumber(value), SqlType.SmallInt) => value >= Short.MinValue && value <= Short.MaxValue
      case (BackfillLiteral.WholeNumber(value), SqlType.Integer) => value >= Int.MinValue && value <= Int.MaxValue
      case (BackfillLiteral.WholeNumber(_), SqlType.BigInt) => true
      case (BackfillLiteral.WholeNumber(value), SqlType.Numeric(precision, scale)) =>
        java.math.BigDecimal.valueOf(value).abs.precision <= precision - scale || value == 0
      case (BackfillLiteral.Decimal(value), SqlType.Numeric(precision, scale)) =>
        value.scale <= scale && (value.precision - value.scale <= precision - scale || value.signum == 0)
      case (BackfillLiteral.FloatingPoint(_), SqlType.DoublePrecision) => true
      case (BackfillLiteral.FloatingPoint(value), SqlType.Real) => value.toFloat.toDouble == value
      case (BackfillLiteral.Bool(_), SqlType.Boolean) => true
      case (BackfillLiteral.Uuid(_), SqlType.Uuid) => true
      case (BackfillLiteral.Date(_), SqlType.Date) => true
      case (BackfillLiteral.Time(value), SqlType.Time(precision)) => fractionDigits(value.getNano) <= precision
      case (BackfillLiteral.Timestamp(value), SqlType.Timestamp(precision)) => fractionDigits(value.getNano) <= precision
      case (BackfillLiteral.TimestampWithTimeZone(value), SqlType.TimestampWithTimeZone(precision)) =>
        fractionDigits(value.getNano) <= precision
      case _ => false
    Option.when(!fits)(s"the constant ${show(literal)} cannot fill a column of type $dataType without conversion or rounding")

  private def show(literal: BackfillLiteral): String = literal match
    case BackfillLiteral.Text(value) => "'" + value.replace("'", "''") + "'"
    case other => other.toString

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
          case BackfillLiteral.Text(text) => string("text"); string(text)
          case BackfillLiteral.WholeNumber(number) => string("whole number"); out.writeLong(number)
          case BackfillLiteral.Decimal(number) => string("decimal"); string(number.toString)
          case BackfillLiteral.FloatingPoint(number) => string("floating point"); out.writeLong(java.lang.Double.doubleToLongBits(number))
          case BackfillLiteral.Bool(flag) => string("boolean"); out.writeBoolean(flag)
          case BackfillLiteral.Uuid(uuid) => string("uuid"); string(uuid.toString)
          case BackfillLiteral.Date(date) => string("date"); string(date.toString)
          case BackfillLiteral.Time(time) => string("time"); string(time.toString)
          case BackfillLiteral.Timestamp(timestamp) => string("timestamp"); string(timestamp.toString)
          case BackfillLiteral.TimestampWithTimeZone(timestamp) => string("timestamp with time zone"); string(timestamp.toString)
      case BackfillValue.Column(id) => string("column"); string(id.value)
      case BackfillValue.Coalesce(values) => string("coalesce"); out.writeInt(values.size); values.foreach(value)
      case BackfillValue.Concat(values) => string("concat"); out.writeInt(values.size); values.foreach(value)
    string(s"hibernate-ddl-backfill-v$Format")
    string(backfill.target.value)
    string(backfill.when match { case BackfillTrigger.BecomesRequired => "becomes required" })
    value(backfill.value)
    out.flush()
    MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray).map(byte => f"${byte & 0xff}%02x").mkString
