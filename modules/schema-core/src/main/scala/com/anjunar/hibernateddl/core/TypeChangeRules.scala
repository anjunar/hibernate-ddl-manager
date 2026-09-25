package com.anjunar.hibernateddl.core

/** How much work a supported type change may cause. Every such change keeps all values; this
  * only describes what the database may rebuild while it holds its locks.
  */
enum TableRewrite:
  /** The stored format stays the same, so the database may skip rewriting the table and its
    * indexes; it is not promised.
    */
  case Possible
  /** The stored format changes, so the table and its indexes are rewritten. */
  case Expected

/** Why a type change is refused. */
enum TypeChangeRejection:
  /** A shorter VARCHAR, a smaller NUMERIC precision or BIGINT to INTEGER. */
  case Narrowing
  /** NUMERIC with another number of digits after the decimal point. */
  case ScaleChange
  /** Any other pair of types, which would need a conversion rule. */
  case OtherTypes

enum TypeChange:
  case Unchanged
  /** `rule` says why the change is allowed. */
  case Widening(rule: String, rewrite: TableRewrite)
  /** `reason` completes "The change ...". */
  case Unsupported(rejection: TypeChangeRejection, reason: String)

/** The closed list of type changes a migration performs on an existing column: a longer VARCHAR,
  * INTEGER to BIGINT, and a larger NUMERIC precision with the same scale. Each keeps every value
  * as it is, so none needs a conversion rule or a check of the data. Nothing else is inferred:
  * that the database could cast between two types is no reason to allow a change.
  */
object TypeChangeRules:
  def classify(from: SqlType, to: SqlType): TypeChange = (from, to) match
    case _ if from == to => TypeChange.Unchanged
    case (SqlType.Varchar(before), SqlType.Varchar(after)) =>
      if after > before then TypeChange.Widening(s"VARCHAR grows from $before to $after characters", TableRewrite.Possible)
      else TypeChange.Unsupported(TypeChangeRejection.Narrowing, s"shortens VARCHAR from $before to $after characters")
    case (SqlType.Integer, SqlType.BigInt) => TypeChange.Widening("INTEGER grows to BIGINT", TableRewrite.Expected)
    case (SqlType.BigInt, SqlType.Integer) => TypeChange.Unsupported(TypeChangeRejection.Narrowing, "narrows BIGINT to INTEGER")
    case (SqlType.Numeric(precision, scale), SqlType.Numeric(newPrecision, newScale)) =>
      if newScale != scale then TypeChange.Unsupported(TypeChangeRejection.ScaleChange,
        s"changes the NUMERIC scale from $scale to $newScale; only the precision may grow")
      else if newPrecision > precision then
        TypeChange.Widening(s"NUMERIC precision grows from $precision to $newPrecision with scale $scale", TableRewrite.Possible)
      else TypeChange.Unsupported(TypeChangeRejection.Narrowing,
        s"reduces the NUMERIC precision from $precision to $newPrecision")
    case _ => TypeChange.Unsupported(TypeChangeRejection.OtherTypes,
      "is not a supported widening; only a longer VARCHAR, INTEGER to BIGINT and a larger NUMERIC precision " +
        "with the same scale are")
