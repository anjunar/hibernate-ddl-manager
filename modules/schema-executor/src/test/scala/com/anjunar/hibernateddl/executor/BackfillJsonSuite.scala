package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*

class BackfillJsonSuite extends munit.FunSuite:
  private def rule(id: String, value: BackfillValue) =
    Backfill.fillNulls(id, SchemaId("user/display"), BackfillTrigger.BecomesRequired, value)

  test("every kind of value and constant survives a round trip exactly, with its checksum") {
    val backfills = Vector(
      rule("text", BackfillValue.coalesce(BackfillValue.concat(BackfillValue.column(SchemaId("user/first")),
        BackfillValue.literal(" \"quoted\" ä ")), BackfillValue.literal("Unknown"))),
      rule("numbers", BackfillValue.coalesce(BackfillValue.literal(Long.MinValue), BackfillValue.literal(java.math.BigDecimal("1.50")),
        BackfillValue.literal(0.1), BackfillValue.literal(true))),
      rule("time", BackfillValue.coalesce(BackfillValue.literal(java.util.UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")),
        BackfillValue.literal(java.time.LocalDate.of(2026, 9, 25)), BackfillValue.literal(java.time.LocalTime.of(8, 30, 0, 123000)),
        BackfillValue.literal(java.time.LocalDateTime.of(2026, 9, 25, 8, 30)),
        BackfillValue.literal(java.time.OffsetDateTime.parse("2026-09-25T08:30+02:00"))))
    )
    val json = BackfillJson.encode(backfills)
    assert(json.startsWith("""{"format":1,"backfills":["""), json)
    assertEquals(BackfillJson.decode(json), Right(backfills))
    assertEquals(BackfillJson.decode(json).toOption.get.map(BackfillChecksum.of), backfills.map(BackfillChecksum.of))
  }

  test("reading is strict: unknown formats, fields, types and triggers are errors") {
    Vector(
      """{"format":2,"backfills":[]}""" -> "Unsupported backfill format",
      """{"format":1,"backfills":[],"extra":1}""" -> "fields",
      """{"format":1,"backfills":[{"id":"x","target":"t","when":"sometimes","value":{"column":"c"}}]}""" -> "unknown trigger",
      """{"format":1,"backfills":[{"id":"x","target":"t","when":"becomes required","value":{"literal":{"type":"money","value":"1"}}}]}""" ->
        "unknown constant type",
      """{"format":1,"backfills":[{"id":"x","target":"t","when":"becomes required","value":{"literal":{"type":"whole number","value":"1.5"}}}]}""" ->
        "invalid number"
    ).foreach((json, message) => assert(BackfillJson.decode(json).left.exists(_.contains(message)), s"$json: ${BackfillJson.decode(json)}"))
  }
