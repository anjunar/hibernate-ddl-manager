package io.github.hibernateddl.executor

import io.github.hibernateddl.core.*

class SchemaModelJsonSuite extends munit.FunSuite:
  private val id = ColumnModel(SchemaId("7f3a9c21/0a1b2c3d"), SqlIdentifier("id"), SqlType.BigInt, nullable = false)
  private val customer = TableModel(
    SchemaId("7f3a9c21"),
    QualifiedName(SqlIdentifier("customer"), Some(SqlIdentifier("public"))),
    Vector(
      id,
      ColumnModel(SchemaId("7f3a9c21/f34e45b6"), SqlIdentifier("nick_name"), SqlType.Varchar(80)),
      ColumnModel(SchemaId("7f3a9c21/1c2d3e4f"), SqlIdentifier("visits"), SqlType.Integer),
      ColumnModel(SchemaId("7f3a9c21/2d3e4f5a"), SqlIdentifier("active"), SqlType.Boolean, nullable = false),
      ColumnModel(SchemaId("7f3a9c21/3e4f5a6b"), SqlIdentifier("notes"), SqlType.Text)
    ),
    Vector(id.id)
  )
  private val model = SchemaModel(Vector(customer))

  private def failure(json: String): String =
    SchemaModelJson.decode(json).swap.getOrElse(fail(s"Expected a decoding error for $json"))

  test("encoding is compact, readable and round-trips every type") {
    val json = SchemaModelJson.encode(model)
    assert(json.startsWith("""{"format":1,"tables":[{"id":"7f3a9c21","catalog":null,"schema":"public","name":"customer","""), json)
    assert(json.contains(""""type":"varchar(80)","nullable":true"""), json)
    assert(json.endsWith(""""primaryKey":["7f3a9c21/0a1b2c3d"]}]}"""), json)
    assertEquals(SchemaModelJson.decode(json), Right(model))
    assertEquals(SchemaModelJson.decode(SchemaModelJson.encode(SchemaModel(Vector.empty))), Right(SchemaModel(Vector.empty)))
  }

  test("quotes, backslashes, control and non-ASCII characters are escaped and survive a round trip") {
    val odd = customer.copy(
      name = QualifiedName(SqlIdentifier("a\"b\\c"), Some(SqlIdentifier("schéma")), Some(SqlIdentifier("tab\there"))),
      columns = Vector(id.copy(name = SqlIdentifier("line\nbreak 🙂 \u007f")))
    )
    val json = SchemaModelJson.encode(SchemaModel(Vector(odd)))
    assert(json.forall(c => c >= ' ' && c <= '~'), json)
    assert(json.contains("\"a\\\"b\\\\c\""), json)
    assertEquals(SchemaModelJson.decode(json), Right(SchemaModel(Vector(odd))))
  }

  test("the text PostgreSQL returns for jsonb, with other key order and whitespace, decodes to the same model") {
    val jsonb =
      """{"format": 1, "tables": [{"id": "7f3a9c21", "name": "customer", "schema": "public", "catalog": null,
        |  "columns": [{"id": "7f3a9c21/0a1b2c3d", "name": "id", "type": "bigint", "nullable": false},
        |  {"id": "7f3a9c21/f34e45b6", "name": "nick_name", "type": "varchar(80)", "nullable": true}],
        |  "primaryKey": ["7f3a9c21/0a1b2c3d"]}]}""".stripMargin
    assertEquals(SchemaModelJson.decode(jsonb), Right(SchemaModel(Vector(customer.copy(columns = customer.columns.take(2))))))
  }

  test("other format versions, missing or unknown fields and unknown types are rejected") {
    val json = SchemaModelJson.encode(model)
    assert(failure(json.replace("\"format\":1", "\"format\":2")).contains("format 2 is unsupported"))
    assert(failure("""{"tables":[]}""").contains("format is missing"))
    assert(failure(json.replace("\"catalog\":null,", "")).contains("expected catalog"))
    assert(failure(json.replace("\"catalog\":null,", "\"catalog\":null,\"comment\":\"x\",")).contains("comment"))
    assert(failure(json.replace("\"bigint\"", "\"uuid\"")).contains("unknown type 'uuid'"))
    assert(failure(json.replace("varchar(80)", "varchar(99999999999)")).contains("VARCHAR length"))
    assert(failure(json.replace("\"nullable\":false", "\"nullable\":0")).contains("true or false"))
    assert(failure(json.replace("\"name\":\"customer\"", "\"name\":\"  \"")).contains("must not be blank"))
    assert(failure(json.replace("\"schema\":\"public\"", "\"schema\":[]")).contains("must be a string"))
    assert(failure("[]").contains("must be an object"))
  }

  test("malformed JSON is rejected with its offset") {
    val json = SchemaModelJson.encode(model)
    Vector(
      "", json + " x", json.dropRight(1), """{"format":1,"format":1,"tables":[]}""", """{"format":1.0,"tables":[]}""",
      """{"format":01,"tables":[]}""", """{"format":1,"tables":[],}""", """{"format":1,"tables":["\q"]}""",
      """{"format":1,"tables":["\u12"]}""", "{\"format\":1,\"tables\":[\"a\nb\"]}", """{"format":1,"tables":[tru]}"""
    ).foreach { malformed =>
      assert(failure(malformed).startsWith("Invalid JSON at offset"), malformed)
    }
  }
