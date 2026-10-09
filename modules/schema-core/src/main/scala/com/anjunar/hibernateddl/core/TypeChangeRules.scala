package com.anjunar.hibernateddl.core

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
