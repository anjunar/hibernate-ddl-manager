package com.anjunar.hibernateddl.postgresql

import com.anjunar.hibernateddl.core.*
import PostgreSqlCheckDefinitions.{Comparison, compare}

class PostgreSqlCheckDefinitionsSuite extends munit.FunSuite:
  private def column(name: String, dataType: SqlType) = ColumnModel(SchemaId("x"), SqlIdentifier(name), dataType)
  private val values = ColumnCheck.AllowedValues(Vector("A", "it's"))

  test("the forms PostgreSQL shows for this dialect's checks compare equal") {
    Vector(
      ("CHECK (((v)::text = ANY ((ARRAY['A'::character varying, 'it''s'::character varying])::text[])))",
        column("v", SqlType.Varchar(10)), values),
      ("CHECK (((\"Stage\")::text = 'A'::text))", column("Stage", SqlType.Varchar(10)), ColumnCheck.AllowedValues(Vector("A"))),
      ("CHECK ((t = ANY (ARRAY['A'::text, 'it''s'::text])))", column("t", SqlType.Text), values),
      ("CHECK ((t1 = 'A'::text))", column("t1", SqlType.Text), ColumnCheck.AllowedValues(Vector("A"))),
      ("CHECK ((c = ANY (ARRAY['A'::bpchar, 'it''s'::bpchar])))", column("c", SqlType.Char(4)), values),
      ("CHECK (((s >= '-128'::integer) AND (s <= 127)))", column("s", SqlType.SmallInt), ColumnCheck.Range(-128, 127)),
      ("CHECK (((\"position\" >= 0) AND (\"position\" <= 2147483647)))", column("position", SqlType.Integer),
        ColumnCheck.Range(0, Int.MaxValue)),
      ("CHECK (((b >= '-9223372036854775808'::bigint) AND (b <= '9223372036854775807'::bigint)))", column("b", SqlType.BigInt),
        ColumnCheck.Range(Long.MinValue, Long.MaxValue))
    ).foreach((definition, target, check) => assertEquals(compare(definition, target, check), Comparison.Equal, definition))
  }

  test("other values, order, column or a single comparison are different; the name alone never passes") {
    Vector(
      ("CHECK ((t = ANY (ARRAY['A'::text, 'B'::text])))", column("t", SqlType.Text), values),
      ("CHECK ((t = ANY (ARRAY['it''s'::text, 'A'::text])))", column("t", SqlType.Text), values),
      ("CHECK ((u = ANY (ARRAY['A'::text, 'it''s'::text])))", column("t", SqlType.Text), values),
      ("CHECK (((s >= 0) AND (s <= 127)))", column("s", SqlType.SmallInt), ColumnCheck.Range(-128, 127)),
      ("CHECK ((score > '-100'::integer))", column("score", SqlType.Integer), ColumnCheck.Range(1, 10)),
      ("CHECK ((\"position\" >= 0))", column("position", SqlType.Integer), ColumnCheck.Range(0, Int.MaxValue))
    ).foreach((definition, target, check) => assertEquals(compare(definition, target, check), Comparison.Different(definition), definition))
  }

  test("forms it does not know are undecidable") {
    Vector(
      ("CHECK ((score > 0) OR (score IS NULL))", column("score", SqlType.Integer), ColumnCheck.Range(1, 10)),
      ("CHECK (((v)::text ~ '^[A-Z]$'::text))", column("v", SqlType.Varchar(10)), values),
      ("CHECK ((t = ANY (ARRAY['A'::text, lower('B'::text)])))", column("t", SqlType.Text), values),
      ("CHECK ((t = ANY (ARRAY['A'::text, 'B'::text])))", column("t", SqlType.Varchar(10)), values)
    ).foreach((definition, target, check) => assertEquals(compare(definition, target, check), Comparison.Undecidable, definition))
  }
