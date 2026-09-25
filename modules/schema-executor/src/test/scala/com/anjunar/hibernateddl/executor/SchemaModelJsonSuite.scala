package com.anjunar.hibernateddl.executor

import com.anjunar.hibernateddl.core.*

class SchemaModelJsonSuite extends munit.FunSuite:
  private val id = ColumnModel(SchemaId("7f3a9c21/0a1b2c3d"), SqlIdentifier("id"), SqlType.Uuid, nullable = false)
  private val customer = TableModel(
    SchemaId("7f3a9c21"),
    QualifiedName(SqlIdentifier("customer"), Some(SqlIdentifier("public"))),
    Vector(
      id,
      ColumnModel(SchemaId("7f3a9c21/f34e45b6"), SqlIdentifier("nick_name"), SqlType.Varchar(80)),
      ColumnModel(SchemaId("7f3a9c21/1c2d3e4f"), SqlIdentifier("visits"), SqlType.Integer),
      ColumnModel(SchemaId("7f3a9c21/2d3e4f5a"), SqlIdentifier("active"), SqlType.Boolean, nullable = false),
      ColumnModel(SchemaId("7f3a9c21/3e4f5a6b"), SqlIdentifier("notes"), SqlType.Text),
      ColumnModel(SchemaId("7f3a9c21/4f5a6b7c"), SqlIdentifier("points"), SqlType.BigInt),
      ColumnModel(SchemaId("7f3a9c21/5a6b7c8d"), SqlIdentifier("created_at"), SqlType.Timestamp(6), nullable = false),
      ColumnModel(SchemaId("7f3a9c21/6b7c8d9e"), SqlIdentifier("paid_at"), SqlType.TimestampWithTimeZone(3))
    ),
    Vector(id.id)
  )
  private val model = SchemaModel(Vector(customer))

  private def failure(json: String): String =
    SchemaModelJson.decode(json).swap.getOrElse(fail(s"Expected a decoding error for $json"))

  test("encoding is compact, readable and round-trips every type") {
    val json = SchemaModelJson.encode(model)
    assert(json.startsWith("""{"format":6,"tables":[{"id":"7f3a9c21","catalog":null,"schema":"public","name":"customer","""), json)
    assert(json.contains(""""type":"varchar(80)","nullable":true,"check":null,"identity":false"""), json)
    assert(json.contains(""""type":"timestamp(6)","nullable":false,"check":null,"identity":false"""), json)
    assert(json.contains(""""type":"timestamp(3) with time zone","nullable":true,"check":null,"identity":false"""), json)
    assert(json.contains(""""type":"uuid""""), json)
    assert(json.endsWith(""""primaryKey":["7f3a9c21/0a1b2c3d"],"foreignKeys":[],"uniqueKeys":[],"indexes":[]}],"sequences":[]}"""), json)
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
        |  "columns": [{"id": "7f3a9c21/0a1b2c3d", "name": "id", "type": "uuid", "nullable": false},
        |  {"id": "7f3a9c21/f34e45b6", "name": "nick_name", "type": "varchar(80)", "nullable": true}],
        |  "primaryKey": ["7f3a9c21/0a1b2c3d"]}]}""".stripMargin
    assertEquals(SchemaModelJson.decode(jsonb), Right(SchemaModel(Vector(customer.copy(columns = customer.columns.take(2))))))
  }

  test("foreign keys round-trip, including a self-reference") {
    val parent = ColumnModel(SchemaId("7f3a9c21/7c8d9e0f"), SqlIdentifier("parent_id"), SqlType.Uuid)
    val linked = customer.copy(columns = customer.columns :+ parent,
      foreignKeys = Vector(ForeignKeyModel(Vector(parent.id), customer.id, customer.primaryKey)))
    val json = SchemaModelJson.encode(SchemaModel(Vector(linked)))
    assert(json.contains(""""foreignKeys":[{"columns":["7f3a9c21/7c8d9e0f"],"referencedTable":"7f3a9c21",""" +
      """"referencedColumns":["7f3a9c21/0a1b2c3d"]}]"""), json)
    assertEquals(SchemaModelJson.decode(json), Right(SchemaModel(Vector(linked))))
  }

  test("every column type has a JSON name and round-trips") {
    val types = Vector(SqlType.Varchar(80), SqlType.Char(1), SqlType.Numeric(38, 2), SqlType.Timestamp(6),
      SqlType.TimestampWithTimeZone(3), SqlType.Time(0), SqlType.Integer, SqlType.BigInt, SqlType.Boolean, SqlType.Text,
      SqlType.Uuid, SqlType.SmallInt, SqlType.Real, SqlType.DoublePrecision, SqlType.Date, SqlType.Binary)
    val typed = customer.copy(columns = id +: types.zipWithIndex.map { (dataType, index) =>
      ColumnModel(SchemaId(s"7f3a9c21/c$index"), SqlIdentifier(s"c$index"), dataType)
    })
    val json = SchemaModelJson.encode(SchemaModel(Vector(typed)))
    Vector("char(1)", "numeric(38,2)", "time(0)", "smallint", "real", "double precision", "date", "binary").foreach { name =>
      assert(json.contains(s"\"type\":\"$name\""), s"$name in $json")
    }
    assertEquals(SchemaModelJson.decode(json), Right(SchemaModel(Vector(typed))))
    assert(failure(json.replace("numeric(38,2)", "numeric(38)")).contains("unknown type 'numeric(38)'"))
  }

  test("unique keys round-trip in key order") {
    val keyed = customer.copy(uniqueKeys = Vector(
      UniqueKeyModel(Vector(SchemaId("7f3a9c21/f34e45b6"))),
      UniqueKeyModel(Vector(SchemaId("7f3a9c21/1c2d3e4f"), SchemaId("7f3a9c21/f34e45b6")))
    ))
    val json = SchemaModelJson.encode(SchemaModel(Vector(keyed)))
    assert(json.contains(""""uniqueKeys":[{"columns":["7f3a9c21/f34e45b6"]},""" +
      """{"columns":["7f3a9c21/1c2d3e4f","7f3a9c21/f34e45b6"]}]"""), json)
    assertEquals(SchemaModelJson.decode(json), Right(SchemaModel(Vector(keyed))))
    assert(failure(json.replace("{\"columns\":[\"7f3a9c21/f34e45b6\"]}", "{\"cols\":[]}")).contains("expected columns"))
  }

  test("indexes round-trip with their column directions") {
    val indexed = customer.copy(indexes = Vector(
      IndexModel(Vector(IndexColumn(SchemaId("7f3a9c21/f34e45b6")))),
      IndexModel(Vector(IndexColumn(SchemaId("7f3a9c21/5a6b7c8d"), descending = true), IndexColumn(SchemaId("7f3a9c21/f34e45b6"))))
    ))
    val json = SchemaModelJson.encode(SchemaModel(Vector(indexed)))
    assert(json.contains(""""indexes":[{"columns":[{"id":"7f3a9c21/f34e45b6","descending":false}]},""" +
      """{"columns":[{"id":"7f3a9c21/5a6b7c8d","descending":true},{"id":"7f3a9c21/f34e45b6","descending":false}]}]"""), json)
    assertEquals(SchemaModelJson.decode(json), Right(SchemaModel(Vector(indexed))))
    assert(failure(json.replace("\"descending\":true", "\"descending\":1")).contains("true or false"))
  }

  test("column checks round-trip as allowed values or an integer range") {
    val checked = customer.copy(columns = customer.columns.map {
      case c if c.name.value == "nick_name" => c.copy(check = Some(ColumnCheck.AllowedValues(Vector("it's", "\"x\""))))
      case c if c.name.value == "points" => c.copy(check = Some(ColumnCheck.Range(Long.MinValue, -1)))
      case c => c
    })
    val json = SchemaModelJson.encode(SchemaModel(Vector(checked)))
    assert(json.contains(""""check":{"values":["it's","\"x\""]}"""), json)
    assert(json.contains(s""""check":{"min":${Long.MinValue},"max":-1}"""), json)
    assertEquals(SchemaModelJson.decode(json), Right(SchemaModel(Vector(checked))))
    assert(failure(json.replace("\"max\":-1", "\"max\":9223372036854775808")).contains("64-bit range"))
    assert(failure(json.replace("\"max\":-1", "\"maximum\":-1")).contains("either values or min and max"))
  }

  test("identity columns and sequences round-trip") {
    val model = SchemaModel(
      Vector(customer.copy(columns = customer.columns.map(c => if c.id == id.id then c.copy(dataType = SqlType.BigInt, identity = true) else c))),
      Vector(SequenceModel(SchemaId("7f3a9c21/0a1b2c3d/sequence"), QualifiedName(SqlIdentifier("customer_SEQ"), Some(SqlIdentifier("public"))), 1, 50))
    )
    val json = SchemaModelJson.encode(model)
    assert(json.contains(""""type":"bigint","nullable":false,"check":null,"identity":true"""), json)
    assert(json.endsWith(""""sequences":[{"id":"7f3a9c21/0a1b2c3d/sequence","catalog":null,"schema":"public",""" +
      """"name":"customer_SEQ","start":1,"increment":50}]}"""), json)
    assertEquals(SchemaModelJson.decode(json), Right(model))
    assert(failure(json.replace("\"increment\":50", "\"increment\":1.5")).contains("Invalid JSON"))
    assert(failure(json.replace("\"identity\":true", "\"identity\":\"yes\"")).contains("identity must be true or false"))
  }

  test("format 5, written before identity columns and sequences existed, still decodes to the same model and fingerprint") {
    val written = """{"format":5,"tables":[{"id":"t","catalog":null,"schema":"public","name":"t","columns":""" +
      """[{"id":"t/id","name":"id","type":"uuid","nullable":false,"check":null},{"id":"t/status","name":"status",""" +
      """"type":"varchar(10)","nullable":true,"check":{"values":["NEW","OLD"]}}],"primaryKey":["t/id"],""" +
      """"foreignKeys":[],"uniqueKeys":[],"indexes":[]}]}"""
    val model = SchemaModelJson.decode(written).toOption.get
    assert(model.sequences.isEmpty && model.tables.head.columns.forall(!_.identity))
    assertEquals(SchemaFingerprint.of(model), "4854bd7150afe372ceb85e2a9a3e9af7d81b4bbd69b2cea7daed768b11b0d520")
    assert(failure(written.dropRight(1) + ",\"sequences\":[]}").contains("sequences"))
  }

  test("format 4, written before column checks existed, still decodes to the same model and fingerprint") {
    val written = """{"format":4,"tables":[{"id":"t","catalog":null,"schema":"public","name":"t","columns":""" +
      """[{"id":"t/id","name":"id","type":"uuid","nullable":false},{"id":"t/parent","name":"parent","type":"uuid",""" +
      """"nullable":true}],"primaryKey":["t/id"],"foreignKeys":[{"columns":["t/parent"],"referencedTable":"t",""" +
      """"referencedColumns":["t/id"]}],"uniqueKeys":[{"columns":["t/parent"]}],""" +
      """"indexes":[{"columns":[{"id":"t/parent","descending":true}]}]}]}"""
    val model = SchemaModelJson.decode(written).toOption.get
    assert(model.tables.head.columns.forall(_.check.isEmpty))
    assertEquals(SchemaFingerprint.of(model), "2e078d3ab32f3cd52cb02314f213bcce5e9d22aba3700cd4ff457cb1d883afb7")
    val withCheck = written.replace("\"nullable\":false}", "\"nullable\":false,\"check\":null}")
    assertNotEquals(withCheck, written)
    assert(failure(withCheck).contains("check"))
  }

  test("format 3, written before indexes existed, still decodes to the same model and fingerprint") {
    val written = """{"format":3,"tables":[{"id":"t","catalog":null,"schema":"public","name":"t","columns":""" +
      """[{"id":"t/id","name":"id","type":"uuid","nullable":false},{"id":"t/parent","name":"parent","type":"uuid",""" +
      """"nullable":true}],"primaryKey":["t/id"],"foreignKeys":[{"columns":["t/parent"],"referencedTable":"t",""" +
      """"referencedColumns":["t/id"]}],"uniqueKeys":[{"columns":["t/parent"]}]}]}"""
    val model = SchemaModelJson.decode(written).toOption.get
    assertEquals(model.tables.head.uniqueKeys.size, 1)
    assertEquals(SchemaFingerprint.of(model), "1f76c2babda21275093a280117c171471900dd4fb6ab259d7fc1b655119b951e")
    val withIndexes = written.replace("\"t/parent\"]}]}]}", "\"t/parent\"]}],\"indexes\":[]}]}")
    assertNotEquals(withIndexes, written)
    assert(failure(withIndexes).contains("indexes"))
  }

  test("format 2, written before unique keys existed, still decodes to the same model and fingerprint") {
    val written = """{"format":2,"tables":[{"id":"t","catalog":null,"schema":"public","name":"t","columns":""" +
      """[{"id":"t/id","name":"id","type":"uuid","nullable":false},{"id":"t/parent","name":"parent","type":"uuid",""" +
      """"nullable":true}],"primaryKey":["t/id"],"foreignKeys":[{"columns":["t/parent"],"referencedTable":"t",""" +
      """"referencedColumns":["t/id"]}]}]}"""
    val model = SchemaModelJson.decode(written).toOption.get
    assertEquals(model.tables.head.foreignKeys.size, 1)
    assertEquals(SchemaFingerprint.of(model), "21ad02d6ea5bab3bdc4eac7924a109696d92ef1fc741f5070e04d581e5d18aa4")
    val withUniqueKeys = written.replace("\"t/id\"]}]}]}", "\"t/id\"]}],\"uniqueKeys\":[]}]}")
    assertNotEquals(withUniqueKeys, written)
    assert(failure(withUniqueKeys).contains("uniqueKeys"))
  }

  test("format 1, written before foreign keys existed, still decodes to the same model and fingerprint") {
    val written = """{"format":1,"tables":[{"id":"t","catalog":null,"schema":"public","name":"t","columns":""" +
      """[{"id":"t/id","name":"id","type":"uuid","nullable":false},{"id":"t/at","name":"at","type":"timestamp(6)",""" +
      """"nullable":true},{"id":"t/name","name":"name","type":"varchar(80)","nullable":true}],"primaryKey":["t/id"]}]}"""
    val model = SchemaModelJson.decode(written).toOption.get
    assert(model.tables.forall(_.foreignKeys.isEmpty))
    assertEquals(SchemaFingerprint.of(model), "085d2a77eff81e4854196e811715cfedfb1248fa6809e4cf86d5875f14cc990b")
    assert(failure(written.replace("\"primaryKey\":[\"t/id\"]", "\"primaryKey\":[\"t/id\"],\"foreignKeys\":[]"))
      .contains("foreignKeys"))
  }

  test("other format versions, missing or unknown fields and unknown types are rejected") {
    val json = SchemaModelJson.encode(model)
    assert(failure(json.replace("\"format\":6", "\"format\":7")).contains("format 7 is unsupported"))
    assert(failure(json.replace("\"format\":6", "\"format\":0")).contains("format 0 is unsupported"))
    assert(failure(json.replace(",\"sequences\":[]", "")).contains("expected format, sequences, tables"))
    assert(failure(json.replace(",\"foreignKeys\":[]", "")).contains("expected catalog, columns, foreignKeys"))
    assert(failure(json.replace("\"foreignKeys\":[]", "\"foreignKeys\":[{\"columns\":[]}]")).contains("referencedTable"))
    assert(failure("""{"tables":[]}""").contains("format is missing"))
    assert(failure(json.replace("\"catalog\":null,", "")).contains("expected catalog"))
    assert(failure(json.replace("\"catalog\":null,", "\"catalog\":null,\"comment\":\"x\",")).contains("comment"))
    assert(failure(json.replace("\"bigint\"", "\"money\"")).contains("unknown type 'money'"))
    assert(failure(json.replace("varchar(80)", "varchar(99999999999)")).contains("VARCHAR length"))
    assert(failure(json.replace("timestamp(6)", "timestamp(99999999999)")).contains("TIMESTAMP precision"))
    assert(failure(json.replace("timestamp(3) with time zone", "timestamptz(3)")).contains("unknown type"))
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
