package com.anjunar.hibernateddl.core

class TypeChangeRulesSuite extends munit.FunSuite:
  import TypeChangeRules.classify

  private def rejection(from: SqlType, to: SqlType): TypeChangeRejection = classify(from, to) match
    case TypeChange.Unsupported(rejection, _) => rejection
    case other => fail(s"Expected $from to $to to be refused, got $other")

  test("exactly three widenings are allowed, with separate rewrite expectations") {
    assertEquals(classify(SqlType.Varchar(100), SqlType.Varchar(255)),
      TypeChange.Widening("VARCHAR grows from 100 to 255 characters", TableRewrite.Possible))
    assertEquals(classify(SqlType.Integer, SqlType.BigInt), TypeChange.Widening("INTEGER grows to BIGINT", TableRewrite.Expected))
    assertEquals(classify(SqlType.Numeric(10, 2), SqlType.Numeric(14, 2)),
      TypeChange.Widening("NUMERIC precision grows from 10 to 14 with scale 2", TableRewrite.Possible))
  }

  test("identical types with identical parameters need no change") {
    Vector(SqlType.Varchar(100), SqlType.Integer, SqlType.BigInt, SqlType.Numeric(10, 2), SqlType.Text).foreach { same =>
      assertEquals(classify(same, same), TypeChange.Unchanged)
    }
  }

  test("narrowing is refused even where current values might fit") {
    assertEquals(rejection(SqlType.Varchar(255), SqlType.Varchar(100)), TypeChangeRejection.Narrowing)
    assertEquals(rejection(SqlType.BigInt, SqlType.Integer), TypeChangeRejection.Narrowing)
    assertEquals(rejection(SqlType.Numeric(14, 2), SqlType.Numeric(10, 2)), TypeChangeRejection.Narrowing)
  }

  test("a changed NUMERIC scale is refused, also together with a larger precision") {
    assertEquals(rejection(SqlType.Numeric(10, 2), SqlType.Numeric(10, 4)), TypeChangeRejection.ScaleChange)
    assertEquals(rejection(SqlType.Numeric(10, 2), SqlType.Numeric(14, 4)), TypeChangeRejection.ScaleChange)
    assertEquals(rejection(SqlType.Numeric(10, 4), SqlType.Numeric(14, 2)), TypeChangeRejection.ScaleChange)
  }

  test("every other pair of types is refused, although the database could cast some of them") {
    Vector(
      SqlType.Varchar(10) -> SqlType.Text, SqlType.Text -> SqlType.Varchar(10), SqlType.Varchar(36) -> SqlType.Uuid,
      SqlType.Varchar(10) -> SqlType.Date, SqlType.SmallInt -> SqlType.Integer, SqlType.Integer -> SqlType.Numeric(12, 0),
      SqlType.Char(5) -> SqlType.Varchar(10), SqlType.Timestamp(3) -> SqlType.Timestamp(6),
      SqlType.Timestamp(6) -> SqlType.TimestampWithTimeZone(6), SqlType.Real -> SqlType.DoublePrecision
    ).foreach { (from, to) =>
      assertEquals(rejection(from, to), TypeChangeRejection.OtherTypes, s"$from to $to")
    }
  }
