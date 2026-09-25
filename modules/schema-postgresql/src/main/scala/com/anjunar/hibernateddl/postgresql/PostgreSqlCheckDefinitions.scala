package com.anjunar.hibernateddl.postgresql

import com.anjunar.hibernateddl.core.*

/** Compares a check constraint's definition, as `pg_get_constraintdef` shows it, with a modeled
  * column check without creating anything. It recognizes exactly the forms PostgreSQL shows for
  * the checks this dialect creates: a range as two comparisons joined by AND, allowed values as
  * `= ANY (ARRAY[...])` or, for a single value, `=`, with the casts of varchar, text or char
  * columns. A recognized definition must name the column and carry the same values in the same
  * order and with the same casts; a single comparison is recognized as a different check.
  * Anything else is undecidable, never equal.
  */
private[postgresql] object PostgreSqlCheckDefinitions:
  enum Comparison:
    case Equal
    case Different(actual: String)
    case Undecidable

  private val Name = """("(?:[^"]|"")+"|[a-z_][a-z0-9_$]*)"""
  private val Literal = """'((?:[^']|'')*)'"""
  private val Whole = """(\d+|'-?\d+'::(?:smallint|integer|bigint))"""
  private val Range = s"""CHECK \\(\\(\\($Name >= $Whole\\) AND \\($Name <= $Whole\\)\\)\\)""".r
  private val Single = s"""CHECK \\(\\($Name (?:>|>=|<|<=|=|<>) (?:$Whole)\\)\\)""".r
  private val VarcharAny = s"""CHECK \\(\\(\\($Name\\)::text = ANY \\(\\(ARRAY\\[(.*)\\]\\)::text\\[\\]\\)\\)\\)""".r
  private val VarcharOne = s"""CHECK \\(\\(\\($Name\\)::text = $Literal::text\\)\\)""".r
  private val PlainAny = s"""CHECK \\(\\($Name = ANY \\(ARRAY\\[(.*)\\]\\)\\)\\)""".r
  private val PlainOne = s"""CHECK \\(\\($Name = $Literal::(text|bpchar)\\)\\)""".r

  def compare(definition: String, column: ColumnModel, check: ColumnCheck): Comparison =
    val parsed: Option[(String, ColumnCheck)] = (column.dataType, definition) match
      case (SqlType.SmallInt | SqlType.Integer | SqlType.BigInt, Range(name, min, other, max)) if unquote(name) == unquote(other) =>
        for low <- whole(min); high <- whole(max) yield unquote(name) -> ColumnCheck.Range(low, high)
      case (SqlType.SmallInt | SqlType.Integer | SqlType.BigInt, Single(name, _)) =>
        Some(unquote(name) -> ColumnCheck.Range(Long.MinValue, Long.MinValue))
      case (SqlType.Varchar(_), VarcharAny(name, items)) => literals(items, "character varying").map(unquote(name) -> ColumnCheck.AllowedValues(_))
      case (SqlType.Varchar(_), VarcharOne(name, value)) => Some(unquote(name) -> ColumnCheck.AllowedValues(Vector(unescape(value))))
      case (SqlType.Text, PlainAny(name, items)) => literals(items, "text").map(unquote(name) -> ColumnCheck.AllowedValues(_))
      case (SqlType.Text, PlainOne(name, value, "text")) => Some(unquote(name) -> ColumnCheck.AllowedValues(Vector(unescape(value))))
      case (SqlType.Char(_), PlainAny(name, items)) => literals(items, "bpchar").map(unquote(name) -> ColumnCheck.AllowedValues(_))
      case (SqlType.Char(_), PlainOne(name, value, "bpchar")) => Some(unquote(name) -> ColumnCheck.AllowedValues(Vector(unescape(value))))
      case _ => None
    parsed match
      case None => Comparison.Undecidable
      case Some((name, actual)) if name == column.name.value && actual == check => Comparison.Equal
      case Some(_) => Comparison.Different(definition)

  private def unquote(name: String): String =
    if name.startsWith("\"") then name.drop(1).dropRight(1).replace("\"\"", "\"") else name

  private def unescape(literal: String): String = literal.replace("''", "'")

  private def whole(text: String): Option[Long] =
    if text.startsWith("'") then text.drop(1).takeWhile(_ != '\'').toLongOption else text.toLongOption

  /** The items of an ARRAY[...], each a literal cast to the given type. */
  private def literals(items: String, cast: String): Option[Vector[String]] =
    val Item = s"""$Literal::$cast(?:, |$$)""".r
    val found = Item.findAllMatchIn(items).toVector
    Option.when(found.nonEmpty && found.map(_.matched).mkString == items)(found.map(m => unescape(m.group(1))))
