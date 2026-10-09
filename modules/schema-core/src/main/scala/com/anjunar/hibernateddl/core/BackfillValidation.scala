package com.anjunar.hibernateddl.core

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
