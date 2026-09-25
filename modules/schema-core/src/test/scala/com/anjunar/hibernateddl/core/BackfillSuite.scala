package com.anjunar.hibernateddl.core

class BackfillSuite extends munit.FunSuite:
  private val first = ColumnModel(SchemaId("user/first"), SqlIdentifier("first_name"), SqlType.Varchar(100))
  private val last = ColumnModel(SchemaId("user/last"), SqlIdentifier("last_name"), SqlType.Text)
  private val age = ColumnModel(SchemaId("user/age"), SqlIdentifier("age"), SqlType.SmallInt)
  private val display = ColumnModel(SchemaId("user/display"), SqlIdentifier("display_name"), SqlType.Varchar(255), false)
  private val table = QualifiedName(SqlIdentifier("users"), Some(SqlIdentifier("public")))
  private val columns = Vector(first, last, age, display)

  private def rule(value: BackfillValue, target: SchemaId = display.id, id: String = "display-v1") =
    Backfill.fillNulls(id, target, BackfillTrigger.BecomesRequired, value)

  private def resolve(value: BackfillValue, target: ColumnModel = display, filled: Set[SchemaId] = Set.empty) =
    BackfillValidation.resolve(rule(value, target.id), table, target, columns, filled)

  private def refused(value: BackfillValue, target: ColumnModel = display, filled: Set[SchemaId] = Set.empty): String =
    resolve(value, target, filled).fold(_.mkString("; "), fill => fail(s"expected a refusal, got $fill"))

  test("static validation finds blank and duplicate IDs, empty lists, NUL, non-finite numbers and self-references") {
    val errors = BackfillValidation.validate(Vector(
      rule(BackfillValue.literal("x"), id = " "),
      rule(BackfillValue.literal("x"), id = "twice"), rule(BackfillValue.literal("y"), id = "twice"),
      rule(BackfillValue.coalesce(), id = "empty"), rule(BackfillValue.concat(), id = "joined"),
      rule(BackfillValue.literal("a\u0000b"), id = "nul"), rule(BackfillValue.literal(Double.NaN), id = "nan"),
      rule(BackfillValue.coalesce(BackfillValue.column(display.id)), id = "self")
    ))
    Vector("A backfill ID must not be blank", "Backfill ID 'twice' is registered 2 times", "Backfill 'empty' has an empty coalesce",
      "Backfill 'joined' has an empty concat", "Backfill 'nul' has a text constant containing NUL",
      "Backfill 'nan' has the constant NaN, which is not a finite number", "Backfill 'self' reads its own target column"
    ).foreach(message => assert(errors.contains(message), s"$message in $errors"))
    assertEquals(BackfillValidation.validate(Vector(rule(BackfillValue.literal("Unknown")))), Vector.empty)
  }

  test("constants, columns, coalesce and concat resolve to physical names and typed constants") {
    val value = BackfillValue.coalesce(
      BackfillValue.concat(BackfillValue.column(first.id), BackfillValue.literal(" "), BackfillValue.column(last.id)),
      BackfillValue.column(first.id),
      BackfillValue.literal("Unknown"))
    assertEquals(resolve(value), Right(NullFill("display-v1", table, display.name, FillValue.Coalesce(Vector(
      FillValue.Concat(Vector(FillValue.Column(first.name), FillValue.Literal(BackfillLiteral.Text(" "), SqlType.Text),
        FillValue.Column(last.name))),
      FillValue.Column(first.name),
      FillValue.Literal(BackfillLiteral.Text("Unknown"), display.dataType))))))
  }

  test("constants must fit the column exactly: length, range, scale, precision and kind") {
    def column(dataType: SqlType) = display.copy(dataType = dataType)
    Vector(
      BackfillValue.literal("x" * 256) -> column(SqlType.Varchar(255)),
      BackfillValue.literal(40000L) -> column(SqlType.SmallInt),
      BackfillValue.literal(java.math.BigDecimal("1.234")) -> column(SqlType.Numeric(10, 2)),
      BackfillValue.literal(java.math.BigDecimal("123456789.5")) -> column(SqlType.Numeric(10, 2)),
      BackfillValue.literal(0.1) -> column(SqlType.Real),
      BackfillValue.literal(java.time.LocalTime.of(8, 30, 0, 123456789)) -> column(SqlType.Time(6)),
      BackfillValue.literal(1L) -> column(SqlType.Text),
      BackfillValue.literal("true") -> column(SqlType.Boolean)
    ).foreach { (value, target) =>
      assert(refused(value, target).contains("without conversion or rounding"), s"$value into ${target.dataType}")
    }
    Vector(
      BackfillValue.literal("x" * 255) -> column(SqlType.Varchar(255)),
      BackfillValue.literal(32767L) -> column(SqlType.SmallInt),
      BackfillValue.literal(java.math.BigDecimal("12345678.50")) -> column(SqlType.Numeric(10, 2)),
      BackfillValue.literal(12L) -> column(SqlType.Numeric(4, 2)),
      BackfillValue.literal(0.5) -> column(SqlType.Real),
      BackfillValue.literal(java.time.LocalTime.of(8, 30, 0, 123456000)) -> column(SqlType.Time(6)),
      BackfillValue.literal(java.time.LocalDate.of(2026, 9, 25)) -> column(SqlType.Date),
      BackfillValue.literal(java.util.UUID.randomUUID()) -> column(SqlType.Uuid),
      BackfillValue.literal(true) -> column(SqlType.Boolean)
    ).foreach((value, target) => assert(resolve(value, target).isRight, s"$value into ${target.dataType}"))
  }

  test("sources must be text or of the target's type, of the same table, not filled by another backfill, not a large object") {
    assert(refused(BackfillValue.column(age.id)).contains("reads column 'user/age' of type SmallInt, which cannot fill Varchar(255)"))
    assert(refused(BackfillValue.column(SchemaId("order/total"))).contains("which is no column of the target's table"))
    assert(refused(BackfillValue.column(first.id), filled = Set(first.id)).contains("chained backfills are unsupported"))
    assert(refused(BackfillValue.concat(BackfillValue.column(age.id))).contains("joins column 'user/age' of type SmallInt"))
    assert(refused(BackfillValue.concat(BackfillValue.literal(1L))).contains("joins the non-text constant"))
    assert(refused(BackfillValue.concat(BackfillValue.literal("x")), target = age).contains("joins text, which cannot fill SmallInt"))
    val lob = display.copy(dataType = SqlType.LargeObject)
    assert(refused(BackfillValue.literal("x"), target = lob).contains("fills a large object column"))
  }

  test("the checksum covers target, trigger, structure and typed constants, but not the ID") {
    val base = rule(BackfillValue.coalesce(BackfillValue.column(first.id), BackfillValue.literal("Unknown")))
    val variants = Vector(
      base.copy(target = last.id),
      base.copy(value = BackfillValue.coalesce(BackfillValue.column(last.id), BackfillValue.literal("Unknown"))),
      base.copy(value = BackfillValue.concat(BackfillValue.column(first.id), BackfillValue.literal("Unknown"))),
      base.copy(value = BackfillValue.coalesce(BackfillValue.column(first.id), BackfillValue.literal("unknown"))),
      rule(BackfillValue.literal(1L)), rule(BackfillValue.literal(java.math.BigDecimal("1"))),
      rule(BackfillValue.literal(java.math.BigDecimal("1.0"))), rule(BackfillValue.literal(1.0)), rule(BackfillValue.literal("1"))
    )
    val checksums = (base +: variants).map(BackfillChecksum.of)
    assertEquals(checksums.distinct.size, checksums.size)
    assertEquals(BackfillChecksum.of(base.copy(id = "renamed")), BackfillChecksum.of(base))
    assert(checksums.forall(_.matches("[0-9a-f]{64}")))
  }
