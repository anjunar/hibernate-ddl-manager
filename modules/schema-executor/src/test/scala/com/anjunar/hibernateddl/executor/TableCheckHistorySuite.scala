package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*
import munit.FunSuite

class TableCheckHistorySuite extends FunSuite:
  private val table = TableModel(
    SchemaId("post"),
    QualifiedName(SqlIdentifier("post")),
    Vector(ColumnModel(SchemaId("id"), SqlIdentifier("id"), SqlType.Integer))
  )
  private val check = TableCheck(SqlIdentifier("quoted \"check\""), "id > 0 AND 'é' = 'é'")

  test("named checks round-trip with strict format 8 fields") {
    val model = SchemaModel(Vector(table.copy(checks = Vector(check))))
    val json = SchemaModelJson.encode(model)
    assertEquals(SchemaModelJson.decode(json), Right(model))
    assert(json.forall(c => c >= ' ' && c <= '~'))
    assert(SchemaModelJson.decode(json.replace("\"expression\":", "\"unknown\":")).isLeft)
  }

  test("format 6 models remain readable without table checks") {
    val model = SchemaModel(Vector(table))
    val legacy = SchemaModelJson.encode(model).replace("\"format\":8", "\"format\":6")
      .replace(",\"checks\":[]", "")
    assertEquals(SchemaModelJson.decode(legacy), Right(model))
  }

  test("check names and expressions affect fingerprints while ordering does not") {
    def fingerprint(checks: Vector[TableCheck]) = SchemaFingerprint.of(SchemaModel(Vector(table.copy(checks = checks))))
    val second = TableCheck(SqlIdentifier("another"), "id < 100")
    assertEquals(fingerprint(Vector(check, second)), fingerprint(Vector(second, check)))
    assertNotEquals(fingerprint(Vector.empty), fingerprint(Vector(check)))
    assertNotEquals(fingerprint(Vector(check)), fingerprint(Vector(check.copy(expression = "true"))))
    assertNotEquals(fingerprint(Vector(check)), fingerprint(Vector(check.copy(name = SqlIdentifier("renamed")))))
  }
